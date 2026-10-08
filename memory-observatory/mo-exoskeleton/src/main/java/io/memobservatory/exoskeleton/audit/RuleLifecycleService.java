package io.memobservatory.exoskeleton.audit;

import io.memobservatory.exoskeleton.criteria.CriteriaService;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.RuleAuditReport;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import io.memobservatory.exoskeleton.storage.RuleRepository.EvalRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则的生命周期管理：审计（§2.6 / §2.7）与人工操作（修订 / 停用 / 批准回升）。
 *
 * <p>审计做三件事：
 * <ol>
 *   <li><b>取数</b>：复用 {@code CriteriaService.clusters()}（簇级判据）——命中面、命中组/对照组成本、
 *       该簇「准」，不另起一套口径。判据只该有一个真相来源（与 promote 的 snapshotOf 同一原则）；</li>
 *   <li><b>判决</b>：交给 {@link RuleAuditJudge}（纯函数，可单测）；</li>
 *   <li><b>落库</b>：对 LIVE 的规则执行降级 / 硬化 / 撤回，并为<b>每条规则</b>写一行评估快照。</li>
 * </ol>
 *
 * <p>为什么只对 LIVE 处置：影子规则不注入，没有爆炸半径可言。影子与 RETIRED 的规则照样落读数快照
 * （否则「这条规则被降级后有没有继续变差」就断了），但动作恒为 {@code NONE}。
 *
 * <p>与晋升闸门（§4.11）的分工：闸门是上线唯一入口，本服务是<b>下线的自动路径</b>。
 * 两者都不越界——审计只做「降」，回升仍走闸门（且须先 {@link #clearDemotion} 清掉降级印记）。
 */
@Service
public class RuleLifecycleService {

    private final RuleRepository rules;
    private final CriteriaService criteria;
    private final RuleAuditJudge judge;

    public RuleLifecycleService(RuleRepository rules, CriteriaService criteria, RuleAuditJudge judge) {
        this.rules = rules;
        this.criteria = criteria;
        this.judge = judge;
    }

    // ==================================================================
    // 审计（§2.6 三道限制 + §2.7 churn 撤回）
    // ==================================================================

    public RuleAuditReport audit() {
        RulesReport.ClusterCriteria clusters = criteria.clusters();
        Map<String, RulesReport.ClusterRow> byKey = new LinkedHashMap<>();
        long totalTurns = 0;
        for (RulesReport.ClusterRow row : clusters.rows()) {
            byKey.put(row.key(), row);
            totalTurns += row.turns();
        }

        List<RuleAuditReport.Row> rows = new ArrayList<>();
        int demoted = 0;
        int retired = 0;
        int hardened = 0;

        for (RulesReport.RuleView view : rules.rules()) {
            MemoryRule r = view.rule();
            RulesReport.ClusterRow cr = byKey.get(r.clusterKey());

            Double hitShare = hitShare(cr, totalTurns);
            Double tokenDelta = tokenDelta(cr);
            Double failDelta = null;   // §2.6 限制二的失败率：本库结构性不可测（登记口 B7），不假装有值
            Double correctionRate = cr == null || cr.transitions() == 0 ? null : cr.correctionRate();

            // 硬化判定要回看历史：把本次读数接在最近两次评估前面（新的在前）。
            List<Double> history = new ArrayList<>();
            history.add(correctionRate);
            for (EvalRow e : rules.recentEvals(r.ruleId(), r.version(), 2)) {
                history.add(e.correctionRate());
            }

            RuleAuditJudge.Decision decision = judge.judge(
                    hitShare, tokenDelta, failDelta, view.churn(), history);

            boolean live = r.state() == MemoryRule.State.LIVE;
            String action = live ? decision.action().name() : RuleAuditJudge.Action.NONE.name();
            String reason = live
                    ? decision.reason()
                    : "规则未处于 LIVE（当前 " + (r.state() == null ? "未知" : r.state().name())
                            + "）：影子不注入、无爆炸半径，本次不处置。读数照记，供后续比较。";

            int affected = apply(r, action);
            if (affected > 0) {
                switch (action) {
                    case "DEMOTE_HIT", "DEMOTE_COST" -> demoted++;
                    case "RETIRE_CHURN" -> retired++;
                    case "HARDEN" -> hardened++;
                    default -> { }
                }
            }

            rules.insertEval(new EvalRow(r.ruleId(), r.version(),
                    hitShare, tokenDelta, failDelta, correctionRate, action));

            rows.add(new RuleAuditReport.Row(r.ruleId(), r.clusterKey(), r.version(),
                    r.state() == null ? null : r.state().name(),
                    hitShare, tokenDelta, failDelta, correctionRate, view.churn(),
                    action, affected, reason));
        }

        String note = "审计只降不升：越界的 LIVE 规则退回影子并留下降级印记，之后不能再走自动晋升"
                + "（回升须人工 clearDemotion）；churn 过高按 §2.7 直接撤回；硬化只提示不停用。"
                + "可空字段为 null = 该项不可测，不是 0。";
        return new RuleAuditReport(rows.size(), demoted, retired, hardened, rows, note);
    }

    /** 命中面 = 该簇非 holdout turn / 全部 turn（§2.6 限制一）。分不清命中组时返回 null。 */
    private static Double hitShare(RulesReport.ClusterRow cr, long totalTurns) {
        if (cr == null || totalTurns == 0) {
            return null;
        }
        return (double) (cr.turns() - cr.holdoutTurns()) / totalTurns;
    }

    /** 命中组成本相对该簇对照组的差（§2.6 限制二）。对照组为空 = 不可测，返回 null。 */
    private static Double tokenDelta(RulesReport.ClusterRow cr) {
        if (cr == null || cr.holdoutTurns() == 0 || cr.controlAvgToken() <= 0 || cr.turns() <= cr.holdoutTurns()) {
            return null;
        }
        return (cr.hitAvgToken() - cr.controlAvgToken()) / cr.controlAvgToken();
    }

    /** 按动作落库，返回影响行数。0 且动作为处置 = 「判了没落下」，由报告如实呈现。 */
    private int apply(MemoryRule r, String action) {
        return switch (action) {
            case "DEMOTE_HIT", "DEMOTE_COST" -> rules.demote(r.ruleId(), r.version(), action);
            case "RETIRE_CHURN" -> rules.retire(r.ruleId(), r.version());
            case "HARDEN" -> rules.harden(r.ruleId(), r.version());
            default -> 0;
        };
    }

    // ==================================================================
    // 人工操作（§2.7 修订 / §5.2 停用 / §2.6 批准回升）
    // ==================================================================

    /**
     * 内容变更 → 新版本（§2.7）。事务内完成「停旧版 + 建新版」，中途失败不留空洞。
     *
     * @return 新版本号；-1 表示规则不存在（如实报告，不静默新建）
     */
    @Transactional
    public int revise(String ruleId, String body) {
        return rules.revise(ruleId, body);
    }

    /** 人工停用（§5.2「可手动停用」）。 */
    @Transactional
    public int retire(String ruleId) {
        return rules.retire(ruleId, rules.latestVersion(ruleId));
    }

    /** 人工批准回升：清降级印记，使规则重新可被闸门审视（§2.6「回升必须人工批准」）。 */
    @Transactional
    public int clearDemotion(String ruleId) {
        return rules.clearDemotion(ruleId, rules.latestVersion(ruleId));
    }
}
