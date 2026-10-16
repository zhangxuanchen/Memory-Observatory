package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillBoard;
import io.memobservatory.exoskeleton.model.SkillDiscoverAccepted;
import io.memobservatory.exoskeleton.model.SkillDiscoverCancel;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillDiscoverStatus;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import io.memobservatory.exoskeleton.tasks.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SkillDiscoverer} 拆出的「看板 / 心路展示格式化」协作类（纯静态、无配置）。
 * 负责：任务类型词表与标签、可提炼判定、软重复、以及提炼思考历程（日志硬统计 +
 * 代表原话 + 依据会话）的裁剪拼装。门面用 {@code import static} 引这些静态件。
 */
final class SkillBoardFormatter {

    private SkillBoardFormatter() {}

    /**
     * 任务类型封闭词表（value + 中文标签），供页面「人工纠正」下拉列出全部合法取值。
     *
     * <p><b>为什么不复用类清单当选项</b>：类清单是「这个 Agent 已有的类」（还随 Session 筛选收窄），
     * 而纠正恰恰可能要改成一个它<b>还没有</b>的类型——选项面必须是全词表，才谈得上「改成对的」。
     */
    static List<SkillBoard.TaskTypeOption> taskTypeOptions() {
        List<SkillBoard.TaskTypeOption> out = new ArrayList<>(TaskType.values().length);
        for (TaskType t : TaskType.values()) {
            out.add(new SkillBoard.TaskTypeOption(t.value(), t.label()));
        }
        return out;
    }

    /** 一类的展示标签；未分类单列（含 NULL），不用「其他」顶替。 */
    static String labelOf(String taskType) {
        if (taskType == null || "__unclassified__".equals(taskType)) {
            return "未分类";
        }
        return TaskType.fromOrOther(taskType).label();
    }

    /** 该类是否属于「可提炼」的类型：排除未分类与收容筐 other。 */
    static boolean extractable(String taskType) {
        if ("__unclassified__".equals(taskType)) {
            return false;
        }
        for (TaskType t : TaskType.values()) {
            if (t.value().equals(taskType)) {
                return t != TaskType.OTHER;
            }
        }
        return false;
    }

    /**
     * 软重复（疑似重复）：同 Agent 下 {@code skill_id} 不同、但内容指纹相同的一对提议。
     *
     * <p><b>为什么只标不合并</b>：两条内容相同却挂在不同 id 下的提议，可能是同一件事（该合并），
     * 也可能只是恰好结构相似（不该合并）——机器不该替人拍板。故两个方向各出一条，交给卡片标「疑似重复」。
     * 同 {@code skill_id} 的「硬重复」不在这里——那条已由 {@code store()} 自动去重。
     */
    static List<SkillBoard.Duplicate> duplicatesOf(List<SkillProposal> proposals) {
        List<SkillBoard.Duplicate> out = new ArrayList<>();
        for (int i = 0; i < proposals.size(); i++) {
            for (int j = i + 1; j < proposals.size(); j++) {
                SkillProposal a = proposals.get(i);
                SkillProposal b = proposals.get(j);
                if (a.skillId().equals(b.skillId())) {
                    continue;
                }
                if (!contentHash(a).equals(contentHash(b))) {
                    continue;
                }
                out.add(new SkillBoard.Duplicate(a.proposalId(), b.proposalId(), b.name(), b.taskLabel()));
                out.add(new SkillBoard.Duplicate(b.proposalId(), a.proposalId(), a.name(), a.taskLabel()));
            }
        }
        return out;
    }

    static String contentHash(SkillProposal p) {
        return EvidenceDigest.contentHash(p.name(), p.prompt(), p.tools());
    }

    // ------------------------------------------------------------------
    // 提炼思考历程：模型自述（四项）+ 确定性证据（依据的会话、代表原话）
    // ------------------------------------------------------------------

    /** 确定性证据条数上限：会话最多列这么多、代表原话最多抽这么多（超出截断，免得卡片过长）。 */
    static final int MAX_EVIDENCE_SESSIONS = 12;
    static final int MAX_EVIDENCE_SAMPLES = 3;
    /** 每条代表原话的展示上限（字）；超出留头留尾。 */
    static final int SAMPLE_MAX_CHARS = 120;

    /**
     * 拼一份完整的心路历程：日志总结（模型总结 + 系统硬统计）+ 模型自述四项 + 确定性证据两项。
     * 全都空即返回 null——没有可展示的内容时，卡片不显示「提炼思考历程」入口。
     */
    static SkillProposal.Reasoning mergeReasoning(SkillProposal.Reasoning llm,
                                                          List<String> sessions, List<String> samples,
                                                          List<String> logFacts) {
        SkillProposal.Reasoning r = new SkillProposal.Reasoning(
                llm == null ? List.of() : nullToEmpty(llm.patterns()),
                llm == null ? List.of() : nullToEmpty(llm.preferences()),
                llm == null ? List.of() : nullToEmpty(llm.keyPoints()),
                llm == null ? "" : (llm.evidence() == null ? "" : llm.evidence()),
                nullToEmpty(sessions), nullToEmpty(samples),
                llm == null ? "" : (llm.logSummary() == null ? "" : llm.logSummary()),
                nullToEmpty(logFacts));
        return r.isEmpty() ? null : r;
    }

    /**
     * 日志总结的系统口径：几个会话、多少条对话、时间跨度——可核对的硬数字。
     * 与模型的 {@code logSummary} 分列（A2：话不可尽信，数字可核对）。
     */
    static List<String> logFacts(int sessionCount, long turns, SkillProposalRepository.Span span) {
        List<String> out = new ArrayList<>();
        if (sessionCount > 0) {
            out.add(sessionCount + " 个会话");
        }
        if (turns > 0) {
            out.add(turns + " 条对话");
        }
        if (span != null && span.first() != null && span.last() != null) {
            out.add(monthDay(span.first()) + " → " + monthDay(span.last()));
        }
        return out;
    }

    /** 只留 {@code MM-DD}：近期日志里年份是噪声。 */
    static String monthDay(String isoDate) {
        return (isoDate != null && isoDate.length() >= 10) ? isoDate.substring(5) : isoDate;
    }

    /** 依据的会话：只取前 N 条（超出截断）。 */
    static List<String> cappedSessions(List<String> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return sessions.size() <= MAX_EVIDENCE_SESSIONS
                ? new ArrayList<>(sessions)
                : new ArrayList<>(sessions.subList(0, MAX_EVIDENCE_SESSIONS));
    }

    /** 从该类原文里均匀抽几条代表原话（首尾必取），每条留头留尾裁到展示上限。 */
    static List<String> sampleQuotes(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int n = texts.size();
        int k = Math.min(MAX_EVIDENCE_SAMPLES, n);
        List<String> out = new ArrayList<>(k);
        for (int j = 0; j < k; j++) {
            // 均匀取：k=1 取首条；否则在 [0, n-1] 上等距，末条落在 n-1（尾必取）。
            int idx = (k == 1) ? 0 : (int) Math.round((double) j * (n - 1) / (k - 1));
            String q = clip(texts.get(idx).strip(), SAMPLE_MAX_CHARS);
            if (!q.isEmpty() && !out.contains(q)) {
                out.add(q);
            }
        }
        return out;
    }

    /** 留头留尾裁到 limit 字（省略号占 1 字），与 {@link EvidenceDigest} 的裁法同精神。 */
    static String clip(String s, int limit) {
        if (s == null) {
            return "";
        }
        if (s.length() <= limit) {
            return s;
        }
        int head = (int) (limit * 0.7);
        int tail = Math.max(0, limit - head - 1);
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    static List<String> nullToEmpty(List<String> xs) {
        return xs == null ? List.of() : xs;
    }
}
