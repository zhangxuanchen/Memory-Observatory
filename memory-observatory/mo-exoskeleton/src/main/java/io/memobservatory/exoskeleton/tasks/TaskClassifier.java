package io.memobservatory.exoskeleton.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.TaskClassifyReport;
import io.memobservatory.exoskeleton.tasks.TaskTypeRepository.SessionDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务类型分类编排（session 粒度）：把每个会话的用户提问交给 LLM 判一个封闭词表取值 + 一句自由备注。
 *
 * <p><b>为什么在 mo-exoskeleton 而不是 mo-server</b>：本模块刻意不依赖 mo-server 代码，而分类是
 * 「判据/报表侧」的事——与 {@code CriteriaService} 同一层。LLM 通道由 {@link LlmChatClient}
 * 零依赖自建。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li>不算就不写：LLM 不可达或解析失败 → 该 session 保持 NULL，<b>不猜一个 other 塞进去</b>。</li>
 *   <li>单条失败不影响整批：一个 session 抛异常只记日志并计入 failed，继续下一个。</li>
 *   <li>不加调度：手动触发（{@code POST /tasks/classify}），幂等可重跑（见 {@link TaskTypeRepository}）。</li>
 * </ol>
 */
@Service
public class TaskClassifier {

    private static final Logger log = LoggerFactory.getLogger(TaskClassifier.class);

    /** 抽检样本上限：够人眼扫一遍即可，不是全量回传。 */
    private static final int SAMPLE_LIMIT = 20;

    /** 均匀抽样时，每条 turn 至少要留这么多字符，否则宁可少取几条 turn。 */
    private static final int MIN_CHARS_PER_TURN = 200;

    private static final String SYSTEM_PREFIX = """
            你是会话任务分类器。给你同一个会话里的用户提问（按时间顺序，形如 [3/12] 表示第 3 条 / 共 12 条）。
            请判断这个会话整体在做什么任务：从下面的封闭词表里选一个，并写一句不超过 30 字的中文备注说明依据。

            判定方法：先给**每一条**提问归类（不要只看开头或结尾的几条），再数哪一类**条数最多**，
            取那一类作为整个会话的类型；只有前两名并列时，才用「主体产出物 / 最后交付的是什么」打破平局。

            两条硬性约束：
            - 不得因为出现了某个词就定类（出现「文档」≠ doc_write，出现「检查」≠ test_verify）。
            - 某类的条数占比不足 1/3 时，不得作为整个会话的类型。

            严格只输出一个 JSON 对象，不要任何说明文字、注释或 markdown 代码块标记，
            键为 task_type 与 note（note 为中文短句）。

            词表：
            """;

    private final LlmChatClient llm;
    private final TaskTypeRepository repo;
    private final LlmUsageMeter meter;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 整轮分类的运行标志：已有分类在跑时，重复进入直接如实跳过（不并发重复发 LLM 调用）。
     *
     * <p><b>前提：单实例部署</b>——本模块不挂 Redis / 不建 DB 锁，进程内原语即可。若将来多实例部署，
     * 这里须换成分布式锁，否则互斥只在单进程内成立。
     *
     * <p>用 {@code try/finally} 保证异常路径也释放；释放用 {@code set(false)} 而非再次 CAS，
     * 因为进入时已由 {@code compareAndSet} 独占。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${mo.exoskeleton.tasks.enabled:true}")
    private boolean enabled;
    @Value("${mo.exoskeleton.tasks.max-sessions-per-run:200}")
    private int maxSessions;
    @Value("${mo.exoskeleton.tasks.digest-max-chars:4000}")
    private int digestMaxChars;
    /** 评委数（奇数）：1 = 单次判定（judge_mode=single）；>1 = 多评委多数决（judge_mode=multi-N）。 */
    @Value("${mo.exoskeleton.tasks.judge-count:3}")
    private int judgeCount;

    public TaskClassifier(LlmChatClient llm, TaskTypeRepository repo, LlmUsageMeter meter) {
        this.llm = llm;
        this.repo = repo;
        this.meter = meter;
    }

    /**
     * 跑一轮：取待分类 session → 逐个判定 → 回写。幂等，可反复调用。
     *
     * @param force true = 无视已有分类全量重判并覆盖（判据/摘要策略变更后重标定用）；
     *              false = 只取未分类的 session
     */
    public TaskClassifyReport runOnce(boolean force) {
        if (!enabled) {
            return disabled("未启用：mo.exoskeleton.tasks.enabled=false。");
        }
        if (!llm.isEnabled()) {
            return disabled("未启用：LLM 通道未配置（mo.exoskeleton.tasks.llm.base-url / model 为空）。");
        }
        if (!running.compareAndSet(false, true)) {
            // 已有整轮分类在跑：如实跳过，不并发重复发 LLM 调用，也不伪装成「跑了但没结果」
            log.info("[task_type] 已有分类在运行中，本轮跳过");
            return new TaskClassifyReport(true, 0, 0, 0, 0L, List.of(), Map.of(), List.of(), List.of(),
                    "本轮分类已在运行中，跳过重复进入（不重复发 LLM 调用）。");
        }
        try {
            List<SessionDigest> pending = repo.pendingSessions(Math.max(1, maxSessions), force);
            if (force) {
                // 先清空候选集的旧标签：判失败的 session 回到 NULL 下轮重试，而不是静默留着旧值
                repo.clearTaskType(pending.stream().map(SessionDigest::sessionId).toList());
            }
            Map<String, Long> byTaskType = new LinkedHashMap<>();
            List<TaskClassifyReport.Sample> samples = new ArrayList<>();
            List<TaskClassifyReport.Failure> failures = new ArrayList<>();
            List<TaskTypeRepository.SessionTask> writes = new ArrayList<>(pending.size());
            Set<String> agents = new LinkedHashSet<>();   // 本轮成功分类涉及的 Agent（收尾按它逐个提炼）
            int failed = 0;

            for (SessionDigest d : pending) {
                try {
                    Verdict r = classify(d.turnTexts(), d.agentId());
                    if (r == null) {
                        failed++;   // 全评委都判不出 / 票并列：不猜，保持 NULL 下轮重试
                        failures.add(new TaskClassifyReport.Failure(
                                d.sessionId(), excerpt(d.turnTexts()), "模型输出不可解析或多数决无定论"));
                        log.warn("[task_type] 判定无定论，跳过 session={}", d.sessionId());
                        continue;
                    }
                    String value = r.type().value();
                    writes.add(new TaskTypeRepository.SessionTask(
                            d.sessionId(), value, r.note(), r.judgeMode(), r.judgeVotes()));
                    if (d.agentId() != null && !d.agentId().isBlank()) {
                        agents.add(d.agentId());
                    }
                    byTaskType.put(value, byTaskType.getOrDefault(value, 0L) + 1);
                    if (samples.size() < SAMPLE_LIMIT) {
                        samples.add(new TaskClassifyReport.Sample(d.sessionId(), value, r.note()));
                    }
                } catch (Exception e) {
                    failed++;   // 单条失败不影响整批
                    // 留痕「哪个 session 为什么失败」：只记计数答不出这一问；e 是重试耗尽后的最后一次异常
                    failures.add(new TaskClassifyReport.Failure(
                            d.sessionId(), excerpt(d.turnTexts()), abbrev(e.toString())));
                    log.warn("[task_type] 分类失败 session={} : {}", d.sessionId(), e.toString());
                }
            }

            int classified = writes.size();
            repo.writeBackAll(writes);
            // 回写之后再数「旧口径」：本轮判成功的已带上当前版本，剩下的才是真正的待重跑盘子
            long staleTurns = repo.countStaleVersion(TaskType.TAXONOMY_VERSION);
            log.info("[task_type] 分类完成 候选={} 成功={} 失败={} 旧口径={} model={}",
                    pending.size(), classified, failed, staleTurns, llm.model());

            return new TaskClassifyReport(true, pending.size(), classified, failed, staleTurns,
                    List.copyOf(agents), byTaskType, samples, List.copyOf(failures),
                    "task_type 由 LLM " + oddJudges() + " 评委多数决生成（judge-count 可配，取奇数；"
                            + "封闭 " + TaskType.values().length + " 值 + 自由备注；票并列即判失败不猜），session 粒度；"
                            + "回写同时落口径版本 " + TaskType.TAXONOMY_VERSION + " 与 classified_at；"
                            + "staleTurns = 仍是旧口径的 turn 数，改了 prompt / 枚举后据此定向 force 重跑。"
                            + "判定失败的 session 保持 NULL、下轮自动重试，失败原因见 failures。"
                            + "备注只供人眼抽检，不参与任何判定。"
                            + "import 路径为代理口径（其 user_text 是意图摘要而非原始提问，见登记口 B5.1）。");
        } finally {
            running.set(false);
        }
    }

    /** 首条提问摘要：压成一行、截断到 80 字，便于人认出是哪个会话。 */
    private static String excerpt(List<String> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        String s = turns.get(0).replaceAll("\\s+", " ").strip();
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /** 失败原因截断：压成一行，控制在 200 字以内。 */
    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }

    // ------------------------------------------------------------------
    // 单次判定
    // ------------------------------------------------------------------

    /** 单次判定的解析结果。note 允许为 null（模型没给）。 */
    private record TaskResult(TaskType type, String note) {
    }

    /**
     * 一整批判定（{@link #judgeCount} 名评委）的多数决结果。
     *
     * <p><b>为什么不是单次判定</b>：单次 LLM 判定的可靠性与掷硬币相当——SkillLens 实证准确率
     * 仅 <b>46.4%</b>，且绝对分跨 judge 有 ±8 噪音（见 darwin-skill 借来的证据）。故对同一份素材
     * **独立判 N 次**，取票最高的类型；**并列最高即判失败返回 null**（不猜，下轮重试），
     * 与「不算就不写」一致。{@code judgeMode} / {@code judgeVotes} 如实落库，让「一次判的」
     * 与「多数决判的」在数据上可区分。
     */
    private record Verdict(TaskType type, String note, String judgeMode, String judgeVotes) {
    }

    /**
     * 跑一轮多数决：独立判 {@link #judgeCount} 次 → 取票最高的类型。
     *
     * <p>返回 null 的两种情形：全体评委都输出不可解析（保持 NULL 下轮重试）；票并列最高
     * （如 1-1-1，无定论，同样不猜）。
     */
    private Verdict classify(List<String> turns, String agentId) throws Exception {
        int judges = oddJudges();
        String system = SYSTEM_PREFIX + TaskType.renderVocabulary() + TaskType.disambiguation();
        String question = "用户提问（按时间顺序）：\n" + digestOf(turns);
        Map<String, Long> votes = new LinkedHashMap<>();
        Map<String, String> noteByType = new LinkedHashMap<>();
        for (int i = 0; i < judges; i++) {
            // 记账维度：分类这一步还没有任务类型（它正是要判出来的），故 taskType 如实留空；能力名 = task_type
            String text = llm.chat(system, question,
                    (ok, usage) -> meter.record(agentId, "task_type", "", ok, usage));
            TaskResult r = parse(text);
            if (r == null) {
                continue;   // 该评委输出不可解析：不计票
            }
            String v = r.type().value();
            votes.merge(v, 1L, Long::sum);
            if (r.note() != null) {
                noteByType.putIfAbsent(v, r.note());   // 先到先得，只用中选类型的那条备注
            }
        }
        if (votes.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(votes.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (sorted.size() > 1 && sorted.get(0).getValue().equals(sorted.get(1).getValue())) {
            return null;   // 并列最高：无定论
        }
        String winner = sorted.get(0).getKey();
        return new Verdict(TaskType.fromOrOther(winner), noteByType.get(winner),
                judges == 1 ? "single" : "multi-" + judges, renderVotes(sorted));
    }

    /** 评委数取奇数：配置成偶数时上取一位（2→3、4→5），最小 1。 */
    private int oddJudges() {
        int n = Math.max(1, judgeCount);
        return (n > 1 && n % 2 == 0) ? n + 1 : n;
    }

    /** 票型渲染：{@code fix_bug=2,explain=1}（按票数降序）。 */
    private static String renderVotes(List<Map.Entry<String, Long>> sorted) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 从模型输出里抠出 JSON 再解析（抄 mo-server {@code UsageController.aiGuessMapping} 的形态：
     * 模型常会包一层解释或代码块标记，取首个 {@code &#123;} 到末个 {@code &#125;} 最稳）。
     * 解析失败返回 null，由调用方保持 NULL 下轮重试。
     */
    private TaskResult parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(text.substring(s, e + 1));
            TaskType type = TaskType.fromOrOther(n.path("task_type").asText(null));
            String note = n.path("note").asText(null);
            if (note != null) {
                note = note.strip();
                if (note.isEmpty()) {
                    note = null;
                }
            }
            return new TaskResult(type, note);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 会话摘要：<b>按 turn 均匀抽样</b>，代表性覆盖整段 session。
     *
     * <p>为什么不是「头 60% + 尾 40% 字符切」：本维度要求「按主体产出物 / 工作量占比定类」，
     * 而中段恰恰是主体工作量所在。曾把 795 轮的巨型 session 头尾各切一段，结果判成 test_verify，
     * 而其主体是从零做架构设计与 MVP 实现——正是中段被扔掉导致的误判。
     *
     * <p>抽样规则：整体能放下就整段给；放不下则——
     * <ul>
     *   <li>turn 数不多（每条都能分到 ≥ {@link #MIN_CHARS_PER_TURN} 字符）：每条都露面，各自配额变小；</li>
     *   <li>turn 数太多：按步长 <b>隔 k 条取一条</b>，首尾必取，每条配额固定，采样点均匀铺满全程。</li>
     * </ul>
     */
    private String digestOf(List<String> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        int n = turns.size();
        long total = 0;
        for (String t : turns) {
            total += t.length() + 1;
        }
        if (total <= digestMaxChars) {
            return render(turns, indexes(n, 1), digestMaxChars);
        }
        int maxTurns = Math.max(1, digestMaxChars / MIN_CHARS_PER_TURN);
        if (n <= maxTurns) {
            return render(turns, indexes(n, 1), Math.max(1, digestMaxChars / n));
        }
        int step = (int) Math.ceil((double) n / maxTurns);
        return render(turns, indexes(n, step), Math.max(1, digestMaxChars / maxTurns));
    }

    /** 取样的 turn 下标：从 0 起每隔 {@code step} 取一个，并保证最后一条必取（结尾常是结论）。 */
    private static List<Integer> indexes(int n, int step) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            idx.add(i);
        }
        if (idx.get(idx.size() - 1) != n - 1) {
            idx.add(n - 1);
        }
        return idx;
    }

    /** 渲染抽样结果：加 {@code [i/n]} 序号让模型看到时间跨度，单条超配额时头尾各留一段。 */
    private String render(List<String> turns, List<Integer> idx, int perTurn) {
        int n = turns.size();
        int cap = Math.max(1, perTurn - 12);   // 给序号前缀留位置
        StringBuilder sb = new StringBuilder(Math.min(digestMaxChars + 64, 8192));
        for (int i : idx) {
            String t = turns.get(i);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(i + 1).append('/').append(n).append("] ").append(clipOne(t, cap));
        }
        return sb.toString();
    }

    /** 单条 turn 超配额时保留头 70% + 尾 30%：一条提问的意图在开头，补充与结论在结尾。 */
    private static String clipOne(String s, int limit) {
        if (s.length() <= limit) {
            return s;
        }
        int head = (int) (limit * 0.7);
        int tail = limit - head;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    private static TaskClassifyReport disabled(String note) {
        return new TaskClassifyReport(false, 0, 0, 0, 0L, List.of(), Map.of(), List.of(), List.of(), note);
    }
}