package io.memobservatory.exoskeleton.promote;

import io.memobservatory.exoskeleton.criteria.CriteriaService;
import io.memobservatory.exoskeleton.model.GateVector;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.PromotionDecision;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 晋升编排（§4.11）：把规则喂进 {@link PromotionGate}，判决为放行时才落库。
 *
 * <p>分工刻意拧开：{@link PromotionGate} 是纯判决、不碰数据库；本类负责取数与落库。
 * 于是「闸门逻辑对不对」可以脱离数据库单独验，而「状态被谁改的」只有一个答案——就是这里。
 *
 * <p><b>本类是 {@code state=LIVE} 的唯一写入路径</b>（§4.11 纪律 1）。起草只写 SHADOW
 * （{@code RuleRepository#insertShadow}），此外没有任何地方改 state；
 * 且落库的 UPDATE 自带 {@code state='SHADOW'} 谓词，绕过本类直接写库也过不去。
 *
 * <p>取数现状（附录 B7）决定了本期的真实结论：库里**不存在两个评估周期**——
 * 7 条规则全停 SHADOW、从未注入，故没有「治理前 / 治理后」这一对向量；
 * 四项里又有三项结构性不可测。于是闸门必然拒绝，且理由可逐项追溯。
 * 这不是实现不到位，而是「缺证据不放行」应有的样子：闸门不因为没数据就放水。
 */
@Service
public class PromotionService {

    private final RuleRepository rules;
    private final CriteriaService criteria;
    private final PromotionGate gate;

    public PromotionService(RuleRepository rules, CriteriaService criteria, PromotionGate gate) {
        this.rules = rules;
        this.criteria = criteria;
        this.gate = gate;
    }

    /** 判一条规则能不能上线；判为放行则落库。 */
    public PromotionDecision promote(String ruleId) {
        Optional<MemoryRule> found = rules.latest(ruleId);
        if (found.isEmpty()) {
            return new PromotionDecision(ruleId, null, 0, null, false, List.of(),
                    List.of(), "库里没有这条规则，无可判决。");
        }
        MemoryRule rule = found.get();

        // 只降不升（§2.6 / A5）：带降级印记的规则，自动晋升通道对它关闭。
        // 必须在这里短路，而不是只靠落库 UPDATE 的 demoted_at IS NULL 谓词——
        // 谓词只能保证「写不进去」，若在此不拦，判决仍会报 passed=true，成为「放行了但没生效」的假放行。
        if (rule.demoted()) {
            return new PromotionDecision(
                    rule.ruleId(), rule.clusterKey(), rule.version(),
                    rule.state() == null ? null : rule.state().name(),
                    false,
                    List.of(new PromotionDecision.Layer(
                            "⓪ 只降不升（§2.6 / A5）",
                            PromotionDecision.Outcome.FAIL,
                            List.of("该规则曾被自动降级（demoted_at 有印记）：自动晋升通道关闭，回升须人工 clearDemotion 后再重走闸门。"))),
                    List.of(),
                    "拦截：这条规则带降级印记，自动晋升通道对它关闭（§2.6 / A5「只降不升」）。");
        }

        // 一个评估周期上的向量。当前只能组出唯一一份快照，故 before 与 after 同值——
        // 这正是「规则从未注入」的直接后果，也是本节要如实报告的事，不是取巧。
        GateVector snapshot = snapshotOf(rule.clusterKey());
        PromotionGate.Verdict verdict = gate.check(snapshot, snapshot, null, null);

        int rows = 0;
        if (verdict.passed()) {
            rows = rules.promote(rule.ruleId(), rule.version());
        }

        return new PromotionDecision(
                rule.ruleId(),
                rule.clusterKey(),
                rule.version(),
                rule.state() == null ? null : rule.state().name(),
                verdict.passed(),
                verdict.layers(),
                verdict.tripwires(),
                note(verdict, rows));
    }

    /**
     * 组一条规则所在簇的判据向量。取不到的一律留 null（= 不可测），不填 0。
     *
     * <p>只有两项真取得到：簇内隐式纠正率（且该簇必须有可比较的相邻对，否则也是「没有」，不是 0）
     * 与簇内平均单位成本。其余四项——速忘率、复读率、托底占比、churn——各有各的结构性障碍（附录 B7）。
     */
    private GateVector snapshotOf(String clusterKey) {
        Double correctionRate = null;
        Double avgToken = null;
        for (RulesReport.ClusterRow row : criteria.clusters().rows()) {
            if (row.key().equals(clusterKey)) {
                // transitions == 0 表示该簇里根本不存在可比较的相邻 turn 对，那是「没测到」，不是「纠正率为 0」。
                correctionRate = row.transitions() == 0 ? null : row.correctionRate();
                avgToken = row.turns() == 0 ? null : row.avgToken();
                break;
            }
        }
        return new GateVector(
                correctionRate,
                null,   // 写后速忘率：memory_events 是伪事件流，无记忆生命周期（B7.1）
                null,   // 复读率：同上
                null,   // 规则托底占比：无命中日志（B7.2）
                null,   // churn：无版本历史、version 写死 1（B7.2）
                avgToken,
                null);  // 失败率：S7 算在 mo-server 的 workflow 域，memory_turns 无此列
    }

    private static String note(PromotionGate.Verdict verdict, int rows) {
        StringBuilder sb = new StringBuilder();
        if (verdict.passed()) {
            sb.append("放行：三层全部通过，state 已由 SHADOW 置为 LIVE（影响 %d 行）。".formatted(rows));
        } else {
            // 「死在某一层」只属于 FAIL。不可测不是死在那一层——闸门其实继续往下走了。
            Optional<PromotionDecision.Layer> died = verdict.layers().stream()
                    .filter(l -> l.outcome() == PromotionDecision.Outcome.FAIL)
                    .findFirst();
            if (died.isPresent()) {
                sb.append("拦截：死在「%s」——三条退化解死在不同层，停在哪一层就是结论。".formatted(died.get().name()));
            } else {
                long unknown = verdict.layers().stream()
                        .filter(l -> l.outcome() == PromotionDecision.Outcome.UNKNOWN)
                        .count();
                sb.append("拦截：三层都走到了，但没有一层能拿满分（%d 层不可测）。".formatted(unknown))
                        .append("这不是「测到变坏了」，而是「根本没测到」——两者必须分开读。");
            }
        }
        sb.append(" 现状：规则全停 SHADOW、从未注入，库里不存在「治理前 / 治理后」两个评估周期，")
                .append("故本次 before 与 after 是同一份快照；四项中三项结构性不可测（附录 B7）。")
                .append("闸门不因缺数据放水——缺证据不予放行。");
        return sb.toString();
    }
}
