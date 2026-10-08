package io.memobservatory.exoskeleton.cards;

import io.memobservatory.exoskeleton.audit.RuleLifecycleService;
import io.memobservatory.exoskeleton.model.CardReport;
import io.memobservatory.exoskeleton.model.DecisionCard;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.PromotionDecision;
import io.memobservatory.exoskeleton.promote.PromotionService;
import io.memobservatory.exoskeleton.storage.CardRepository;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 卡片作答 → 动作回写（§4.10）。
 *
 * <p><b>这个类是卡片的「手」，它自己不判断</b>：能不能采纳由闸门判、能不能停用由状态机判。
 * 三条纪律（都是既有定案的直接推论）：
 * <ol>
 *   <li><b>T1 的「采纳」不等于上线</b>——一律走 {@link PromotionService#promote}
 *       （{@code state=LIVE} 的唯一入口，§4.11 纪律 1）。当前闸门因缺证据恒拦，所以「采纳」后
 *       会被判拦截：这是<b>正确行为</b>，卡片只提出上线请求，不放行；</li>
 *   <li><b>任何选项都不把规则改回 LIVE</b>（只降不升，A5）。T3 的「降命中面」通过 {@code revise}
 *       升新版本退回影子实现，不是直接改状态；</li>
 *   <li><b>不该由卡片改的东西就不改</b>：T2 的档位（阈值）与 T5 的门槛都只记录建议——
 *       它们改的是配置，一张卡不该顺手改掉全局判定口径。这几档的后果在卡面上就写明「本版仅记录」。</li>
 * </ol>
 *
 * <p>抑制与再出由 {@link CardRepository} 按答案读出来（不在这里维护内存状态）：
 * 答「驳回 / 放弃」的卡，其覆盖的簇进抑制集，下一轮不再出现在候选里。
 */
@Service
public class CardActionService {

    private final CardRepository cards;
    private final RuleRepository rules;
    private final RuleLifecycleService lifecycle;
    private final PromotionService promotion;

    public CardActionService(CardRepository cards, RuleRepository rules,
                             RuleLifecycleService lifecycle, PromotionService promotion) {
        this.cards = cards;
        this.rules = rules;
        this.lifecycle = lifecycle;
        this.promotion = promotion;
    }

    /** 一次回写的结果：动作名 + 影响行数 + 说明。 */
    private record Outcome(String action, int affected, String text) {
    }

    @Transactional
    public CardReport.AnswerResult answer(String cardId, String answerKey) {
        Optional<CardRepository.Stored> found = cards.find(cardId);
        if (found.isEmpty()) {
            return new CardReport.AnswerResult(cardId, answerKey, null, "卡片不存在", 0,
                    "库里没有这张卡，无可作答。生成卡片走 POST /api/v1/exoskeleton/cards/generate。");
        }
        CardRepository.Stored stored = found.get();
        if (!"OPEN".equals(stored.status())) {
            return new CardReport.AnswerResult(cardId, answerKey, null, "已答过", 0,
                    "这张卡已经答过（%s，%s）——重复作答不覆盖第一次的决定。".formatted(stored.answer(), stored.answeredAt()));
        }

        DecisionCard card = stored.card();
        List<DecisionCard.Option> options = card.options();
        boolean legal = options.stream().anyMatch(o -> o.key().equals(answerKey));
        if (!legal) {
            return new CardReport.AnswerResult(cardId, answerKey, null, "选项非法", 0,
                    "本卡选项固定三档、位置固定（§4.10.2 约束二）："
                            + options.stream().map(DecisionCard.Option::key).collect(Collectors.joining(" / "))
                            + "。逃逸口（看原始样本）不占选项位（§4.10.7）。");
        }

        cards.answer(cardId, answerKey);
        Outcome outcome = apply(card, answerKey);

        String note = "卡片已作答并记下（payload 快照不回填）。" + outcome.text()
                + " 抑制与再出按答案推导：答「驳回 / 放弃」的簇下一轮不再出现在候选里；"
                + "答「下个周期再报」的卡下轮刷新重提；其余答案视为已表态，同一张卡不再打扰。";
        return new CardReport.AnswerResult(cardId, answerKey, outcome.action(), "已记录并执行", outcome.affected(), note);
    }

    // ==================================================================
    // 逐模板回写：三个 key 的后果必须与卡面 effect 文案一致
    // ==================================================================

    private Outcome apply(DecisionCard card, String answer) {
        return switch (card.template()) {
            case T1 -> t1(card, answer);
            case T3 -> t3(card, answer);
            case T5 -> t5(card, answer);
            case T2 -> recordOnly("档位建议已记录（本版不改配置：阈值需改配置并重启）");
            case T4 -> recordOnly("抽检结论已记录（此项不改变任何系统状态）");
            case T6 -> recordOnly("裁决已记录（一致性审计未实现，本版无自动执行）");
        };
    }

    /** T1：采纳 → 走闸门；驳回 → 停用；再观察 → 什么都不做。 */
    private Outcome t1(DecisionCard card, String answer) {
        List<String> subjects = card.subjects();
        switch (answer) {
            case "approve" -> {
                List<String> lines = new ArrayList<>();
                int live = 0;
                for (String key : subjects) {
                    PromotionDecision d = promotion.promote(key);
                    live += d.passed() ? 1 : 0;
                    lines.add("「%s」：%s".formatted(key, d.passed() ? "闸门放行，已置 LIVE" : "闸门拦截 —— " + firstFail(d)));
                }
                return new Outcome("PROMOTE", live,
                        "已提交晋升闸门 %d 个簇：%s。注意「采纳」不等于生效——闸门是 LIVE 的唯一入口，缺证据会被拦（§4.11 纪律 1）。"
                                .formatted(subjects.size(), String.join("；", lines)));
            }
            case "reject" -> {
                int n = 0;
                for (String key : subjects) {
                    n += lifecycle.retire(key);
                }
                return new Outcome("RETIRE", n,
                        "已停用 %d 条规则（state=RETIRED，不可逆）；这些簇同时进入抑制集，不再重复提议。".formatted(n));
            }
            default -> {   // defer：保持影子
                return new Outcome("NONE", 0, "保持影子、什么都没改；下个周期重新评估时会刷新这张卡。");
            }
        }
    }

    /** T3：停用 → RETIRED；降命中面 → 升新版本退回影子（待人工补全收窄条件）；保留 → 不动。 */
    private Outcome t3(DecisionCard card, String answer) {
        List<String> subjects = card.subjects();
        switch (answer) {
            case "retire" -> {
                int n = 0;
                for (String key : subjects) {
                    n += lifecycle.retire(key);
                }
                return new Outcome("RETIRE", n, "已停用 %d 条规则（state=RETIRED，不再注入）。".formatted(n));
            }
            case "narrow" -> {
                int n = 0;
                List<String> lines = new ArrayList<>();
                for (String key : subjects) {
                    Optional<MemoryRule> rule = rules.latest(key);
                    if (rule.isEmpty()) {
                        lines.add("「%s」：库里没有这条规则，跳过".formatted(key));
                        continue;
                    }
                    int version = lifecycle.revise(key, rule.get().body() + NARROW_MARKER);
                    if (version > 0) {
                        n++;
                        lines.add("「%s」：v%d（SHADOW）".formatted(key, version));
                    } else {
                        lines.add("「%s」：修订失败".formatted(key));
                    }
                }
                return new Outcome("REVISE", n,
                        "已升新版本并退回影子（停止注入），标记待人工补全收窄条件：%s。"
                                .formatted(String.join("；", lines))
                                + "本版不编造收窄条件——只把规则挪出注入面，收窄内容由人补全后再过闸门。");
            }
            default -> {   // hold：保留待复核
                return new Outcome("NONE", 0, "保留待复核：什么都没改，下个周期再报。");
            }
        }
    }

    /**
     * 收窄标记：追加在规则正文末尾。
     *
     * <p>为什么只加标记、不写收窄条件：收窄条件要读规则语义才能写对，系统不编。
     * 而「退回影子」本身就已经把爆炸半径归零（影子不注入），风险处置是即时生效的——
     * 待补的是规则内容，不是安全动作。
     */
    private static final String NARROW_MARKER = """

            [§2.6 命中面收窄 · 待人工补全] 本条由决策卡片（T3）触发「降命中面」：已升为新版本并退回影子（停止注入）。
            收窄条件需人工补全后再走晋升闸门；触发时的读数见 memory_rule_evals 与卡片 payload。""";

    /** T5：放弃 → 抑制（由答案本身承载）；继续收集 / 放宽门槛 → 不改系统状态。 */
    private Outcome t5(DecisionCard card, String answer) {
        if ("drop".equals(answer)) {
            return new Outcome("SUPPRESS", card.subjects().size(),
                    "已放弃 %d 个簇：进入抑制集，下一轮不再出现在候选里（要恢复就把这几张卡的状态清回未答）。"
                            .formatted(card.subjects().size()));
        }
        if ("relax".equals(answer)) {
            return recordOnly("放宽门槛的建议已记录（本版不改配置：门槛一改，所有簇的支撑度判定都会变，不由一张卡决定）");
        }
        return new Outcome("NONE", 0, "继续收集：什么都没改，等样本长起来。");
    }

    private static Outcome recordOnly(String text) {
        return new Outcome("NONE", 0, text + "——这一档的后果在卡面上就写明了「本版仅记录」，作答只留痕。");
    }

    /** 取闸门拦截的第一条原因（报告里给出可追溯的理由，不只一个布尔）。 */
    private static String firstFail(PromotionDecision d) {
        return d.layers().stream()
                .filter(l -> l.outcome() == PromotionDecision.Outcome.FAIL)
                .findFirst()
                .map(PromotionDecision.Layer::name)
                .orElseGet(() -> d.layers().stream()
                        .filter(l -> l.outcome() == PromotionDecision.Outcome.UNKNOWN)
                        .count() + " 层不可测");
    }
}