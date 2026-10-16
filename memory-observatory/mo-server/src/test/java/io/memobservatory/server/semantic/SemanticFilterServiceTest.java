package io.memobservatory.server.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.contract.ConfirmKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【server/semantic/SemanticFilterService】并行送检的行为回归。
 *
 * <p>十万级压测发现 problems 端点 11.6~14.2 s 的根因是 GET 线程里串行逐条调 LLM
 * （见《压测报告-十万级合成事件-20261012》）。改成并行后，本测试钉住并行化最容易
 * 丢的四件事：<b>原序、剔除语义、闸门语义、单条 fail-open</b>，外加「确实在并行」。
 *
 * <p>替身是手写 {@link FakeClient}（覆盖 predict/healthy），零 mock 库——与项目
 * 测试纪律一致；并发计数用 sleep 制造重叠窗口，断言的是「最大在途 &gt; 1」这种
 * 结构性质，不依赖挂钟毫秒数，不会 flaky。
 */
class SemanticFilterServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可控替身：按 state 里 "pTrue" 决定返回值，"boom"=true 抛异常；统计最大在途并发。 */
    static class FakeClient extends LayaClient {
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger maxInflight = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        FakeClient(SemanticProperties props) {
            super(props);
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode predict(
                Map<String, Object> state, Map<String, Object> questions, String langHint)
                throws Exception {
            calls.incrementAndGet();
            maxInflight.accumulateAndGet(incrementAndGetBoth(), Math::max);
            try {
                Thread.sleep(40); // 制造重叠窗口，让 maxInflight 能观测到并行
                if (Boolean.TRUE.equals(state.get("boom"))) {
                    throw new IllegalStateException("simulated laya failure");
                }
                double pTrue = ((Number) state.getOrDefault("pTrue", 0.9)).doubleValue();
                return MAPPER.readTree("{\"answers\":{\"is_genuine\":{\"noul\":" + pTrue + "}}}");
            } finally {
                inflight.decrementAndGet();
            }
        }

        private int incrementAndGetBoth() {
            return inflight.incrementAndGet();
        }
    }

    private static SemanticProperties props(int topN, int concurrency) {
        SemanticProperties p = new SemanticProperties();
        p.setEnabled(true);
        p.setTopN(topN);
        p.setConcurrency(concurrency);
        return p;
    }

    /** PROBLEM 阈值 0.65：pFalse ≥ 0.65 剔除，即 pTrue ≤ 0.35 的候选是「假阳性」。 */
    private static SemanticFilterService svc(FakeClient c, int topN, int concurrency) {
        return new SemanticFilterService(c, props(topN, concurrency));
    }

    private static List<Map<String, Object>> hits(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> h = new java.util.LinkedHashMap<>();
            h.put("id", i);
            list.add(h);
        }
        return list;
    }

    /** stateOf：hit 里带 pTrue 即送检；带 noState 则不可送检。 */
    private static Map<String, Object> stateOf(Map<String, Object> hit) {
        if (hit.containsKey("noState")) {
            return null;
        }
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("pTrue", hit.getOrDefault("pTrue", 0.9));
        if (hit.containsKey("boom")) {
            s.put("boom", true);
        }
        return s;
    }

    @Test
    void 剔除假阳性且保留项维持原序() {
        FakeClient client = new FakeClient(props(20, 8));
        List<Map<String, Object>> in = hits(3);
        in.get(0).put("pTrue", 0.9);   // pFalse=0.1 → 保留
        in.get(1).put("pTrue", 0.1);   // pFalse=0.9 ≥ 0.65 → 剔除
        in.get(2).put("pTrue", 0.8);   // 保留
        var out = svc(client, 20, 8).filter(in, ConfirmKind.PROBLEM, SemanticFilterServiceTest::stateOf);
        assertThat(out.status()).isEqualTo("ok");
        assertThat(out.removed()).isEqualTo(1);
        assertThat(out.hits()).containsExactly(in.get(0), in.get(2)); // 原序，不是并行返回序
    }

    @Test
    void 单条失败时failopen原位保留() {
        FakeClient client = new FakeClient(props(20, 8));
        List<Map<String, Object>> in = hits(2);
        in.get(0).put("boom", true);   // 送检抛异常 → 保留
        in.get(1).put("pTrue", 0.1);   // 正常剔除
        var out = svc(client, 20, 8).filter(in, ConfirmKind.PROBLEM, SemanticFilterServiceTest::stateOf);
        assertThat(out.hits()).containsExactly(in.get(0));
        assertThat(out.confirmed()).isEqualTo(1); // 失败的那条不计入送检数
    }

    @Test
    void 闸门只送前topN条其余直接保留() {
        FakeClient client = new FakeClient(props(2, 8));
        List<Map<String, Object>> in = hits(5);
        var out = svc(client, 2, 8).filter(in, ConfirmKind.PROBLEM, SemanticFilterServiceTest::stateOf);
        assertThat(client.calls).hasValue(2);
        assertThat(out.hits()).hasSize(5);        // 全部保留（只有保留项才可能进 kept）
        assertThat(out.confirmed()).isEqualTo(2);
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void 不可送检的候选不吃闸门额度() {
        FakeClient client = new FakeClient(props(2, 8));
        List<Map<String, Object>> in = hits(4);
        in.get(0).put("noState", true);          // 不可送检 → 保留，不占额度
        var out = svc(client, 2, 8).filter(in, ConfirmKind.PROBLEM, SemanticFilterServiceTest::stateOf);
        assertThat(client.calls).hasValue(2);    // 送检的是第 2、3 条而非被 noState 挤掉
        assertThat(out.hits()).containsExactly(in.get(0), in.get(1), in.get(2), in.get(3));
        assertThat(out.truncated()).isTrue();    // 第 4 条被闸门挡下
    }

    @Test
    void 批量候选真的并行发送() {
        FakeClient client = new FakeClient(props(20, 8));
        var out = svc(client, 20, 8).filter(hits(6), ConfirmKind.PROBLEM, SemanticFilterServiceTest::stateOf);
        assertThat(out.confirmed()).isEqualTo(6);
        // 串行版 maxInflight 恒为 1；并行版应观测到在途重叠（上限受 concurrency=8）
        assertThat(client.maxInflight.get()).isGreaterThan(1).isLessThanOrEqualTo(8);
    }
}
