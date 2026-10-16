package io.memobservatory.exoskeleton.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外骨骼侧 io_kind 读/写词表一致性守卫（架构重构 M1 / 步骤 8，轻量路线）。
 *
 * <p>为什么需要它：{@link ShapeBucketer} 的读类三项、写类两项是 mo-server {@code IoKind}
 * 词表在**本模块里的第二份拷贝**——两个模块各自持一份，源码注释写着「与 mo-server 的
 * {@code IoKind.READ_KINDS} 逐字一致」，但在本测试之前没有任何机器校验。任一侧改词表而
 * 另一侧忘记同步，两侧就会按不同口径分桶，报表静默错位。
 *
 * <p>为什么不新建共享模块：两个 Java 模块的 Dockerfile 各以自己目录为构建上下文
 * （见 {@code 架构重构-增量与目标蓝图.md} §5 P1-2 与步骤 8 记录），加跨模块依赖会让镜像
 * 构建解析不到。故走轻量路线——不搬代码，只把「一致」变成断言：两侧都与仓库根的
 * {@code contract/vocabulary.json} 比对，传递性即保证彼此逐字一致。
 *
 * <p>与 mo-server 的 {@code ContractVocabularyTest}、Python 的 {@code VocabularyJsonTest}
 * 读**同一份**契约文件。
 */
class ShapeBucketerVocabularyTest {

    private static final Path CONTRACT = repoRoot().resolve("contract").resolve("vocabulary.json");

    @Test
    void 契约文件可被定位() {
        assertTrue(Files.isRegularFile(CONTRACT),
                "找不到仓库根的 contract/vocabulary.json：" + CONTRACT);
    }

    @Test
    void 读类与契约文件一致() throws Exception {
        JsonNode root = readContract();
        Set<String> expected = stringSet(root.get("io_kind_read"));
        assertEquals(expected, readKinds("READ_KINDS"),
                "ShapeBucketer.READ_KINDS 与 contract/vocabulary.json 的 io_kind_read 不一致："
                        + "改了读类就要同步改三侧，否则与外骨骼的分桶口径会跟 mo-server 漂移");
    }

    @Test
    void 写类与契约文件一致() throws Exception {
        JsonNode root = readContract();
        Set<String> expected = stringSet(root.get("io_kind_write"));
        assertEquals(expected, readKinds("WRITE_KINDS"),
                "ShapeBucketer.WRITE_KINDS 与 contract/vocabulary.json 的 io_kind_write 不一致："
                        + "改了写类就要同步改三侧，否则与外骨骼的分桶口径会跟 mo-server 漂移");
    }

    @Test
    void 读写两类都取自封闭词表() throws Exception {
        Set<String> vocabulary = stringSet(readContract().get("io_kind_vocabulary"));
        assertTrue(vocabulary.containsAll(readKinds("READ_KINDS")),
                "读类里出现了不在 io_kind_vocabulary 封闭词表内的取值");
        assertTrue(vocabulary.containsAll(readKinds("WRITE_KINDS")),
                "写类里出现了不在 io_kind_vocabulary 封闭词表内的取值");
    }

    /** 反射读 {@link ShapeBucketer} 的私有词表常量——不为了测试便利而放宽生产代码的可见性。 */
    @SuppressWarnings("unchecked")
    private static Set<String> readKinds(String fieldName) throws Exception {
        Field field = ShapeBucketer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return new LinkedHashSet<>((Set<String>) field.get(null));
    }

    private static JsonNode readContract() throws IOException {
        return new ObjectMapper().readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
    }

    private static Set<String> stringSet(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** 从工作目录上溯定位仓库根（须同时含 mo-server 与 mo-exoskeleton）。 */
    private static Path repoRoot() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null
                && !(Files.isDirectory(root.resolve("mo-server")) && Files.isDirectory(root.resolve("mo-exoskeleton")))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("无法定位仓库根（需同时含 mo-server 与 mo-exoskeleton）");
        }
        return root;
    }
}