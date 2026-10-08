/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · report（旁路记忆观测）
 * 【文件】IoKind.java（io.memobservatory.agentloop.workbench.report）
 * 【核心功能】事件性质归一（B4 埋点补强）：把一个事件归一成语义封闭的 io_kind，
 *            随 metadata 写入，供 §4.4 分桶与判据使用。
 * 【核心改动】2026-10-06 新建。此前只能靠 operation 猜「这个 turn 有没有读 / 有没有写」，
 *            但 operation 回答不了——read_file 记成 WRITE、工具结果与模型调用也记 WRITE。
 * 【设计要点】词表封闭、纯增量：不动 operation / layer 现有语义（看板、流程分析不受影响）。
 *            跨语言同词表：examples/trae_importer.py 的 IO_KIND_* 与这里逐字一致，
 *            口径见《自校准闭环：设计与实现（L6 能学）》附录 B4。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.report;

import java.util.List;
import java.util.Set;

/**
 * io_kind：事件的**操作性质**归一（B4 埋点补强）。
 *
 * <p>为什么需要它：B4.1「区分不出读文件 vs 搜内容」与 B4.2「区分不出记忆写入 vs 工具调用」
 * 同一个根因——{@code operation} 是「记忆操作」轴（READ/WRITE/UPDATE/EXPIRE），
 * 而调用方拿它回答的是「这个 turn 干了什么活」。两把尺子量的是两件事，
 * 于是 {@code read_file} 落进 WRITE、模型调用与工具结果也落进 WRITE，「无 WRITE 收尾」恒假。
 *
 * <p>词表封闭为 8 值，读写两类各三项供 §4.4 判定「是否只读未写」：
 * <table>
 *   <tr><td>{@link #FILE_READ}</td><td>读文件（read_file / view / cat）</td></tr>
 *   <tr><td>{@link #CONTENT_SEARCH}</td><td>搜内容（search / grep / find / glob）</td></tr>
 *   <tr><td>{@link #MEMORY_READ}</td><td>读记忆（remember / retrieve / recall）</td></tr>
 *   <tr><td>{@link #FILE_WRITE}</td><td>改文件（edit / write_file / apply_patch）</td></tr>
 *   <tr><td>{@link #MEMORY_WRITE}</td><td>写记忆（store / memorize / save_memory）</td></tr>
 *   <tr><td>{@link #TOOL_OTHER}</td><td>其它工具（bash / run_command / task / 认不出的）</td></tr>
 *   <tr><td>{@link #MODEL_CALL}</td><td>模型调用与模型产出（思考 / 正文 / 数据块）</td></tr>
 *   <tr><td>{@link #LIFECYCLE}</td><td>会话 / 生命周期 / 控制 / 人机协同 / 提示 / 自定义</td></tr>
 * </table>
 *
 * <p>{@link #MODEL_CALL} 与 {@link #LIFECYCLE} 都不是工具调用——这正是 B4.2 要分开的那一半：
 * 只有 {@code io_kind ∈ 工具类} 才算「这个 turn 动了工具」。
 */
public final class IoKind {

    // ---- 读类 ----
    public static final String FILE_READ = "file_read";
    public static final String CONTENT_SEARCH = "content_search";
    public static final String MEMORY_READ = "memory_read";
    // ---- 写类 ----
    public static final String FILE_WRITE = "file_write";
    public static final String MEMORY_WRITE = "memory_write";
    // ---- 非读写 ----
    public static final String TOOL_OTHER = "tool_other";
    public static final String MODEL_CALL = "model_call";
    public static final String LIFECYCLE = "lifecycle";

    /** 读类三项。§4.4 的「是否有工具读」= 命中本集合。 */
    public static final Set<String> READ_KINDS = Set.of(FILE_READ, CONTENT_SEARCH, MEMORY_READ);
    /** 写类两项。§4.4 的「是否只读未写」= 有读类且两写类都没命中。 */
    public static final Set<String> WRITE_KINDS = Set.of(FILE_WRITE, MEMORY_WRITE);
    /** 工具类六项（读三 + 写二 + 其它）。用它判「这个 turn 有没有调用工具」。 */
    public static final Set<String> TOOL_KINDS = Set.of(
            FILE_READ, CONTENT_SEARCH, MEMORY_READ, FILE_WRITE, MEMORY_WRITE, TOOL_OTHER);

    /** 封闭词表（顺序即文档顺序，便于人读与回归）。 */
    public static final List<String> VOCABULARY = List.of(
            FILE_READ, CONTENT_SEARCH, MEMORY_READ, FILE_WRITE, MEMORY_WRITE,
            TOOL_OTHER, MODEL_CALL, LIFECYCLE);

    private IoKind() {
    }

    /**
     * 工具名 → io_kind。判定顺序即优先级，不能随意调换：
     * <ol>
     *   <li>记忆写入先于记忆读取——{@code update_memory} 同时含「memory」与写类词，先判读会判错；</li>
     *   <li>记忆类先于文件类——{@code search_memory} 含 {@code search}，不能落进「搜内容」；</li>
     *   <li>文件读先于文件写、文件写先于内容检索——避免 {@code write} / {@code search} 提前吞掉更具体的名字。</li>
     * </ol>
     * 认不出一律 {@link #TOOL_OTHER}，不猜（与「宁可漏报不可误报」同一条纪律）。
     */
    public static String ofTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return TOOL_OTHER;
        }
        String t = toolName.toLowerCase();

        // 1. 记忆写入
        if (containsAny(t, "save_memory", "update_memory", "write_memory", "memory_write",
                "memorize", "store", "consolidate", "forget")) {
            return MEMORY_WRITE;
        }
        // 2. 记忆读取
        if (containsAny(t, "search_memory", "memory_search", "read_memory", "memory_read",
                "remember", "retrieve", "recall")) {
            return MEMORY_READ;
        }
        // 3. 读文件
        if (containsAny(t, "read_file", "view_file", "open_file", "cat_file", "readfile")
                || t.endsWith(".read")) {
            return FILE_READ;
        }
        // 4. 改文件
        if (containsAny(t, "write_file", "edit_file", "create_file", "apply_patch",
                "str_replace", "edit", "write")) {
            return FILE_WRITE;
        }
        // 5. 搜内容
        if (containsAny(t, "search", "grep", "find", "glob", "ripgrep", "fetch")) {
            return CONTENT_SEARCH;
        }
        return TOOL_OTHER;
    }

    /**
     * 非工具事件 → io_kind。只有模型层算 {@link #MODEL_CALL}，其余（会话 / 生命周期 /
     * 控制 / 人机协同 / 提示 / 自定义）一律 {@link #LIFECYCLE}。
     */
    public static String ofLayer(String layer) {
        if (layer == null) {
            return LIFECYCLE;
        }
        String l = layer.toLowerCase();
        // text / data 是模型产出的流式块，与 model 同性质
        return ("model".equals(l) || "text".equals(l) || "data".equals(l)) ? MODEL_CALL : LIFECYCLE;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
