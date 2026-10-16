package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.criteria.CorrectionDetector;
import io.memobservatory.exoskeleton.model.SkillEffectReport;
import io.memobservatory.exoskeleton.model.SkillEffectReport.Bucket;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository.ContrastTurn;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能效果验证的编排：取数 → 分桶 → 交闸门判决。
 *
 * <p>只做两件事，判决本身在 {@link SkillEffectGate}（纯函数）：本类负责「取哪些 turn、怎么分组」，
 * 那是数据口径；闸门负责「两组读数意味着什么」。
 *
 * <p><b>分桶规则</b>：以采纳时刻 {@code decidedAt} 为界，一个<b>会话</b>按它首 turn 的时刻归到
 * 「界前」或「界后」，该会话的 turn 全部跟着走。按会话而不是按单个 turn 归组，是为了让
 * 「回合数」这项在两组里都成立——一个会话被从中间劈成两半，两边都算不出「这个会话用了几回合」。
 *
 * <p><b>只采信 workbench 路径</b>：与判据线（{@code CriteriaService}）同一纪律——导入路径的
 * {@code user_text} 是 intent 摘要、{@code corrected} 也非真值（登记口 B5），故两组都只统计
 * {@code source = workbench} 的 turn。样本因此更少，但数是真的。
 */
@Service
public class SkillEffectService {

    /** 可采信来源路径（与 {@code CriteriaService.TRUSTED_SOURCE} 同值，刻意各自声明以便独立演进）。 */
    private static final String TRUSTED_SOURCE = "workbench";

    private final SkillProposalRepository repo;
    private final SkillEffectGate gate;

    public SkillEffectService(SkillProposalRepository repo, SkillEffectGate gate) {
        this.repo = repo;
        this.gate = gate;
    }

    /** 生成一条提议的效果验证报告；提议不存在返回 null（端点据此回 404）。 */
    public SkillEffectReport report(String proposalId) {
        SkillProposal p = repo.find(proposalId);
        if (p == null) {
            return null;
        }
        if (p.decidedAt() == null) {
            return undecided(p, "尚未采纳（没有采纳时刻），无从分「前 / 后」两组——先采纳再回来看效果。");
        }

        List<ContrastTurn> turns = repo.classContrastTurns(p.agentId(), p.taskType());
        Map<String, List<ContrastTurn>> bySession = groupBySession(turns);

        Bucket before = bucketOf(bySession, p.decidedAt(), true);
        Bucket after = bucketOf(bySession, p.decidedAt(), false);

        SkillEffectGate.Verdict v = gate.check(before, after);
        return new SkillEffectReport(p.proposalId(), p.agentId(), p.taskType(), p.taskLabel(),
                p.status(), p.decidedAt(), before, after, v.items(), v.verdict(), v.tripwires(),
                note());
    }

    // ------------------------------------------------------------------
    // 分桶
    // ------------------------------------------------------------------

    /** 按会话归拢，保留 SQL 的会话 / 时间顺序（LinkedHashMap 固定组序，便于读数稳定）。 */
    private static Map<String, List<ContrastTurn>> groupBySession(List<ContrastTurn> turns) {
        Map<String, List<ContrastTurn>> out = new LinkedHashMap<>();
        for (ContrastTurn t : turns) {
            out.computeIfAbsent(t.sessionId(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    /**
     * 某一组的读数。{@code isBefore=true} 取「首 turn 早于界」的会话，否则取不早于界的。
     *
     * <p>会话首 turn 无 {@code startedAt}（或不是可采信来源）时整段剔除：归不了组就不参与判决，
     * 而不是硬塞进某一组污染读数。
     */
    private Bucket bucketOf(Map<String, List<ContrastTurn>> bySession, Instant boundary, boolean isBefore) {
        long sessions = 0;
        long turns = 0;
        long corrected = 0;
        long failures = 0;

        for (List<ContrastTurn> s : bySession.values()) {
            Instant first = firstTrustedStart(s);
            if (first == null) {
                continue;   // 整个会话没有一个可采信、有时刻的 turn
            }
            boolean before = first.isBefore(boundary);
            if (before != isBefore) {
                continue;
            }
            sessions++;
            for (ContrastTurn t : s) {
                if (!TRUSTED_SOURCE.equals(t.source())) {
                    continue;   // 只采信 workbench（B5）
                }
                turns++;
                if (t.corrected()) {
                    corrected++;
                }
                if (CorrectionDetector.hasFailureSign(t.outcome())) {
                    failures++;
                }
            }
        }

        boolean measurable = turns >= gate.minSample();
        return new Bucket(sessions, turns,
                turns == 0 ? 0.0 : (double) corrected / turns,
                turns == 0 ? 0.0 : (double) failures / turns,
                sessions == 0 ? 0.0 : (double) turns / sessions,
                measurable);
    }

    /** 会话首 turn 的可采信时刻（取该会话中最早的可采信、带时刻的 turn）。 */
    private static Instant firstTrustedStart(List<ContrastTurn> session) {
        Instant min = null;
        for (ContrastTurn t : session) {
            if (!TRUSTED_SOURCE.equals(t.source()) || t.startedAt() == null) {
                continue;
            }
            if (min == null || t.startedAt().isBefore(min)) {
                min = t.startedAt();
            }
        }
        return min;
    }

    // ------------------------------------------------------------------
    // 说明与未决分支
    // ------------------------------------------------------------------

    private static String note() {
        return "埋点口径：「技能被用上」无逐 turn 观测（技能以提示片段 + 内置工具装配进 Agent，"
                + "调用与否不在遥测里），故以采纳时刻为界、把同 Agent 同任务类型的会话按首 turn 分成"
                + "「界前 / 界后」两组对照——是暴露组的上界近似（可能高估暴露，登记口 B17）。"
                + "只采信 workbench 路径；三项判据都是「越低越好」，判「不劣化即通过」。"
                + "本报告只出结论，撤回采纳由人拍板。";
    }

    private SkillEffectReport undecided(SkillProposal p, String note) {
        Bucket empty = new Bucket(0, 0, 0.0, 0.0, 0.0, false);
        return new SkillEffectReport(p.proposalId(), p.agentId(), p.taskType(), p.taskLabel(),
                p.status(), null, empty, empty, List.of(), SkillEffectReport.UNKNOWN, List.of(), note);
    }
}