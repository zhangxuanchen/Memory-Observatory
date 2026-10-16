package io.memobservatory.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.agentloop.workbench.report.IoKind;
import io.memobservatory.server.model.MemoryOp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨语言契约一致性守卫（架构重构 M0 / 步骤 2）。
 *
 * <p>为什么需要它：{@code io_kind} 词表与 {@code operation} 归一值在本仓被写了两遍——
 * Java 侧 {@link IoKind} / {@link MemoryOp}，Python 侧 {@code mo_ingest/contract.py}。
 * 两处注释都写着「逐字一致」，但在本测试之前**没有任何机器校验**：
 * 全仓 Java 测试里 {@code IoKind} 出现 0 次（实测）。契约漂移无人会发现。
 *
 * <p>本测试把仓库根的 {@code contract/vocabulary.json} 当唯一真相源，断言 Java 侧常量与它逐字一致；
 * 对应的 Python 侧断言在 {@code mo_ingest/tests/test_contract.py}。
 *
 * <p>步骤 8（M1 轻量路线）起契约新增读/写分组（{@code io_kind_read} / {@code io_kind_write}）：
 * 外骨骼的 {@code ShapeBucketer.READ_KINDS/WRITE_KINDS} 是这两组词在另一模块里的第二份拷贝，
 * 由 {@code mo-exoskeleton} 的 {@code ShapeBucketerVocabularyTest} 断言与同一份契约一致——
 * 于是「逐字一致」这句注释承诺在三侧都变成了可执行的断言。
 *
 * <p>刻意不引 ArchUnit 等重型依赖，只用 Jackson + JUnit（均为既有依赖），与项目
 * 「零依赖手写守则测试」的约定一致。
 */
class ContractVocabularyTest {

    /** 从 user.dir 逐级上溯找 contract/vocabulary.json——`mvn -pl mo-server test` 的工作目录是模块目录。 */
    private static Path vocabularyPath() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("contract").resolve("vocabulary.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 contract/vocabulary.json（应从仓库根逐级上溯可见）");
    }

    private static JsonNode root() throws IOException {
        String json = Files.readString(vocabularyPath(), StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(json);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void ioKind词表与契约文件逐字一致() throws IOException {
        JsonNode root = root();
        // 用 List 而非 Set 比对——**顺序也是契约的一部分**（VOCABULARY 的注释声明「顺序即文档顺序」）。
        assertEquals(stringList(root.get("io_kind_vocabulary")), IoKind.VOCABULARY,
                "IoKind.VOCABULARY 与 contract/vocabulary.json 不一致：改了词表就要同步改两侧");
    }

    @Test
    void ioKind生命周期常量一致() throws IOException {
        JsonNode root = root();
        assertEquals(root.get("io_kind_lifecycle").asText(), IoKind.LIFECYCLE);
    }

    @Test
    void ioKind读类与契约文件一致() throws IOException {
        JsonNode root = root();
        Set<String> expected = new LinkedHashSet<>(stringList(root.get("io_kind_read")));
        assertEquals(expected, IoKind.READ_KINDS,
                "IoKind.READ_KINDS 与 contract/vocabulary.json 的 io_kind_read 不一致：改了读类就要同步改三侧");
    }

    @Test
    void ioKind写类与契约文件一致() throws IOException {
        JsonNode root = root();
        Set<String> expected = new LinkedHashSet<>(stringList(root.get("io_kind_write")));
        assertEquals(expected, IoKind.WRITE_KINDS,
                "IoKind.WRITE_KINDS 与 contract/vocabulary.json 的 io_kind_write 不一致：改了写类就要同步改三侧");
    }

    @Test
    void operation归一值与契约文件一致() throws IOException {
        JsonNode root = root();
        Set<String> expected = new LinkedHashSet<>(stringList(root.get("operation_canonical")));
        Set<String> actual = new LinkedHashSet<>();
        for (MemoryOp op : MemoryOp.values()) {
            actual.add(op.name());
        }
        assertEquals(expected, actual, "MemoryOp 枚举值与 contract/vocabulary.json 不一致");
    }

    @Test
    void operation别名归一与契约文件一致() throws IOException {
        // R7：别名 → 归一值的映射此前无机器校验，Java 把 consolidate 归成了 WRITE（应为 UPDATE）。
        // 这里逐条比对契约文件里的 operation_aliases，任一侧漂移立即变红。
        JsonNode root = root();
        JsonNode aliases = root.get("operation_aliases");
        assertTrue(aliases != null && aliases.isObject() && !aliases.isEmpty(),
                "契约文件必须含非空 operation_aliases 对象");
        for (Iterator<String> it = aliases.fieldNames(); it.hasNext(); ) {
            String alias = it.next();
            MemoryOp expected = MemoryOp.valueOf(aliases.get(alias).asText());
            assertEquals(expected, MemoryOp.of(alias),
                    "MemoryOp.of(\"" + alias + "\") 与 contract/vocabulary.json 不一致");
            assertEquals(expected, MemoryOp.of(alias.toUpperCase()),
                    "MemoryOp.of 应大小写不敏感：" + alias);
        }
        MemoryOp dflt = MemoryOp.valueOf(root.get("operation_default").asText());
        assertEquals(dflt, MemoryOp.of("没见过的"), "无法识别的操作应回落 operation_default");
        assertEquals(dflt, MemoryOp.of(null));
        assertEquals(dflt, MemoryOp.of(""));
        for (MemoryOp op : MemoryOp.values()) {
            assertEquals(op, MemoryOp.of(op.name()), "归一值应幂等：" + op);
        }
    }

    @Test
    void 契约文件本身可被定位() throws IOException {
        // 防呆：若工作目录策略变了导致上溯失败，应当在这里明确失败，而不是让上面三条静默跳过。
        assertTrue(Files.isRegularFile(vocabularyPath()), "contract/vocabulary.json 必须存在且可读");
        assertTrue(root().has("io_kind_vocabulary"), "契约文件必须含 io_kind_vocabulary 字段");
        assertTrue(root().has("io_kind_read"), "契约文件必须含 io_kind_read 字段");
        assertTrue(root().has("io_kind_write"), "契约文件必须含 io_kind_write 字段");
        assertTrue(root().has("operation_aliases"), "契约文件必须含 operation_aliases 字段");
        assertTrue(root().has("operation_default"), "契约文件必须含 operation_default 字段");
    }
}