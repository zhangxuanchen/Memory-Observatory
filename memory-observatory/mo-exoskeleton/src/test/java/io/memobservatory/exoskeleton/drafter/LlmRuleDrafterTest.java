package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.model.ClusterProfile;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmRuleDrafter} 的行为：把簇画像交给（替身）LLM，产出草稿文本。
 *
 * <p><b>为什么用零依赖手写替身</b>：本机 JDK 高，Mockito / Byte Buddy 不可用；直接继承
 * {@link LlmChatClient} 覆盖 {@code isEnabled()} 与 {@code chat(...)} 即可，等价断言、零新增依赖
 * （与本模块既有的替身范式一致）。{@link DraftCache} 同样如此——继承后把两级缓存换成一张内存表。
 *
 * <p>要害各自成测：**不编**（未配置 / 失败 / 空 / 越界 → 不产出）、**说清为什么**（每种不产出都带真因，
 * 登记口 B20.1）、**不越界**（机器校验，B20.4）、**喂样本**（B20.2）、**确定性**（同一画像同一文本、
 * 不重复调 LLM，且跨实例成立——B20.3）、**隔离**（失败只毁这一簇、不抛穿）。
 */
class LlmRuleDrafterTest {

    /** 可控替身：既能回固定文本，也能抛异常，并记被调用次数。 */
    private static class StubLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        String reply = "命中即用，不为留痕额外写回";
        Exception error;
        boolean enabled = true;
        String lastSystem;
        String lastUser;

        StubLlm() {
            super("", "", "", "", 1, 0, 0);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String chat(String system, String user, LlmUsageSink sink) throws Exception {
            calls.incrementAndGet();
            lastSystem = system;
            lastUser = user;
            if (error != null) {
                throw error;
            }
            return reply;
        }
    }

    /**
     * 草稿缓存替身：把 L1/L2 换成一张内存表（不碰 JdbcTemplate）。
     * 同一个替身实例传给两个撰写器，就等价于「进程重启后 L2 还在」——B20.3 的跨重启断言即这么说。
     */
    private static class StubCache extends DraftCache {
        final Map<String, String> stored = new HashMap<>();

        StubCache() {
            super(null);
        }

        @Override
        public Optional<String> get(String clusterKey) {
            return Optional.ofNullable(stored.get(clusterKey));
        }

        @Override
        public void put(String clusterKey, String body, String model) {
            stored.put(clusterKey, body);
        }
    }

    private static ClusterProfile profile(String key) {
        return new ClusterProfile(key, 120);
    }

    /** 新建撰写器并配一份空缓存（同一实例内的多次 draft 共用它，故仍验得到确定性）。 */
    private static LlmRuleDrafter newDrafter(LlmChatClient llm) {
        return new LlmRuleDrafter(llm, new StubCache());
    }

    @Test
    @DisplayName("空画像 / 空簇键：如实不产出，且不调用 LLM")
    void 空画像留空不调用() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        assertFalse(drafter.draft(null).produced());
        assertFalse(drafter.draft(new ClusterProfile("  ", 10)).produced());
        assertEquals(0, llm.calls.get());
    }

    @Test
    @DisplayName("簇键不合口径（缺 steps / 取值不在词表）：不产出，不调用 LLM")
    void 簇键不合口径留空() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        assertFalse(drafter.draft(profile("readonly")).produced());
        assertFalse(drafter.draft(profile("weird|steps:1-3")).produced());
        assertFalse(drafter.draft(profile("readonly|steps:99")).produced());
        assertEquals(0, llm.calls.get());
    }

    @Test
    @DisplayName("LLM 未配置：fail-open 不产出，不报错、不调用")
    void LLM未配置留空() {
        StubLlm llm = new StubLlm();
        llm.enabled = false;
        LlmRuleDrafter drafter = newDrafter(llm);

        assertFalse(drafter.draft(profile("readonly|steps:4-10")).produced());
        assertEquals(0, llm.calls.get());
    }

    @Test
    @DisplayName("正常：返回清洗后的文本，画像被拆成人话送模型")
    void 正常产出并送画像() {
        StubLlm llm = new StubLlm();
        llm.reply = "  边读边写：读到的先归并再落笔。  ";
        LlmRuleDrafter drafter = newDrafter(llm);

        RuleDrafter.Draft out = drafter.draft(profile("readwrite|steps:4-10"));

        assertTrue(out.produced());
        assertEquals("边读边写：读到的先归并再落笔。", out.body().get());
        assertEquals(1, llm.calls.get());
        assertTrue(llm.lastUser.contains("边读边写"), llm.lastUser);
        assertTrue(llm.lastUser.contains("4–10 步"), llm.lastUser);
        assertTrue(llm.lastUser.contains("120 个 turn"), llm.lastUser);
        // 提示词把范围钉在记忆策略、禁掉推理步骤（契约第 2 条）
        assertTrue(llm.lastSystem.contains("推理策略"), llm.lastSystem);
    }

    @Test
    @DisplayName("喂样本（B20.2）：画像里的样本进提示词，模型据此看到「这一簇在干什么」")
    void 样本进提示词() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        ClusterProfile p = new ClusterProfile("readwrite|steps:4-10", 120,
                List.of("帮我给订单模块加一个超时重试", "把导出的 csv 改成带表头"));
        drafter.draft(p);

        assertTrue(llm.lastUser.contains("样本"), llm.lastUser);
        assertTrue(llm.lastUser.contains("超时重试"), llm.lastUser);
        assertTrue(llm.lastUser.contains("带表头"), llm.lastUser);
    }

    @Test
    @DisplayName("无样本时不硬塞一段空样本（画像字段仍是必填，只是内容为空）")
    void 无样本不塞空段() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        drafter.draft(profile("readonly|steps:4-10"));

        assertFalse(llm.lastUser.contains("样本"), llm.lastUser);
    }

    @Test
    @DisplayName("越界机器校验（B20.4）：含分步 / 推理措辞的草稿不产出、不写也不缓存")
    void 越界不产出() {
        StubLlm llm = new StubLlm();
        llm.reply = "第一步：先读一遍记忆；第二步：按结论落笔";
        LlmRuleDrafter drafter = newDrafter(llm);

        RuleDrafter.Draft out = drafter.draft(profile("readonly|steps:4-10"));
        assertFalse(out.produced());
        assertNotNull(out.reason());
        assertTrue(out.reason().contains("越界"), out.reason());

        // 越界不缓存：同一簇再要一次仍会重新问模型（而不是把越界句记下来复用）
        drafter.draft(profile("readonly|steps:4-10"));
        assertEquals(2, llm.calls.get());
    }

    @Test
    @DisplayName("越界校验刻意保守：含「先…再…」的正常措辞不被误伤")
    void 合规措辞不误伤() {
        StubLlm llm = new StubLlm();
        llm.reply = "只读未写：读到的先归并再落笔，命中即用";
        LlmRuleDrafter drafter = newDrafter(llm);

        assertTrue(drafter.draft(profile("readonly|steps:4-10")).produced());
    }

    @Test
    @DisplayName("实机误伤回归（B20.4）：真实模型写的「该忘：已完成的中间步骤」是合规的，不该被拦")
    void 中间步骤不算越界() {
        StubLlm llm = new StubLlm();
        // 这条文本取自实机（本机 qwen3）的真实产出：首版词表把裸「步骤」当信号，把它误拦了
        llm.reply = "该记：当前处理的文件路径与关键错误信息；该忘：已完成的中间步骤；"
                + "何时读回：遇到相同错误类型时；用哪个工具：代码审查工具";
        LlmRuleDrafter drafter = newDrafter(llm);

        assertTrue(drafter.draft(profile("readwrite|steps:1-3")).produced());
    }

    @Test
    @DisplayName("确定性：同一画像第二次要，回同一文本且不再调 LLM")
    void 同一画像不再重复调用() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        RuleDrafter.Draft first = drafter.draft(profile("readonly|steps:11+"));
        RuleDrafter.Draft second = drafter.draft(profile("readonly|steps:11+"));

        assertEquals(first.body(), second.body());
        assertEquals(1, llm.calls.get());
    }

    @Test
    @DisplayName("确定性跨重启（B20.3）：缓存落在撰写器之外时，新实例照样回同一文本、不再调 LLM")
    void 确定性跨实例成立() {
        StubLlm llm = new StubLlm();
        llm.reply = "只读未写：命中即用";
        StubCache shared = new StubCache();   // 相当于重启后仍在的那张表

        RuleDrafter.Draft before = new LlmRuleDrafter(llm, shared).draft(profile("readonly|steps:4-10"));
        RuleDrafter.Draft after = new LlmRuleDrafter(llm, shared).draft(profile("readonly|steps:4-10"));

        assertEquals(before.body(), after.body());
        assertEquals(1, llm.calls.get());
    }

    @Test
    @DisplayName("不同画像各自调用一次，互不串味")
    void 不同画像各自调用() {
        StubLlm llm = new StubLlm();
        LlmRuleDrafter drafter = newDrafter(llm);

        drafter.draft(profile("readonly|steps:1-3"));
        drafter.draft(profile("writeonly|steps:1-3"));

        assertEquals(2, llm.calls.get());
    }

    @Test
    @DisplayName("调用失败：这一簇不产出、不抛穿；且不缓存——下次仍会重试")
    void 调用失败留空且不缓存() {
        StubLlm llm = new StubLlm();
        llm.error = new RuntimeException("LLM HTTP 500");
        LlmRuleDrafter drafter = newDrafter(llm);

        assertFalse(drafter.draft(profile("none|steps:4-10")).produced());
        assertFalse(drafter.draft(profile("none|steps:4-10")).produced());
        assertEquals(2, llm.calls.get());
    }

    @Test
    @DisplayName("返回空白：不产出，不缓存成一句空话")
    void 返回空白留空() {
        StubLlm llm = new StubLlm();
        llm.reply = "   \n  ";
        LlmRuleDrafter drafter = newDrafter(llm);

        assertFalse(drafter.draft(profile("readonly|steps:4-10")).produced());
    }

    @Test
    @DisplayName("返回被引号包住：去掉外层引号")
    void 去外层引号() {
        StubLlm llm = new StubLlm();
        llm.reply = "「只读未写：命中即用」";
        LlmRuleDrafter drafter = newDrafter(llm);

        RuleDrafter.Draft out = drafter.draft(profile("readonly|steps:4-10"));

        assertTrue(out.produced());
        assertEquals("只读未写：命中即用", out.body().get());
        assertFalse(out.body().get().contains("「"));
    }

    @Test
    @DisplayName("超长返回：截断到上限（防御模型话痨）")
    void 超长截断() {
        StubLlm llm = new StubLlm();
        llm.reply = "记".repeat(500);
        LlmRuleDrafter drafter = newDrafter(llm);

        RuleDrafter.Draft out = drafter.draft(profile("readonly|steps:4-10"));

        assertTrue(out.produced());
        assertEquals(200, out.body().get().length());
    }

    @Test
    @DisplayName("没产出时带上真因（B20.1）：五种「不产出」各有可读原因，不再是一句笼统的「无对应策略」")
    void 不产出时带真因() {
        // ① 未配置
        StubLlm off = new StubLlm();
        off.enabled = false;
        assertEquals("LLM 通道未配置（base-url / model 为空）",
                newDrafter(off).draft(profile("readonly|steps:4-10")).reason());

        // ② 簇键不合口径
        assertTrue(newDrafter(new StubLlm())
                .draft(profile("readonly")).reason().contains("簇键不合口径"));

        // ③ 调用失败（真因里带异常简报）
        StubLlm bad = new StubLlm();
        bad.error = new RuntimeException("connection reset");
        String failReason = newDrafter(bad).draft(profile("readonly|steps:4-10")).reason();
        assertNotNull(failReason);
        assertTrue(failReason.startsWith("LLM 调用失败："), failReason);
        assertTrue(failReason.contains("connection reset"), failReason);

        // ④ 返回空正文
        StubLlm blank = new StubLlm();
        blank.reply = "   ";
        assertEquals("LLM 返回空正文",
                newDrafter(blank).draft(profile("readonly|steps:4-10")).reason());

        // ⑤ 产出越界（B20.4）
        StubLlm out = new StubLlm();
        out.reply = "先规划一下整体方案再动手";
        assertTrue(newDrafter(out).draft(profile("readonly|steps:4-10")).reason().contains("越界"));

        // 产出时不该有原因
        assertNotNull(newDrafter(new StubLlm())
                .draft(profile("readonly|steps:4-10")).body().orElse(null));
        assertNull(newDrafter(new StubLlm())
                .draft(profile("readonly|steps:4-10")).reason());
    }
}
