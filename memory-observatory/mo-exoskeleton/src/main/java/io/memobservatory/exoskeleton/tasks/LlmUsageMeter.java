package io.memobservatory.exoskeleton.tasks;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 用量计量：按「Agent × 能力 × 任务类型」记账调用次数与 token，供人面回答「谁在烧钱、烧在哪」。
 *
 * <p><b>为什么记账维度是「Agent × 能力 × 任务类型」</b>：本链路上只有两种能力——任务分类
 * （{@code task_type}，每次会话一条）与技能提炼（{@code skill}，每类一条）。两者调用频次与摘要长度
 * 差一个量级，混在一个总数里看不出成本结构；而 Agent 是「谁在用」这一问的答案。故一个 key
 * 就是这三格的组合，一个 key 一行。
 *
 * <p><b>只有内存态、不落库</b>：这是运行读数（进程重启归零），不是审计流水——按项目纪律，
 * 新增持久化要为「谁来读、读来干什么」负责，而成本计量的用途是即时观察与调参。若将来要留历史，
 * 再单独立表，不把这份读数拖下水。
 *
 * <p><b>失败也计一次调用</b>：HTTP 已经发出去了，即便超时也是真实消耗（模型侧照算）。
 * 但失败没有用量读数（拿不到 {@code usage}），故 {@code failed} 单列，token 只累加成功的，
 * 好让「调了几次」与「用了多少 token」各自可解释，不被彼此污染。
 */
@Component
public class LlmUsageMeter {

    /** 一行读数：一格「Agent × 能力 × 任务类型」。 */
    public record Row(String agentId, String capability, String taskType,
                      long calls, long failed,
                      long promptTokens, long completionTokens, long totalTokens) {
    }

    /** 一格的累加器：并发提炼时多线程自增，故用原子量。 */
    private static final class Counters {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong totalTokens = new AtomicLong();
    }

    /** key = agentId \u0000 capability \u0000 taskType（用 NUL 分隔，避免字段里出现分隔符撞键）。 */
    private final ConcurrentHashMap<String, Counters> byKey = new ConcurrentHashMap<>();
    /** 与 key 一一对应的三格语境（读行时取回来）。 */
    private final ConcurrentHashMap<String, String[]> keys = new ConcurrentHashMap<>();

    /**
     * 记一次调用。
     *
     * @param agentId    调用的 Agent（可为空 = 全局 / 未知归属）
     * @param capability 能力名（{@code task_type} 分类 / {@code skill} 提炼）
     * @param taskType   任务类型（分类时是待判的会话、尚无所属类，如实给空）
     * @param ok         true = 拿到了响应（未必成功解析）
     * @param usage      成功时的用量；未上报为 null（不累加，也不当 0）
     */
    public void record(String agentId, String capability, String taskType, boolean ok, LlmUsage usage) {
        String a = blankToEmpty(agentId);
        String c = blankToEmpty(capability);
        String t = blankToEmpty(taskType);
        String key = a + '\u0000' + c + '\u0000' + t;
        Counters cnt = byKey.computeIfAbsent(key, k -> new Counters());
        keys.putIfAbsent(key, new String[]{a, c, t});
        cnt.calls.incrementAndGet();
        if (!ok) {
            cnt.failed.incrementAndGet();
            return;
        }
        if (usage != null) {
            cnt.promptTokens.addAndGet(usage.promptTokens());
            cnt.completionTokens.addAndGet(usage.completionTokens());
            cnt.totalTokens.addAndGet(usage.totalTokens());
        }
    }

    /** 全部读数，按总 token 降序（烧得多的排前面）；同 token 按调用次数降序。 */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (var e : byKey.entrySet()) {
            String[] ctx = keys.get(e.getKey());
            Counters c = e.getValue();
            out.add(new Row(ctx[0], ctx[1], ctx[2],
                    c.calls.get(), c.failed.get(),
                    c.promptTokens.get(), c.completionTokens.get(), c.totalTokens.get()));
        }
        out.sort(Comparator.comparingLong(Row::totalTokens).reversed()
                .thenComparing(Comparator.comparingLong(Row::calls).reversed())
                .thenComparing(Row::agentId).thenComparing(Row::capability).thenComparing(Row::taskType));
        return out;
    }

    /** 清空全部读数（测试与「重新开始计量」用）。 */
    public void reset() {
        byKey.clear();
        keys.clear();
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.strip();
    }
}