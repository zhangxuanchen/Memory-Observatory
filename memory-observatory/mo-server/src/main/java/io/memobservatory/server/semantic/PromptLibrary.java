package io.memobservatory.server.semantic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三套 laya 问题定义（questions 契约）。
 *
 * <p><b>契约要点</b>：
 * <ul>
 *   <li>{@code instructions} 里用反引号引用的字段名，必须与送入的 state 键一一对应</li>
 *   <li>{@code choice} 的选项数务必 ≤ 20（超了会因 head_max_len 预算被摊薄而崩，
 *       Banking77 在 77 选项上只有 0.425）</li>
 *   <li>判定一律用 {@code noul}，<b>不要用 {@code score}</b>——score 是 laya 最弱的原语（SST-5 0.372）</li>
 * </ul>
 *
 * <p><b>三个 noul 问题都刻意问成「是真的吗」</b>（P(真)），调用处统一 {@code 1 - noul()}
 * 得到 P(假阳性)。这样问题措辞更自然，但取反不能漏——漏了会把真问题当假阳性全删。
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    /** 内容风险：这条命中是真凭据泄漏，还是示例 / 占位符？ */
    public static Map<String, Object> riskQuestions() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("is_real_credential", noul(
                "Does `snippet` (matched by rule `rule_name` in field `field`) contain a REAL "
              + "credential that would grant access if used — a live secret, a real password, "
              + "a working token from an actual configuration or request — rather than an "
              + "example, a placeholder such as your-key-here, a variable reference, a "
              + "documentation sample, or a test fixture with no real value?"));
        q.put("leak_context", choice(
                "Where does this snippet come from?",
                Map.of(
                        "live_usage", "actual configuration or a real request/response",
                        "user_paste", "user pasted their own secret into the conversation",
                        "doc_example", "documentation, tutorial, comment or chat message",
                        "code_fixture", "test data or placeholder inside source code",
                        "unknown", "cannot tell from the snippet alone")));
        return q;
    }

    /** 问题分析：这次超阈值是真问题，还是任务本身就需要这么多资源？ */
    public static Map<String, Object> problemQuestions() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("is_genuine", noul(
                "A monitoring rule flagged `hit_type` (observed `observed`, threshold "
              + "`threshold`) on node `node`. Considering the user request in `turn_user` and "
              + "what the agent actually did in `summary`: is this a genuine problem — waste, "
              + "a pathological loop, or a malfunction — rather than the legitimate cost of a "
              + "genuinely complex task?"));
        q.put("issue_kind", choice(
                "If it is a real problem, which kind is it?",
                Map.of(
                        "pathological_loop", "stuck repeating the same action without progress",
                        "resource_waste", "did far more work than the task needed",
                        "tool_malfunction", "external tool or service failed repeatedly",
                        "memory_pressure", "ran out of context or thrashed memory",
                        "legitimate_cost", "the task genuinely required this")));
        return q;
    }

    /** 流程分析：这次重复是病态空转，还是正常的重试 / 增量推进？ */
    public static Map<String, Object> loopQuestions() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("is_genuine", noul(
                "The agent called node `node` repeatedly, `repeat_count` times in this trace. "
              + "Reading what each call actually did in `summary`: is it stuck in a "
              + "pathological loop that makes no progress, or is each call a legitimate retry "
              + "or an incremental step toward the goal?"));
        return q;
    }

    // ==================== 构造辅助 ====================

    private static Map<String, Object> noul(String instructions) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "noul");
        def.put("instructions", instructions);
        return def;
    }

    private static Map<String, Object> choice(String instructions, Map<String, String> criteria) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "choice");
        def.put("instructions", instructions);
        def.put("criteria", criteria);
        return def;
    }

    /** 供 UI / 调试查看某类场景的问题定义。 */
    public static Map<String, Object> of(ConfirmKind kind) {
        return switch (kind) {
            case RISK -> riskQuestions();
            case PROBLEM -> problemQuestions();
            case LOOP -> loopQuestions();
        };
    }

    /** 该场景的主判定问题 id（取 noul 值时用）。三个场景都刻意问成「是真的吗」。 */
    public static String primaryQid(ConfirmKind kind) {
        return switch (kind) {
            case RISK -> "is_real_credential";
            case PROBLEM, LOOP -> "is_genuine";
        };
    }

    /**
     * 该场景的分类问题 id（取 choice 标签时用）。
     *
     * <p>与 {@link #primaryQid} 是<b>同一次 predict 里的两个问题</b>，不额外增加推理开销：
     * 前者答「是不是真问题」，后者答「是哪一类问题」。
     *
     * <p><b>为什么 RISK 返回 null</b>：实测 {@code leak_context} 3 例全错，其中
     * 「文档占位符」被判成 {@code live_usage}（方向性错误，比无标签更危险），
     * 「测试夹具」以 0.779 的高置信判错——<b>置信度门控救不了，因为错的恰恰是高置信那个</b>。
     * 而 RISK 场景漏报不可逆，宁可不出标签。{@code riskQuestions()} 里仍保留该问题定义
     * 作为记录，但<b>不要</b>在未做人工标注标定前重新消费它。
     *
     * <p>{@code issue_kind} 则可用：在通过 noul 过滤保留下来的命中里 3/3 正确
     * （病态空转 / 工具故障 / 资源浪费各归其类）。
     */
    public static String classifyQid(ConfirmKind kind) {
        return switch (kind) {
            case PROBLEM -> "issue_kind";
            case RISK, LOOP -> null;
        };
    }

    /** 全部可用于回归测试的场景。 */
    public static List<ConfirmKind> allKinds() {
        return List.of(ConfirmKind.RISK, ConfirmKind.PROBLEM, ConfirmKind.LOOP);
    }
}
