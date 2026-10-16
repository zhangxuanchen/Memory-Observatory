package io.memobservatory.server.model;

/**
 * 记忆操作归一化枚举（P0 四种）。
 * 归一化通常已在 Python SDK 的 OTelSpanExporter 完成，写入 memory.op_normalized 属性；
 * Java 侧读取该属性，并兼容标准 memory.operation（add/update/compact 等）与原始操作名。
 *
 * <p>归一表是跨语言契约（R7）：唯一真相源为仓库根 {@code contract/vocabulary.json}
 * 的 {@code operation_aliases} / {@code operation_default}，由 {@code ContractVocabularyTest} 机器校验。
 * 改了任一侧就必须同步改另一侧，否则对应测试立即变红。
 */
public enum MemoryOp {
    READ,
    WRITE,
    UPDATE,
    EXPIRE;

    /**
     * 从字符串解析为归一化操作。
     * 优先匹配 P0 归一值；兼容 GenAI 标准值与 Python 原始操作名。
     *
     * 规则（与 contract/vocabulary.json 的 operation_aliases 逐字一致）：
     *   READ / RETRIEVE                  → READ
     *   UPDATE / COMPACT / CONSOLIDATE   → UPDATE
     *   EXPIRE / DELETE / FORGET         → EXPIRE
     *   WRITE / ADD / STORE / 默认        → WRITE
     */
    public static MemoryOp of(String s) {
        if (s == null || s.isBlank()) return WRITE;
        return switch (s.toUpperCase()) {
            case "READ", "RETRIEVE" -> READ;
            case "UPDATE", "COMPACT", "CONSOLIDATE" -> UPDATE;
            case "EXPIRE", "DELETE", "FORGET" -> EXPIRE;
            default -> WRITE; // WRITE / ADD / STORE / 无法识别
        };
    }
}
