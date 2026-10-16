package io.memobservatory.server.notify;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 去重键的稳定性测试。
 *
 * <p>键不稳定是这套机制最容易犯的错，且后果很隐蔽：表现不是报错，而是同一个问题
 * 每轮都被当成新问题重推一遍。所以这里只测「同输入同键、异输入异键」这一件事。
 */
class DedupKeysTest {

    private static Map<String, Object> hit(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void 阈值键_同输入必须同键() {
        Map<String, Object> a = hit("turnId", "t-2");
        Map<String, Object> b = hit("turnId", "t-2");
        assertThat(FindingCollector.thresholdKey("turn-token", "role-frontend", a))
                .isEqualTo(FindingCollector.thresholdKey("turn-token", "role-frontend", b));
    }

    @Test
    void 阈值键_实体不同必须异键() {
        assertThat(FindingCollector.thresholdKey("turn-token", "role-frontend", hit("turnId", "t-2")))
                .isNotEqualTo(FindingCollector.thresholdKey("turn-token", "role-frontend", hit("turnId", "t-3")));
    }

    /** 同一 turn 里两个不同技能各自循环，必须是两条告警而不是一条。 */
    @Test
    void 阈值键_同turn不同skill必须异键() {
        Map<String, Object> a = new LinkedHashMap<>(hit("turnId", "t-2"));
        a.put("skill", "read_file");
        Map<String, Object> b = new LinkedHashMap<>(hit("turnId", "t-2"));
        b.put("skill", "bash");
        assertThat(FindingCollector.thresholdKey("skill-repeat", "role-frontend", a))
                .isNotEqualTo(FindingCollector.thresholdKey("skill-repeat", "role-frontend", b));
    }

    /** 聚合型规则没有实体字段，退到 agent 级：命中行数变化不该被当成新问题。 */
    @Test
    void 阈值键_聚合型规则退到agent级() {
        String k1 = FindingCollector.thresholdKey("skill-count", "role-frontend", hit("events", 300));
        String k2 = FindingCollector.thresholdKey("skill-count", "role-frontend", hit("events", 500));
        assertThat(k1).isEqualTo(k2);
    }

    /** 实体字段取不到时也要退到 agent 级，而不是拼出 null。 */
    @Test
    void 阈值键_实体字段缺失时退化且不出现null字面量() {
        String key = FindingCollector.thresholdKey("mem-churn", "role-frontend", new LinkedHashMap<>());
        assertThat(key).isEqualTo("thr|mem-churn|role-frontend|-");
        assertThat(key).doesNotContain("null");
    }

    /** signals 顺序不同必须同键——否则同一条流程问题每轮都算新的。 */
    @Test
    void 流程键_signals顺序不同必须同键() {
        assertThat(FindingCollector.flowKey("role-frontend", "read@skill", List.of("S1", "S3")))
                .isEqualTo(FindingCollector.flowKey("role-frontend", "read@skill", List.of("S3", "S1")));
    }

    @Test
    void 流程键_节点不同必须异键() {
        assertThat(FindingCollector.flowKey("role-frontend", "read@skill", List.of("S1")))
                .isNotEqualTo(FindingCollector.flowKey("role-frontend", "write@skill", List.of("S1")));
    }

    /** 同一个泄漏密钥出现在多处只报一次：键里不含事件 id。 */
    @Test
    void 风险键_同规则同字段跨事件必须同键() {
        assertThat(FindingCollector.riskKey("role-frontend", "cloud-ak", "memory"))
                .isEqualTo(FindingCollector.riskKey("role-frontend", "cloud-ak", "memory"));
    }

    @Test
    void 风险键_不同规则或字段必须异键() {
        assertThat(FindingCollector.riskKey("role-frontend", "cloud-ak", "memory"))
                .isNotEqualTo(FindingCollector.riskKey("role-frontend", "api-key", "memory"));
        assertThat(FindingCollector.riskKey("role-frontend", "cloud-ak", "memory"))
                .isNotEqualTo(FindingCollector.riskKey("role-frontend", "cloud-ak", "user_input"));
    }

    /** 不同 agent 的同一问题必须是两条，不能互相掩盖。 */
    @Test
    void 键必须含agentId() {
        assertThat(FindingCollector.flowKey("agent-a", "read@skill", List.of("S1")))
                .isNotEqualTo(FindingCollector.flowKey("agent-b", "read@skill", List.of("S1")));
        assertThat(FindingCollector.riskKey("agent-a", "cloud-ak", "memory"))
                .isNotEqualTo(FindingCollector.riskKey("agent-b", "cloud-ak", "memory"));
    }

    @Test
    void 阈值严重度_按规则分档() {
        assertThat(FindingCollector.thresholdSeverity("skill-error")).isEqualTo("critical");
        assertThat(FindingCollector.thresholdSeverity("turn-token")).isEqualTo("high");
        assertThat(FindingCollector.thresholdSeverity("skill-count")).isEqualTo("medium");
        // 未知规则名（新增或拼错）回落 medium，不会被静默丢掉
        assertThat(FindingCollector.thresholdSeverity("some-new-rule")).isEqualTo("medium");
    }
}