package io.memobservatory.exoskeleton.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvidenceDigest} 的预算纪律与抽样代表性。
 *
 * <p><b>为什么要专测预算</b>：{@code B14.4} 的教训是——只拿「正文之和」跟预算比，
 * 长串会一路超出去（每条前面那截 {@code [3/12] } 与分隔换行也是字符）。故这里用
 * 「正文远大于预算」的长串把这条守住：渲染结果<b>必须</b>落在预算之内。
 */
class EvidenceDigestTest {

    @Test
    @DisplayName("短串：全量保留、不裁剪、不省略")
    void 短串全量保留() {
        List<String> texts = List.of("第一件事", "第二件事", "第三件事");

        String out = EvidenceDigest.render(texts, 20000);

        assertTrue(out.contains("第一件事"));
        assertTrue(out.contains("第三件事"));
        assertTrue(out.length() <= 20000);
    }

    @Test
    @DisplayName("长串：正文之和远超预算，结果仍严格落在预算内（前缀与换行也算字符）")
    void 长串不超预算() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 575; i++) {
            // 每条正文 ~900 字，总计 ~517500 字，远超 20000 的预算
            texts.add(("第 " + i + " 条：" + "细节描述。".repeat(180)));
        }

        String out = EvidenceDigest.render(texts, 20000);

        assertTrue(out.length() <= 20000,
                "摘要长度必须 <= 预算（含每条前缀与换行的骨架开销），实得 " + out.length());
    }

    @Test
    @DisplayName("抽样代表性：首尾必取，覆盖整串而非只取开头")
    void 抽样覆盖首尾() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 575; i++) {
            texts.add("第 " + i + " 条：" + "细节描述。".repeat(180));
        }

        String out = EvidenceDigest.render(texts, 20000);

        List<Integer> seen = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\[(\\d+)/575\\]").matcher(out);
        while (m.find()) {
            seen.add(Integer.parseInt(m.group(1)));
        }
        assertEquals(1, seen.get(0), "首条必取");
        assertEquals(575, seen.get(seen.size() - 1), "末条必取");
        // 若只取开头，中间条目的序号不会出现；抽样应覆盖整串
        assertTrue(seen.stream().anyMatch(k -> k > 150 && k < 425),
                "应隔条抽样覆盖整串，而不是截取开头一段");
    }

    @Test
    @DisplayName("指纹：同输入同指纹、异输入异指纹，且为 64 位十六进制")
    void 指纹稳定且区分() {
        String a = EvidenceDigest.sha256("agent-x\n__fact__\n同一份证据");
        String b = EvidenceDigest.sha256("agent-x\n__fact__\n同一份证据");
        String c = EvidenceDigest.sha256("agent-x\n__fact__\n换了一份证据");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("内容指纹：同名同 prompt 同工具 → 同指纹；任一变化 → 异指纹，且为 64 位十六进制")
    void 内容指纹稳定且区分() {
        String a = EvidenceDigest.contentHash("写文档", "先读再写", List.of("readFile", "writeFile"));
        String b = EvidenceDigest.contentHash("写文档", "先读再写", List.of("readFile", "writeFile"));
        String c = EvidenceDigest.contentHash("写文档", "先读再写", List.of("readFile"));
        String d = EvidenceDigest.contentHash("写文档", "换个 prompt", List.of("readFile", "writeFile"));

        assertEquals(a, b, "同内容必须同指纹");
        assertNotEquals(a, c, "工具变了，指纹必须变");
        assertNotEquals(a, d, "prompt 变了，指纹必须变");
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("内容指纹：null 与空列表等价（都当「没有工具」），且不因 null 崩")
    void 内容指纹容忍空值() {
        assertEquals(EvidenceDigest.contentHash("n", "p", null),
                EvidenceDigest.contentHash("n", "p", List.of()));
        assertEquals(EvidenceDigest.contentHash(null, null, null),
                EvidenceDigest.contentHash("", "", List.of()));
    }

    @Test
    @DisplayName("内容指纹：工具顺序参与指纹（提取端顺序稳定，故顺序即语义）")
    void 内容指纹含工具顺序() {
        assertNotEquals(
                EvidenceDigest.contentHash("n", "p", List.of("readFile", "writeFile")),
                EvidenceDigest.contentHash("n", "p", List.of("writeFile", "readFile")));
    }
}