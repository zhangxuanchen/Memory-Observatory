package io.memobservatory.exoskeleton.cluster;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * B 层「归因」的 v1 替身：把每个 turn 按行为形状归入可解释的粗分桶（§4.4）。
 *
 * <p>为什么不用 lab05 的三个语义簇（`single-file-fix` / `repo-summary` / `config-edit`）：
 * 那三个判据在真实事件流里不成立——工作台从不写 `layer='prompt'`（模型调用记 `layer='model'`），
 * 且每个 tool 结果与模型调用都记 `WRITE`，使「writes == 0」恒假。硬套的结果是两个簇恒为空、
 * 第三个退化成兜底桶、大量 turn 落进 `unclassified`（详见设计文档落地记录）。
 *
 * <p>v2（2026-10-06）起改用 {@code metadata.io_kind}（B4 埋点补强的产物）判定读写，
 * 不再借 {@code op='READ'} 这个注定含糊的代理：
 * <ul>
 *   <li>读类 = {@code file_read | content_search | memory_read}</li>
 *   <li>写类 = {@code file_write | memory_write}</li>
 * </ul>
 * 于是 §4.4 要的两条判据第一次真的取得到——「有没有工具读」与「是否只读未写」，
 * 而这两条的可靠性取决于 io_kind 的可靠性：工作台路径是真工具名（可靠），
 * 导入路径是 action 文本猜的（代理口径，见附录 B4.1/B4.2）。
 *
 * <p>仍然只报**原始支撑度分布**：桶人眼可读、可复现、可争论。等样本够了再考虑换语义簇。
 */
public final class ShapeBucketer {

    /** 分桶口径，随响应返回以便审计——明确它不是语义聚类。 */
    public static final List<String> DIMENSIONS = List.of(
            "shape：由 metadata.io_kind 归一（B4 埋点补强）——"
                    + "readonly = 有读类（file_read/content_search/memory_read）且无写类；"
                    + "readwrite = 读类写类都有；writeonly = 有写类无读类；"
                    + "none = 有 io_kind 但既无读类也无写类（只有模型调用与生命周期）；"
                    + "unknown = 该 turn 没有任何带 io_kind 的事件（B4 之前的老数据）",
            "steps：turn 主事件的 metadata.action_count；分 1-3 / 4-10 / 11+ 三档，缺主事件记 unknown");

    // ---- shape 取值（封闭词表） ----
    public static final String READONLY = "readonly";
    public static final String READWRITE = "readwrite";
    public static final String WRITEONLY = "writeonly";
    public static final String NONE = "none";
    /** B4 之前的老数据：一个 io_kind 都没有。与 {@link #NONE} 是两回事——那是「判过，没有读写」。 */
    public static final String UNKNOWN = "unknown";

    private static final List<String> SHAPES =
            List.of(READONLY, READWRITE, WRITEONLY, NONE, UNKNOWN);
    private static final List<String> STEP_BANDS = List.of("1-3", "4-10", "11+", "unknown");

    /** 读类三项，与 mo-server 的 {@code IoKind.READ_KINDS} 逐字一致。 */
    private static final Set<String> READ_KINDS =
            Set.of("file_read", "content_search", "memory_read");
    /** 写类两项，与 mo-server 的 {@code IoKind.WRITE_KINDS} 逐字一致。 */
    private static final Set<String> WRITE_KINDS = Set.of("file_write", "memory_write");

    /**
     * 读 {@code memory_turns.io_kind_mix} 的键集。支撑度侧与判据侧必须用同一片段，
     * 否则两个报表会按不同的口径分桶。{@code io_kind_mix} 为 NULL 时返回空数组（→ unknown）。
     */
    public static final String SQL_IO_KINDS =
            "COALESCE((SELECT array_agg(k) FROM jsonb_object_keys(io_kind_mix) AS k), ARRAY[]::text[])";

    /** 固定桶序（两维交叉、含空桶）：报表按此顺序展示，位置不随数据变化。 */
    public static final List<String> BUCKET_ORDER = buildOrder();

    private ShapeBucketer() {
    }

    /** 一个 turn 的行为形状特征（由 memory_turns.io_kind_mix 与 action_seq 得来）。 */
    public record TurnFeatures(
            Set<String> ioKinds,   // 该 turn 出现过的 io_kind；空集 = 一个都没有（老数据）
            Long steps             // metadata.action_count；字段缺失为 null
    ) {
        public TurnFeatures {
            ioKinds = ioKinds == null ? Set.of() : Set.copyOf(ioKinds);
        }
    }

    /** SQL 取回的 text[] 列 → 键集。列缺失 / null / 脏值一律按空集（→ unknown），不打断整批。 */
    public static Set<String> kindsOf(java.sql.Array array) {
        if (array == null) {
            return Set.of();
        }
        try {
            return kindsOf((String[]) array.getArray());
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** SQL 取回的键数组 → 键集；null 与空值都按空集处理，不打断整批。 */
    public static Set<String> kindsOf(String[] keys) {
        if (keys == null || keys.length == 0) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                out.add(k);
            }
        }
        return out;
    }

    /** 桶键，形如 {@code readonly|steps:4-10}。 */
    public static String bucketKey(TurnFeatures f) {
        return shape(f.ioKinds()) + "|steps:" + stepBand(f.steps());
    }

    /** 有读类且无写类 = 只读未写（§4.4 的第三条维度）。 */
    static String shape(Set<String> kinds) {
        if (kinds.isEmpty()) {
            return UNKNOWN;
        }
        boolean read = containsAny(kinds, READ_KINDS);
        boolean write = containsAny(kinds, WRITE_KINDS);
        if (read && write) {
            return READWRITE;
        }
        if (read) {
            return READONLY;
        }
        return write ? WRITEONLY : NONE;
    }

    private static boolean containsAny(Set<String> kinds, Set<String> candidates) {
        for (String k : kinds) {
            if (candidates.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String stepBand(Long steps) {
        if (steps == null) {
            return "unknown";
        }
        if (steps <= 3) {
            return "1-3";
        }
        if (steps <= 10) {
            return "4-10";
        }
        return "11+";
    }

    private static List<String> buildOrder() {
        List<String> out = new ArrayList<>();
        for (String shape : SHAPES) {
            for (String band : STEP_BANDS) {
                out.add(shape + "|steps:" + band);
            }
        }
        return List.copyOf(out);
    }
}
