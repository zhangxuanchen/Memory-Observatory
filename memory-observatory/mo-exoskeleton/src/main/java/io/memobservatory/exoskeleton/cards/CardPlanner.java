package io.memobservatory.exoskeleton.cards;

import io.memobservatory.exoskeleton.model.CardTemplate;
import io.memobservatory.exoskeleton.model.DecisionCard;
import io.memobservatory.exoskeleton.model.DecisionCard.Evidence;
import io.memobservatory.exoskeleton.model.DecisionCard.Option;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.RulesReport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 决策卡片生成器（§4.10.5 流水线）的<b>纯逻辑</b>：
 * 候选 → 选模板 → 填槽（标题 / 三个带对照的数 / 三档后果）→ 合并同类 → 排序截断。
 *
 * <p><b>为什么拧成纯函数</b>：这一段是「把判断变成选择题」里唯一有判断的地方——用哪个模板、
 * 三档怎么写、谁与谁合并、谁排前面。把它从取数与落库里剥出来才验得动（可脱离 Spring 单测）。
 * 取数在 {@code DecisionCardService}、落库在 {@code CardRepository}，与
 * {@code PromotionGate} / {@code PromotionService} 的分工同一形态。
 *
 * <p><b>诚实原则（沿用 B7.3）</b>：证据只填真有的数，缺的项不填 0 也不编。
 * 因此时间轴上的现实是：T1 / T3 / T5 有真触发路径，T2 需先有 LIVE 规则，
 * T4 / T6 依赖尚未实现的抽检池与一致性审计——六个模板都在库里，但<b>能触发的才出</b>。
 */
public class CardPlanner {

    // ------------------------------------------------------------------
    // 模板常量：三档选项、默认项（恒为「不动」）、严重度、到期天数
    // 结构固定、人工维护（§4.10.1）：形状必须稳定，人只学一次。
    // ------------------------------------------------------------------

    /** 严重度只用于 §4.10.5 的排序：越界/硬化 > 可上线 > 样本不足。 */
    private static final Map<CardTemplate, Integer> SEVERITY = Map.of(
            CardTemplate.T3, 30,   // 硬化 ＝ 已上线且无改善，最急
            CardTemplate.T6, 25,
            CardTemplate.T1, 20,
            CardTemplate.T2, 20,
            CardTemplate.T4, 15,
            CardTemplate.T5, 10);

    /** 到期天数：越急的卡给越短的窗口（到期只是不再展示，不替人作决定）。 */
    private static final Map<CardTemplate, Integer> EXPIRES_DAYS = Map.of(
            CardTemplate.T3, 7,
            CardTemplate.T1, 14,
            CardTemplate.T2, 14,
            CardTemplate.T6, 14,
            CardTemplate.T4, 30,
            CardTemplate.T5, 30);

    /** 「命中面接近上限」的起算比例：达到它就该问要不要收紧（T2）。 */
    private static final double NEAR_HIT_SHARE = 0.15;

    // ------------------------------------------------------------------
    // 输入 / 输出
    // ------------------------------------------------------------------

    /**
     * 生成器的输入：全是纯数据。
     *
     * <p>{@code evaluatedRules} = 已有评估快照的 rule_id 集合，用来判「影子期是否已经过了一轮」——
     * 这是 T1「影子期结束」在当前数据下唯一可判的依据。
     * {@code suppressed} = 曾被驳回 / 放弃、不再提议的簇（§4.10.4 的 reject 后果）。
     */
    public record Input(
            List<RulesReport.SupportBucket> support,
            List<RulesReport.RuleView> rules,
            List<RulesReport.ClusterRow> clusters,
            Set<String> evaluatedRules,
            Set<String> suppressed,
            long totalTurns,
            int minSupport,
            double hitShareMax,
            int churnHigh,
            int cap,
            Instant now) {
    }

    /** 一个候选：卡片 + 排序用的严重度 + 到期时刻。 */
    public record Candidate(DecisionCard card, int severity, Instant expiresAt) {
    }

    /** 一个模板的触发情况（响应里如实列出六个，未触发的给原因——「六个都建，能触发的才出」）。 */
    public record TemplateStatus(String template, String label, boolean triggered, int candidates, String reason) {
    }

    /** 一次规划的产出：截断后的卡片 + 六个模板的触发情况 + 各步计数（能对上账）。 */
    public record Plan(List<Candidate> cards, List<TemplateStatus> templates,
                       int planned, int merged, int queued) {
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    public Plan plan(Input in) {
        Map<String, RulesReport.ClusterRow> clusterByKey = new LinkedHashMap<>();
        for (RulesReport.ClusterRow row : in.clusters()) {
            clusterByKey.put(row.key(), row);
        }

        // ① 各模板产出候选（未合并）
        List<Candidate> raw = new ArrayList<>();
        List<TemplateStatus> statuses = new ArrayList<>();

        statuses.add(t1(in, clusterByKey, raw));
        statuses.add(t2(in, clusterByKey, raw));
        statuses.add(t3(in, clusterByKey, raw));
        statuses.add(notTriggered(CardTemplate.T4,
                "抽检池与月度节奏未实现：校准抽检要「按月从已答卡片里抽样复核」，"
                        + "本版没有抽检池，也没有月度计时器——故不产出卡片（不编样本）。"));
        statuses.add(t5(in, raw));
        statuses.add(notTriggered(CardTemplate.T6,
                "记忆一致性审计未实现（《分类记忆体》§4.10）：没有疑点来源，"
                        + "无从产出矛盾裁决卡——故不产出（不编疑点）。"));

        int planned = raw.size();

        // ② 合并同类：证据一致的候选合成一张（§4.10.5）
        List<Candidate> merged = merge(raw);
        int mergedAway = planned - merged.size();

        // ③ 排序截断：严重度优先、其次先到期者先出，末尾按 cardId 保证确定性
        List<Candidate> sorted = new ArrayList<>(merged);
        sorted.sort(Comparator
                .comparingInt(Candidate::severity).reversed()
                .thenComparing(c -> c.expiresAt() == null ? Instant.MAX : c.expiresAt())
                .thenComparing(c -> c.card().cardId()));

        int cap = Math.max(0, in.cap());
        List<Candidate> shown = sorted.size() <= cap ? sorted : new ArrayList<>(sorted.subList(0, cap));
        int queued = sorted.size() - shown.size();

        // 触发情况按截断后的实际出卡回填：免得报「触发了 30 张」而实际只出 5 张。
        // 条件成立但被截断的，要说明是「排队」而不是「触发不了」——两者含义完全不同。
        List<TemplateStatus> filled = new ArrayList<>();
        for (TemplateStatus s : statuses) {
            int count = (int) shown.stream().filter(c -> c.card().template().name().equals(s.template())).count();
            if (count > 0) {
                filled.add(new TemplateStatus(s.template(), s.label(), true, count, ""));
            } else if (s.triggered()) {
                filled.add(new TemplateStatus(s.template(), s.label(), false, 0,
                        "条件成立，但被排序截断排队在本轮之外（下一轮或提高上限后出）。"));
            } else {
                filled.add(s);
            }
        }

        return new Plan(shown, filled, planned, mergedAway, queued);
    }

    // ------------------------------------------------------------------
    // T1 规则审批：影子期结束、证据齐
    // ------------------------------------------------------------------

    private TemplateStatus t1(Input in, Map<String, RulesReport.ClusterRow> clusterByKey, List<Candidate> out) {
        int before = out.size();
        for (RulesReport.RuleView view : in.rules()) {
            MemoryRule rule = view.rule();
            if (rule.state() != MemoryRule.State.SHADOW) {
                continue;
            }
            String key = rule.clusterKey();
            if (key == null || in.suppressed().contains(key)) {
                continue;   // 曾被驳回：退出候选，不再重复提议（§4.10.4）
            }
            RulesReport.ClusterRow cr = clusterByKey.get(key);
            if (cr == null || cr.transitions() == 0) {
                continue;   // 「准」在本簇量不到 ⇒ 证据不齐，不出卡（不是「证据说没问题」）
            }
            if (!in.evaluatedRules().contains(rule.ruleId())) {
                continue;   // 影子期还没过任何一轮，无所谓「结束」
            }

            List<Evidence> evidence = new ArrayList<>();
            evidence.add(new Evidence("支撑样本", String.valueOf(cr.turns()), null, null));
            evidence.add(new Evidence("簇内隐式纠正率", pct(cr.correctionRate()),
                    "相邻对 n=" + cr.transitions() + "（本簇无对照口径）", null));
            Double costDelta = tokenDelta(cr);
            if (costDelta != null) {
                evidence.add(new Evidence("单位成本 vs 对照", signedPct(costDelta),
                        "对照 " + trim(cr.controlAvgToken()) + " token", costDelta <= 0));
            }

            String note = "本簇仅「准」可测（簇内可比较相邻 turn 对 n=" + cr.transitions() + "）；"
                    + "「记忆有效性 / 多」三项判据结构性不可测（附录 B7），成本对照取自簇内 holdout。"
                    + "采纳 = 提交晋升闸门，不等于生效——闸门是 LIVE 的唯一入口，缺证据会被拦。";

            out.add(new Candidate(card(CardTemplate.T1, key,
                    "「" + key + "」类任务，建议启用记忆约束",
                    List.of(key), evidence, t1Options(), "defer",
                    "可随时停用，一分钟内生效", note, in.now()), SEVERITY.get(CardTemplate.T1), at(in, CardTemplate.T1)));
        }
        return status(CardTemplate.T1, out.size() - before,
                "需要「影子规则 + 该簇『准』可测 + 影子期已过至少一轮评估」，三者同时成立才出卡。");
    }

    private static List<Option> t1Options() {
        return List.of(
                new Option("approve", "采纳",
                        "立即提交晋升闸门；通过则置为 LIVE 并开始注入（闸门是唯一入口，缺证据会被拦）"),
                new Option("defer", "再观察一轮", "保持影子，下个周期再报"),
                new Option("reject", "驳回", "退出候选，同一簇不再重复提议"));
    }

    // ------------------------------------------------------------------
    // T2 档位调整：命中面已接近上限
    // ------------------------------------------------------------------

    private TemplateStatus t2(Input in, Map<String, RulesReport.ClusterRow> clusterByKey, List<Candidate> out) {
        int before = out.size();
        for (RulesReport.RuleView view : in.rules()) {
            MemoryRule rule = view.rule();
            if (rule.state() != MemoryRule.State.LIVE) {
                continue;   // 只有真的在注入的规则才有「档位」可调
            }
            String key = rule.clusterKey();
            RulesReport.ClusterRow cr = key == null ? null : clusterByKey.get(key);
            Double hitShare = hitShare(cr, in.totalTurns());
            if (cr == null || hitShare == null || hitShare < NEAR_HIT_SHARE || hitShare > in.hitShareMax()) {
                continue;   // 未接近上限或已越界（越界归 §2.6 自动降级，不归人）
            }

            List<Evidence> evidence = List.of(
                    new Evidence("命中面", pct(hitShare), "上限 " + pct(in.hitShareMax()), true),
                    new Evidence("支撑样本", String.valueOf(cr.turns()), null, null));

            String note = "命中面已进入上限的 " + pct(NEAR_HIT_SHARE) + "–" + pct(in.hitShareMax())
                    + " 区间：再扩一点就会被 §2.6 限制一自动退回影子。"
                    + "三档的后果都是「只记录」——本版不改配置：阈值改动需改配置并重启，不由一张卡顺手改掉全局判定口径。";

            out.add(new Candidate(card(CardTemplate.T2, key,
                    "「" + key + "」命中面接近上限，是否调整档位",
                    List.of(key), evidence, t2Options(), "hold",
                    "档位改动需改配置，可随时回退", note, in.now()), SEVERITY.get(CardTemplate.T2), at(in, CardTemplate.T2)));
        }
        // 未触发的原因要按实际数据说：不能一律断言「当前无 LIVE 规则」——
        // 有 LIVE 规则、只是命中面没进区间，与压根没有 LIVE 规则，是两回事（不编证据，B7.3）。
        long liveRules = in.rules().stream().filter(v -> v.rule().state() == MemoryRule.State.LIVE).count();
        String reason = liveRules == 0
                ? "当前无 LIVE 规则，故不触发（档位只对已在注入的规则有意义）。"
                : "有 " + liveRules + " 条 LIVE 规则，但其命中面均未进入 " + pct(NEAR_HIT_SHARE) + "–"
                        + pct(in.hitShareMax()) + " 区间（越界者归 §2.6 自动降级，不归人）。";
        return status(CardTemplate.T2, out.size() - before,
                "需要「LIVE 规则 + 命中面进入 " + pct(NEAR_HIT_SHARE) + "–上限」区间；" + reason);
    }

    private static List<Option> t2Options() {
        return List.of(
                new Option("conservative", "更保守", "记录收紧建议（本版不改配置：阈值需改配置并重启）"),
                new Option("hold", "保持", "维持当前档位（不动）"),
                new Option("aggressive", "更激进", "记录放宽建议（本版不改配置；放宽仍受 §2.6 命中面上限约束）"));
    }

    // ------------------------------------------------------------------
    // T3 硬化处置：命中面扩了但「准」无改善（§2.6 第三条落下的印记）
    // ------------------------------------------------------------------

    private TemplateStatus t3(Input in, Map<String, RulesReport.ClusterRow> clusterByKey, List<Candidate> out) {
        int before = out.size();
        for (RulesReport.RuleView view : in.rules()) {
            MemoryRule rule = view.rule();
            if (rule.hardenedAt() == null || rule.state() != MemoryRule.State.LIVE) {
                continue;   // 只处置仍在注入的硬化规则；已退回影子/停用的无需再动
            }
            String key = rule.clusterKey();
            RulesReport.ClusterRow cr = key == null ? null : clusterByKey.get(key);
            Double hitShare = hitShare(cr, in.totalTurns());

            List<Evidence> evidence = new ArrayList<>();
            if (hitShare != null) {
                evidence.add(new Evidence("命中面", pct(hitShare), "上限 " + pct(in.hitShareMax()),
                        hitShare <= in.hitShareMax()));
            }
            if (cr != null && cr.transitions() > 0) {
                evidence.add(new Evidence("簇内隐式纠正率", pct(cr.correctionRate()), "越低越好（§2.2 ↓）", null));
            }
            evidence.add(new Evidence("版本数", String.valueOf(view.versionCount()),
                    "观察窗 " + in.churnHigh() + " 版内", view.churn() < in.churnHigh()));

            String note = "硬化由 §2.6 第三条落印：连续两个评估周期「准」无改善。"
                    + "印记只提示、不处置（审计不会停用它），处置由这张卡交给人。"
                    + "三档里「降命中面」通过升新版本退回影子实现——不编造收窄条件，只标记待人工补全。";

            out.add(new Candidate(card(CardTemplate.T3, key,
                    "「" + key + "」规则已标记硬化：命中面扩了但「准」无改善",
                    List.of(key), evidence, t3Options(), "hold",
                    "可随时停用；停用不可逆", note, in.now()), SEVERITY.get(CardTemplate.T3), at(in, CardTemplate.T3)));
        }
        return status(CardTemplate.T3, out.size() - before,
                "需要「已落硬化印记的 LIVE 规则」；硬化需连续两个评估周期，当前快照表刚建、尚未自然触发。");
    }

    private static List<Option> t3Options() {
        return List.of(
                new Option("retire", "停用", "该规则置为 RETIRED，不再注入（不可逆）"),
                new Option("narrow", "降命中面",
                        "退回影子并升一个新版本，标记待人工补全收窄条件；过后需重新过闸门"),
                new Option("hold", "保留待复核", "保持现状，下个周期再报（不动）"));
    }

    // ------------------------------------------------------------------
    // T5 样本不足：支撑度不够的簇
    // ------------------------------------------------------------------

    private TemplateStatus t5(Input in, List<Candidate> out) {
        int before = out.size();
        for (RulesReport.SupportBucket bucket : in.support()) {
            if (bucket.supported() || in.suppressed().contains(bucket.key())) {
                continue;
            }
            long gap = Math.max(0, in.minSupport() - bucket.turns());
            List<Evidence> evidence = List.of(
                    new Evidence("当前样本", String.valueOf(bucket.turns()), null, null),
                    new Evidence("门槛", String.valueOf(in.minSupport()), null, null),
                    new Evidence("缺口", String.valueOf(gap), null, null));

            String note = "支撑度不足的簇不进起草候选（§4.4 第 4 条）。"
                    + "三档里「放宽门槛」本版只记录建议、不自动改配置——门槛一降，"
                    + "所有簇的支撑度判定都会变，那不是一张卡该改的事。";

            out.add(new Candidate(card(CardTemplate.T5, bucket.key(),
                    "「" + bucket.key() + "」样本不足，是否继续",
                    List.of(bucket.key()), evidence, t5Options(), "collect",
                    "放弃后该簇不再提议，可由清空卡片重置", note, in.now()),
                    SEVERITY.get(CardTemplate.T5), at(in, CardTemplate.T5)));
        }
        return status(CardTemplate.T5, out.size() - before,
                "支撑度 < 最小支撑度（" + in.minSupport() + "）的簇即触发；这是当前唯一能自然出量的模板。");
    }

    private static List<Option> t5Options() {
        return List.of(
                new Option("collect", "继续收集", "什么都不做，等样本长起来（不动）"),
                new Option("relax", "放宽门槛", "记录放宽建议（本版不改配置：门槛改动需改配置并重启）"),
                new Option("drop", "放弃该簇", "该簇退出候选，不再重复提议"));
    }

    // ------------------------------------------------------------------
    // 合并同类（§4.10.5）：证据一致的候选合成一张
    // ------------------------------------------------------------------

    /**
     * 按「模板 + 证据签名 + 默认项」分组，同组 ≥2 个才合并成一张。
     *
     * <p>为什么这是卡片数量的主要控制手段：T5 会在十几个小簇上同时触发，逐个出卡毫无意义——
     * 「这些簇样本都不足」本来就是<b>一个</b>判断。合并后的卡覆盖多个簇，作答时一并执行。
     */
    private static List<Candidate> merge(List<Candidate> raw) {
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate c : raw) {
            groups.computeIfAbsent(signature(c.card()), k -> new ArrayList<>()).add(c);
        }

        List<Candidate> out = new ArrayList<>();
        for (List<Candidate> group : groups.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            out.add(coalesce(group));
        }
        return out;
    }

    /** 证据签名：模板 + 默认项 + 逐条证据（顺序也参与，保持三档位置固定的确定性）。 */
    private static String signature(DecisionCard card) {
        StringBuilder sb = new StringBuilder(card.template().name()).append('|').append(card.defaultOption());
        for (Evidence e : card.evidence()) {
            sb.append('|').append(e.label()).append('=').append(e.value()).append('~').append(e.compare()).append('~').append(e.better());
        }
        return sb.toString();
    }

    /** 合成一张卡：覆盖簇取并集，卡面取组内第一张，note 里如实写上被合并的簇。 */
    private static Candidate coalesce(List<Candidate> group) {
        DecisionCard first = group.get(0).card();
        Set<String> subjects = new TreeSet<>();
        for (Candidate c : group) {
            subjects.addAll(c.card().subjects());
        }
        List<String> keys = new ArrayList<>(subjects);
        String hash = Integer.toHexString(String.join(",", keys).hashCode());
        String cardId = first.template().name() + ":merged:" + hash;

        String note = first.note() + " 本卡由 §4.10.5「合并同类」合成："
                + group.size() + " 个候选证据一致（" + String.join("、", keys) + "），"
                + "作答将一并作用于这 " + keys.size() + " 个簇。";

        DecisionCard merged = new DecisionCard(cardId, first.template(), keys, first.title(),
                first.evidence(), first.options(), first.defaultOption(), first.reversible(),
                first.expiresInDays(), note);

        int severity = group.stream().mapToInt(Candidate::severity).max().orElse(0);
        Instant expires = group.stream().map(Candidate::expiresAt)
                .min(Comparator.nullsLast(Comparator.naturalOrder())).orElse(null);
        return new Candidate(merged, severity, expires);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static DecisionCard card(CardTemplate template, String key, String title, List<String> subjects,
                                     List<Evidence> evidence, List<Option> options, String defaultOption,
                                     String reversible, String note, Instant now) {
        String cardId = template.name() + ":cluster:" + key;
        return new DecisionCard(cardId, template, subjects, title, evidence, options, defaultOption,
                reversible, EXPIRES_DAYS.get(template), note);
    }

    private static Instant at(Input in, CardTemplate template) {
        return in.now().plus(Duration.ofDays(EXPIRES_DAYS.get(template)));
    }

    /** 命中面 = 该簇非 holdout turn / 全部 turn（§2.6 限制一）。分不清命中组时返回 null。 */
    private static Double hitShare(RulesReport.ClusterRow cr, long totalTurns) {
        if (cr == null || totalTurns == 0) {
            return null;
        }
        return (double) (cr.turns() - cr.holdoutTurns()) / totalTurns;
    }

    /** 命中组成本相对该簇对照组的差。对照组为空 = 不可测，返回 null（不填 0）。 */
    private static Double tokenDelta(RulesReport.ClusterRow cr) {
        if (cr.holdoutTurns() == 0 || cr.controlAvgToken() <= 0 || cr.turns() <= cr.holdoutTurns()) {
            return null;
        }
        return (cr.hitAvgToken() - cr.controlAvgToken()) / cr.controlAvgToken();
    }

    private static TemplateStatus status(CardTemplate t, int produced, String reason) {
        return new TemplateStatus(t.name(), t.label(), produced > 0, produced, produced > 0 ? "" : reason);
    }

    private static TemplateStatus notTriggered(CardTemplate t, String reason) {
        return new TemplateStatus(t.name(), t.label(), false, 0, reason);
    }

    private static String pct(double v) {
        return "%.1f%%".formatted(v * 100);
    }

    private static String signedPct(double v) {
        return "%+.1f%%".formatted(v * 100);
    }

    private static String trim(double v) {
        return "%.1f".formatted(v);
    }

    /** 供测试与复用：模板全集（六个，固定）。 */
    public static Set<CardTemplate> templates() {
        return new LinkedHashSet<>(List.of(CardTemplate.values()));
    }

    /** 供测试：严重度 / 到期天数表（避免测试里重复硬编码）。 */
    public static int severityOf(CardTemplate t) {
        return SEVERITY.getOrDefault(t, 0);
    }

    /**
     * 「不再重复提议」的选项：作答过这两种的卡，其覆盖的簇退出后续候选（§4.10.4 的 reject 后果）。
     * 与 {@code t1Options} / {@code t5Options} 里那两个选项的 effect 文案逐字对应——文案说不再提议，代码就得做到。
     */
    public static final Set<String> SUPPRESSING_ANSWERS = Set.of("reject", "drop");

    /**
     * 「下个周期再报」的选项：作答过这些的卡可以重新出（刷新成新的卡面）。
     * 其余答案一律视为<b>人已表态</b>，同一张卡不再打扰——要恢复就让卡片回到未答状态。
     */
    public static final Set<String> REISSUABLE_ANSWERS = Set.of("defer", "hold", "collect", "unsure", "keep_condition");
}