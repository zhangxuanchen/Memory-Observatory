package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【工作台/core/ConversationMemory】会话记忆核心行为回归。
 *
 * <p>此前 1216 行的记忆本体零测试（架构审视弱点 1 的剩余部分）。用<b>真实</b>
 * {@link JsonFileAgentStateStore}（@TempDir 落盘）而非替身——「重启可恢复」「伴生文件」
 * 这些行为本来就长在文件布局上，mock 会把它测成假的。
 *
 * <p>覆盖：会话隔离键、三层上下文（早期要旨/最近原文）、L1 超长截断、
 * L2 折叠（KEEP_RECENT=5）与归档找回、伴生文件计量（轮次/工具字符）、clear、
 * 压缩标记消费、五区预算 ContextBudget 的计量与判定阈值。
 * 模型重摘要（L3/L4，依赖 ContextSummarizer）不在本套——代码明确 summarizer=null
 * 时走纯规则兜底（L533 边界1），测试全部走这条零成本路径。
 */
class ConversationMemoryTest {

    @TempDir
    Path tmp;

    private ConversationMemory mem;

    /** 台账面 key：{workspace}/{agent}/{session}。 */
    private static String k(String session) {
        return ConversationMemory.key("ws1", "agentA", session);
    }

    @BeforeEach
    void setUp() {
        mem = new ConversationMemory(new JsonFileAgentStateStore(tmp.resolve("context")));
    }

    /** 往会话里灌 n 轮短对话，第 i 轮用户文本带轮号便于断言。 */
    private void rounds(String key, int n) {
        for (int i = 1; i <= n; i++) {
            mem.append(key, "问题" + i, "回答" + i, null);
        }
    }

    // ------------------------------------------------------------------
    // 基础面：键、空态、单轮
    // ------------------------------------------------------------------

    @Test
    void 键格式与会话隔离() {
        assertThat(k("s1")).isEqualTo("ws1/agentA/s1");
        rounds(k("s1"), 1);
        // 别的会话不受写入影响：无历史时 context 为 null、快照 exists=false
        assertThat(mem.context(k("s2"))).isNull();
        assertThat(mem.snapshot(k("s2")).exists()).isFalse();
    }

    @Test
    void 单轮写入后上下文与快照可见() {
        String key = k("s1");
        assertThat(mem.context(key)).isNull();  // 写前空
        rounds(key, 1);
        String ctx = mem.context(key);
        assertThat(ctx).contains("【最近原文】").contains("用户：问题1").contains("助手：回答1");
        ConversationMemory.MemorySnapshot snap = mem.snapshot(key);
        assertThat(snap.exists()).isTrue();
        assertThat(snap.folded()).isFalse();       // 5 轮内不该发生折叠
        assertThat(snap.recentTurns()).isEqualTo(1);
        assertThat(snap.totalTurns()).isEqualTo(1);
        assertThat(snap.recent()).singleElement()
                .extracting(ConversationMemory.RecentTurn::user, ConversationMemory.RecentTurn::assistant)
                .containsExactly("问题1", "回答1");
    }

    @Test
    void L1单条超长写入即保头尾截断() {
        String longUser = "甲".repeat(13_000);
        mem.append(k("s1"), longUser, "短回答", null);
        String stored = mem.snapshot(k("s1")).recent().get(0).user();
        assertThat(stored).contains("…[内容超长已截断]…")
                .hasSize(6_000 + "…[内容超长已截断]…".length() + 2_000 + 2)
                .startsWith("甲").endsWith("甲");
    }

    // ------------------------------------------------------------------
    // L2 折叠与归档找回
    // ------------------------------------------------------------------

    @Test
    void 超保留轮数触发折叠且最早轮离开原文() {
        String key = k("s1");
        rounds(key, ConversationMemory.KEEP_RECENT + 1);  // 6 轮，折掉第 1 轮
        ConversationMemory.MemorySnapshot snap = mem.snapshot(key);
        assertThat(snap.folded()).isTrue();
        assertThat(snap.recentTurns()).isEqualTo(ConversationMemory.KEEP_RECENT);
        assertThat(snap.totalTurns()).isEqualTo(6);       // 累计轮次不因折叠减少
        String ctx = mem.context(key);
        assertThat(ctx).doesNotContain("用户：问题1").contains("用户：问题6");
        // 折叠足迹：时间 + 被折轮用户问题摘句
        List<Map<String, Object>> trail = mem.foldTrail(key);
        assertThat(trail).isNotEmpty();
        assertThat((String) trail.get(0).get("snippet")).contains("问题1");
    }

    @Test
    void 摘要内嵌归档标记且可按id找回全文() {
        String key = k("s1");
        rounds(key, 6);
        String summary = mem.snapshot(key).summary();
        assertThat(summary).contains("【记忆归档 #");
        Matcher m = Pattern.compile("【记忆归档 #(\\d+)】").matcher(summary);
        assertThat(m.find()).isTrue();
        String found = mem.findArchive(key, m.group(1));
        assertThat(found).startsWith("已找回归档").contains("问题1").contains("回答1");
    }

    @Test
    void 归档查询的负面路径明确报错() {
        assertThat(mem.findArchive(k("s1"), "")).startsWith("ERROR: 缺少归档 id");
        assertThat(mem.findArchive(k("s1"), "999999999")).startsWith("ERROR: 未找到归档");
    }

    // ------------------------------------------------------------------
    // 伴生文件计量与持久性
    // ------------------------------------------------------------------

    @Test
    void 工具字符跨调用累积且按会话隔离() {
        String key = k("s1");
        mem.recordTool(key, 100);
        mem.recordTool(key, 50);
        assertThat(mem.toolChars(key)).isEqualTo(150);
        mem.recordTool(k("s2"), 7);
        assertThat(mem.toolChars(key)).isEqualTo(150);   // 互不污染
        mem.recordTool(key, 0);                          // 非正数不加
        assertThat(mem.toolChars(key)).isEqualTo(150);
    }

    @Test
    void 重启后同store同会话记忆与轮次可恢复() {
        String key = k("s1");
        rounds(key, 2);
        mem.recordTool(key, 9);
        // 新实例 = 服务重启：同一落盘根，记忆、累计轮次、工具计量都还在
        ConversationMemory rebooted =
                new ConversationMemory(new JsonFileAgentStateStore(tmp.resolve("context")));
        assertThat(rebooted.context(key)).contains("用户：问题2");
        assertThat(rebooted.snapshot(key).totalTurns()).isEqualTo(2);
        assertThat(rebooted.toolChars(key)).isEqualTo(9);
    }

    @Test
    void 清空会话回到空态() {
        String key = k("s1");
        rounds(key, 3);
        mem.clear(key);
        assertThat(mem.context(key)).isNull();
        ConversationMemory.MemorySnapshot snap = mem.snapshot(key);
        assertThat(snap.exists()).isFalse();
        assertThat(snap.totalTurns()).isZero();          // turns.json 伴生文件一并删除
    }

    // ------------------------------------------------------------------
    // 压缩标记与自收敛入口
    // ------------------------------------------------------------------

    @Test
    void 压缩标记的置位查询消费语义() {
        String key = k("s1");
        assertThat(mem.consumeCompress(key)).isFalse();
        mem.markCompress(key);
        assertThat(mem.isCompressMarked(key)).isTrue();
        assertThat(mem.consumeCompress(key)).isTrue();   // 消费即清
        assertThat(mem.isCompressMarked(key)).isFalse();
    }

    @Test
    void 压缩自收敛大目标即达标且不抛异常() {
        String key = k("s1");
        rounds(key, 6);
        // 目标远大于现体积 → 无活可干直接达标（summarizer=null 的零成本路径）
        assertThat(mem.compressMem(key, 10_000_000, null)).isTrue();
        // 极小目标 → 规则层尽力后（summarizer 为 null 时 L4 跳过）允许不达标，但不得抛
        mem.compressMem(key, 50, null);
        assertThat(mem.compressionLog(key)).isInstanceOf(List.class);
    }

    // ------------------------------------------------------------------
    // 五区预算模型（纯计量，零落盘）
    // ------------------------------------------------------------------

    @Test
    void 预算模型计量与分区写入() {
        ConversationMemory.ContextBudget b = ConversationMemory.ContextBudget.of();
        assertThat(b.window()).isEqualTo(ConversationMemory.ContextBudget.DEFAULT_WINDOW);
        b.zone(ConversationMemory.ContextBudget.Zone.SYSTEM, "系统提示");
        assertThat(b.chars(ConversationMemory.ContextBudget.Zone.SYSTEM)).isEqualTo(4);
        assertThat(b.targetRatio(ConversationMemory.ContextBudget.Zone.TOOL)).isEqualTo(0.40);
        // null 视为空串，链式返回自身
        assertThat(b.zone(ConversationMemory.ContextBudget.Zone.TASK, null)
                .chars(ConversationMemory.ContextBudget.Zone.TASK)).isZero();
    }

    @Test
    void 工具历史分段追加覆盖与超限回收() {
        ConversationMemory.ContextBudget b = ConversationMemory.ContextBudget.of();
        b.appendTool("0123456789").appendTool("0123456789").appendTool("0123456789");
        assertThat(b.chars(ConversationMemory.ContextBudget.Zone.TOOL)).isEqualTo(33); // 3×(10+换行)
        // 超 cap 从最旧段丢：33→22→11 达标，丢 2 段
        assertThat(b.capTool(20)).isEqualTo(2);
        assertThat(b.chars(ConversationMemory.ContextBudget.Zone.TOOL)).isEqualTo(11);
        // 覆盖式重建
        b.toolFromHistory(List.of("a", "bb"));
        assertThat(b.text(ConversationMemory.ContextBudget.Zone.TOOL)).isEqualTo("a\nbb\n");
        // cap 低于已用 → 逐段丢尽至空（回收的是段数，不是剩多少）
        assertThat(b.capTool(0)).isEqualTo(2);
        assertThat(b.chars(ConversationMemory.ContextBudget.Zone.TOOL)).isZero();
        assertThat(b.capTool(10)).isZero();  // 空表再回收：本就未超限，0 段丢弃
    }

    @Test
    void 预算判定三档阈值() {
        // 空载大窗 → 充足
        assertThat(ConversationMemory.ContextBudget.of().decide().verdict())
                .isEqualTo(ConversationMemory.ContextBudget.Verdict.SUFFICIENT);
        // FREE 13% → WARN（<15% 未及 10%）
        var warn = ConversationMemory.ContextBudget.of().window(100)
                .zone(ConversationMemory.ContextBudget.Zone.SYSTEM, "x".repeat(87)).decide();
        assertThat(warn.verdict()).isEqualTo(ConversationMemory.ContextBudget.Verdict.WARN);
        assertThat(warn.status(ConversationMemory.ContextBudget.Zone.FREE))
                .isEqualTo(ConversationMemory.ContextBudget.Status.WARN);
        // FREE 8% → 需压缩，needCompress 置位且加载度入结论
        var need = ConversationMemory.ContextBudget.of().window(100)
                .zone(ConversationMemory.ContextBudget.Zone.SYSTEM, "x".repeat(92)).decide();
        assertThat(need.needCompress()).isTrue();
        assertThat(need.load()).isCloseTo(0.92, org.assertj.core.data.Offset.offset(0.001));
        // SYSTEM 恒 OK：固定不可压是判定规则
        assertThat(need.status(ConversationMemory.ContextBudget.Zone.SYSTEM))
                .isEqualTo(ConversationMemory.ContextBudget.Status.OK);
    }

    @Test
    void 预算拷贝隔离不共享可变状态() {
        var b = ConversationMemory.ContextBudget.of()
                .zone(ConversationMemory.ContextBudget.Zone.MEMORY, "原内容").window(500);
        var c = b.copy();
        c.zone(ConversationMemory.ContextBudget.Zone.MEMORY, "改了");
        assertThat(b.text(ConversationMemory.ContextBudget.Zone.MEMORY)).isEqualTo("原内容");
        assertThat(c.window()).isEqualTo(500);
    }
}
