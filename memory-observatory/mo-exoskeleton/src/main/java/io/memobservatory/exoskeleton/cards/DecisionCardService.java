package io.memobservatory.exoskeleton.cards;

import io.memobservatory.exoskeleton.criteria.CriteriaService;
import io.memobservatory.exoskeleton.model.CardReport;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.CardRepository;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 决策卡片的生成与查询（§4.10）。
 *
 * <p>分工：{@link CardPlanner} 是纯判断（哪个模板、三档怎么写、合并谁），本类只做两件事——
 * <b>取数</b>（复用 {@code CriteriaService.clusters()} 与 {@code RuleRepository}，不另起一套口径）
 * 与<b>落库</b>（新卡 / 刷新 / 重新提出 / 跳过）。与 {@code PromotionGate} / {@code PromotionService} 同一形态。
 *
 * <p>去重与抑制的规矩（每条都对应 §4.10.4 里某句对用户说过的后果）：
 * <ul>
 *   <li>未答卡 → <b>刷新</b>卡面，保留 created_at（还是同一张卡）；</li>
 *   <li>答过「下个周期再报」→ <b>重新提出</b>（{@link CardPlanner#REISSUABLE_ANSWERS}）；</li>
 *   <li>答过「驳回 / 放弃」→ 覆盖的簇进抑制集，<b>不再出现在候选里</b>；</li>
 *   <li>答过其余任何一项 → 人已表态，<b>不再打扰</b>（不刷新、不重提）。</li>
 * </ul>
 */
@Service
public class DecisionCardService {

    private final CardRepository cards;
    private final RuleRepository rules;
    private final CriteriaService criteria;
    private final CardPlanner planner = new CardPlanner();

    @Value("${mo.exoskeleton.cards.cap:5}")
    private int cap;
    @Value("${mo.exoskeleton.cards.expired-visible:false}")
    private boolean expiredVisible;
    @Value("${mo.exoskeleton.min-support:30}")
    private int minSupport;
    @Value("${mo.exoskeleton.audit.hit-share-max:0.20}")
    private double hitShareMax;
    @Value("${mo.exoskeleton.audit.churn-high:3}")
    private int churnHigh;

    public DecisionCardService(CardRepository cards, RuleRepository rules, CriteriaService criteria) {
        this.cards = cards;
        this.rules = rules;
        this.criteria = criteria;
    }

    // ==================================================================
    // 生成（§4.10.5 流水线）
    // ==================================================================

    /**
     * 跑一轮生成：取数 → 规划 → 落库。
     *
     * <p>刻意不做调度（与 {@code CriteriaController.recompute}、{@code TaskTypeController.classify} 同规矩）：
     * 出卡的节奏应当由人决定，自动出卡会把「卡片数」这个反向指标变成噪声。要定时另配。
     */
    @Transactional
    public CardReport.GenerateResult generate() {
        RulesReport.ClusterCriteria clusterCriteria = criteria.clusters();
        long totalTurns = 0;
        for (RulesReport.ClusterRow row : clusterCriteria.rows()) {
            totalTurns += row.turns();
        }

        Instant now = Instant.now();
        CardPlanner.Plan plan = planner.plan(new CardPlanner.Input(
                rules.support(),
                rules.rules(),
                clusterCriteria.rows(),
                cards.rulesWithEvals(),
                cards.suppressedClusters(),
                totalTurns,
                minSupport,
                hitShareMax,
                churnHigh,
                cap,
                now));

        int created = 0;
        int refreshed = 0;
        int reissued = 0;
        int skipped = 0;

        for (CardPlanner.Candidate candidate : plan.cards()) {
            String cardId = candidate.card().cardId();
            Optional<CardRepository.Stored> existing = cards.find(cardId);
            if (existing.isEmpty()) {
                created += cards.insert(candidate);
                continue;
            }
            CardRepository.Stored stored = existing.get();
            if ("OPEN".equals(stored.status())) {
                refreshed += cards.refresh(candidate);
            } else if (CardPlanner.REISSUABLE_ANSWERS.contains(stored.answer())) {
                reissued += cards.reissue(candidate);
            } else {
                // 人已表过态（采纳 / 驳回 / 停用 / 记录建议…）：同一张卡不再打扰。
                skipped++;
            }
        }

        return new CardReport.GenerateResult(
                plan.planned(), plan.merged(), created, refreshed, reissued, skipped,
                cards.suppressedClusters().size(), plan.queued(), cap,
                describe(plan),
                note(plan, created, refreshed, reissued, skipped, cap));
    }

    private static List<String> describe(CardPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        for (CardPlanner.TemplateStatus s : plan.templates()) {
            out.add(s.triggered()
                    ? "%s %s：触发 %d 张".formatted(s.template(), s.label(), s.candidates())
                    : "%s %s：未触发 —— %s".formatted(s.template(), s.label(), s.reason()));
        }
        return out;
    }

    private static String note(CardPlanner.Plan plan, int created, int refreshed, int reissued,
                               int skipped, int cap) {
        Map<String, Integer> byTemplate = new LinkedHashMap<>();
        for (CardPlanner.Candidate c : plan.cards()) {
            byTemplate.merge(c.card().template().label(), 1, Integer::sum);
        }
        return "生成流水线（§4.10.5）：候选 %d → 合并同类消掉 %d → 排序截断后出 %d 张、排队 %d 张（单轮上限 %d）。"
                .formatted(plan.planned(), plan.merged(), plan.cards().size(), plan.queued(), cap)
                + "落库：新建 %d、刷新未答 %d、重新提出 %d、跳过（人已表态）%d。".formatted(created, refreshed, reissued, skipped)
                + "本轮出卡：" + (byTemplate.isEmpty() ? "无" : byTemplate.toString()) + "。"
                + "六个模板都在库里，能触发的才出——未触发的模板在 templates 字段里逐条给出原因，不编证据。";
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 卡片清单。默认不含已到期未答的卡——到期即不再展示（不替人作决定，也不反复打扰）。 */
    public CardReport list(boolean includeExpired) {
        Instant now = Instant.now();
        List<CardReport.CardView> views = new ArrayList<>();
        int expired = 0;
        for (CardRepository.Stored s : cards.list(null)) {
            if (s.expired(now)) {
                expired++;
                if (!includeExpired) {
                    continue;
                }
            }
            views.add(s.view(now));
        }
        int open = cards.count("OPEN");
        int answered = cards.count("ANSWERED");
        String note = "卡片是数据不是模板字符串（§4.10.4）：payload 是生成**当时**的卡面快照，"
                + "判据后来重算也不回填。到期只是不再展示，不改变卡片状态——"
                + "过期未答的卡共 %d 张，%s。选项恒为三档、默认项是「不动」；逃逸口（看原始样本）不占选项位（§4.10.7）。"
                        .formatted(expired, includeExpired ? "本次已包含" : "本次已隐去，加 ?includeExpired=true 可见");
        return new CardReport(views, new CardReport.Stats(open, answered, expired, cap, note), note);
    }

    /** 配置为「默认隐藏到期卡」时，控制器不必再传参。 */
    public CardReport list() {
        return list(expiredVisible);
    }
}