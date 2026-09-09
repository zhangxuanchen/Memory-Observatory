package io.memobservatory.server.model;

/**
 * 记忆操作归一化枚举（P0 四种）。
 * 归一化在 Python SDK 的 OTelSpanExporter 完成，写入 memory.op_normalized 属性；
 * Java 侧读取该属性，兼容标准 memory.operation（add/update/compact 等）。
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
     * 规则：
     *   READ / RETRIEVE        → READ
     *   UPDATE                → UPDATE
     *   EXPIRE / DELETE / FORGET → EXPIRE
     *   WRITE / ADD / STORE / 默认 → WRITE
     */
    public static MemoryOp of(String s) {
        if (s == null || s.isBlank()) return WRITE;
        return switch (s.toUpperCase()) {
            case "READ", "RETRIEVE" -> READ;
            case "UPDATE", "COMPACT" -> UPDATE; // consolidate/compact 归为 UPDATE
            case "EXPIRE", "DELETE", "FORGET" -> EXPIRE;
            default -> WRITE; // WRITE / ADD / STORE
        };
    }
}
