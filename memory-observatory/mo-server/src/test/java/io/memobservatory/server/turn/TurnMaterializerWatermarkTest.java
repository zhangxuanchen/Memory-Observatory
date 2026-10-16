package io.memobservatory.server.turn;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 【server/turn/TurnMaterializer】物化水位持久化的结构 + 容错回归。
 *
 * <p>治架构审视弱点 5：水位原本只在内存，重启即 EPOCH 全量回看。现在每轮成功
 * 落库 {@code memory_materialize_watermark}、首轮从库接续。正确性不靠水位本身——
 * 靠「候选全量重聚合 + turn_id 幂等 upsert」兑底，所以水位读失败/落库失败都必须
 * <b>只降级不阻断</b>。本测试用无 DataSource 的 {@link JdbcTemplate} 模拟「库不可达」：
 * 一切调用必须安静地走 fail-open 路径。
 *
 * <p>SQL 字符串钉法与 {@link TurnMaterializerSourceTest} 同口径：幂等语义（ON CONFLICT）
 * 一旦被人改丢，重启重放就会写出两格水位——这里秒红。
 */
class TurnMaterializerWatermarkTest {

    /** 无 DataSource 的 JdbcTemplate：任何查询/更新都抛 IllegalStateException ≈ 库不可达。 */
    private static TurnMaterializer offline() {
        return new TurnMaterializer(new JdbcTemplate());
    }

    @Test
    void 水位落库SQL必须幂等且按管线隔离() {
        assertThat(TurnMaterializer.SQL_WATERMARK_SAVE)
                .contains("ON CONFLICT (pipeline) DO UPDATE")
                .contains("EXCLUDED.watermark_ts");
        assertThat(TurnMaterializer.SQL_WATERMARK_LOAD)
                .contains("WHERE pipeline = ?");
        assertThat(TurnMaterializer.PIPELINE_TURNS).isEqualTo("turns");
    }

    @Test
    void 库不可达时读水位只降级不阻断() {
        TurnMaterializer m = offline();
        assertThatCode(m::ensureWatermarkRestored).doesNotThrowAnyException();
        // 读失败：水位维持 EPOCH（本轮按全量兑），且不算「已接续」——下轮继续重试
        assertThat(m.currentWatermark()).isEqualTo(Instant.EPOCH);
        assertThat(m.watermarkRestored()).isFalse();
    }

    @Test
    void 库不可达时落水位只降级不阻断() {
        TurnMaterializer m = offline();
        assertThatCode(() -> m.persistWatermark(Instant.now())).doesNotThrowAnyException();
        // 内存推进不受落库失败影响（本轮成果不丢）
        assertThat(m.currentWatermark()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void 库不可达时整轮安静返回零() {
        // runOnce 串起全流程：接水位(失败)→取候选(失败)→catch→返回 0，绝不把异常漏给调度线程
        TurnMaterializer m = offline();
        assertThatCode(() -> m.runOnce()).doesNotThrowAnyException();
        assertThat(m.runOnce()).isZero();
    }
}
