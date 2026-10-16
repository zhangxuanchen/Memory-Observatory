package io.memobservatory.server.turn;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SourceFamilyClassifier} 的行为测试（零依赖手写替身：直接注入 source→family 映射，不碰 DB）。
 *
 * <p>替代原先对 SQL 字符串的断言——归类逻辑已挪到本类，测试随之从「断字符串」改为「断行为」。
 */
class SourceFamilyClassifierTest {

    private SourceFamilyClassifier classifier() {
        // 与 schema.sql 的播种一致
        return new SourceFamilyClassifier(Map.of(
                "import", "import",
                "trae_importer", "import",
                "workbuddy_importer", "import",
                "qoder_hook", "hook"));
    }

    /** 命中清单：按清单里的 family 归类（导入家族 → import，Qoder 实时旁路 → hook）。 */
    @Test
    void 命中清单按家族归类() {
        SourceFamilyClassifier c = classifier();
        assertThat(c.classify("trae_importer", true, true)).isEqualTo("import");
        assertThat(c.classify("workbuddy_importer", false, true)).isEqualTo("import");
        assertThat(c.classify("qoder_hook", true, true)).isEqualTo("hook");
        // 清单里最特殊的一条：Qoder 有 turn_user，但绝不能被算成 workbench
        assertThat(c.classify("qoder_hook", true, false)).isEqualTo("hook");
    }

    /** 未命中但有 turn_user → workbench。 */
    @Test
    void 未命中且有turnUser归workbench() {
        assertThat(classifier().classify("some_unknown", true, false)).isEqualTo("workbench");
    }

    /** 未命中但有 turn_actions → workbench。 */
    @Test
    void 未命中且有turnActions归workbench() {
        assertThat(classifier().classify("some_unknown", false, true)).isEqualTo("workbench");
    }

    /** 未命中且两者皆无 → otlp（兜底）。 */
    @Test
    void 未命中且无turn字段归otlp() {
        SourceFamilyClassifier c = classifier();
        assertThat(c.classify("some_unknown", false, false)).isEqualTo("otlp");
        assertThat(c.classify(null, false, false)).isEqualTo("otlp");
        assertThat(c.classify("", false, false)).isEqualTo("otlp");
    }

    /** 新增平台零改 Java 的证明：往清单里塞一行，立刻生效。 */
    @Test
    void 清单新增一行即生效() {
        SourceFamilyClassifier c = new SourceFamilyClassifier(Map.of(
                "import", "import", "newplat", "import"));
        assertThat(c.classify("newplat", true, true)).isEqualTo("import");
    }
}
