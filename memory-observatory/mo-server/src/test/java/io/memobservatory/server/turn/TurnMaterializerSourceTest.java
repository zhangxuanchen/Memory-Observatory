package io.memobservatory.server.turn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源归类的结构回归测试。
 *
 * <p>归类已从 SQL 的硬编码 {@code CASE WHEN} 挪到 {@link SourceFamilyClassifier}
 * （查 {@code event_source_family} 表）。本测试只钉一件事：SQL 里**不再**出现家族清单，
 * 免得有人又把平台名写回 SQL——那会让「新增平台零改 Java、零重建」失效。
 */
class TurnMaterializerSourceTest {

    @Test
    void SQL只取原始平台名不再硬编码家族() {
        assertThat(TurnMaterializer.SQL_LOAD)
                .contains("AS meta_source")
                .doesNotContain("CASE WHEN")
                .doesNotContain("'import'")
                .doesNotContain("'trae_importer'")
                .doesNotContain("'workbuddy_importer'")
                .doesNotContain("'qoder_hook'")
                .doesNotContain("'workbench'");
    }

    /**
     * 回填兜底（十万级压测发现③，2026-10-12）：候选除了「ts 过水位」的，还必须包含
     * 「有事件但从未物化」的 turn（反连接 turns）——否则导入器回填老 ts 历史后，
     * 这些 turn 永远进不了候选。本测试钉住反连接存在，免得优化时被人当冗余删掉。
     */
    @Test
    void 候选含未物化turn的反兑底_老ts回填不漏() {
        assertThat(TurnMaterializer.SQL_LOAD)
                .contains("NOT EXISTS")
                .contains("memory_turns t")
                .contains("UNION");
    }
}
