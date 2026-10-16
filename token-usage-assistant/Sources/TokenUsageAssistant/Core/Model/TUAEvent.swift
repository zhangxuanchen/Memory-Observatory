import Foundation

/// 归一化后的 turn 级事件（DESIGN §5.2）。
/// 解析口径：宽松读取，缺字段不猜；`payload` 原样留档整条 JSON。
struct TUAEvent {
    var eventUid: String
    var ts: Int64
    var ide: String?
    var model: String?
    var workspaceId: String?
    var workspaceRoot: String?
    var sessionId: String?
    var turnId: String?
    var eventName: String?
    var ioKind: String?
    var toolName: String?
    var actionIdx: Int?
    var credits: Double?
    var tokenIn: Int?
    var tokenOut: Int?
    var tokenCache: Int?
    var tokenSource: String?
    var latencyMs: Int?
    var status: String?
    /// 用户提问原文（仅 UserPromptSubmit / Qoder Stop 带；落 `turns.user_prompt`）。
    var prompt: String?
    /// 一行人类可读详情（工具命令/路径、提问原文、「Turn 结束」等），供实时事件流展示。
    var detail: String?
    var payloadJSON: String?

    static func from(_ dict: [String: Any]) -> TUAEvent? {
        let ts = int64(dict, "ts") ?? nowMillis()
        let ide = string(dict, "ide")
        let sessionId = string(dict, "session_id")
        let turnId = string(dict, "turn_id")
        let eventName = string(dict, "event_name")
        let toolName = string(dict, "tool_name")
        let actionIdx = int(dict, "action_idx")

        var workspaceRoot = string(dict, "workspace_root")
        var workspaceId = string(dict, "workspace_id")
        if workspaceId == nil, let root = workspaceRoot {
            workspaceId = Workspace.deriveId(root: root)
        }
        if workspaceRoot == nil, workspaceId == nil {
            // 无归属信息也允许入库（保持「不猜」纪律，仅记为无工作区）
            workspaceRoot = nil
        }

        let uid = string(dict, "event_uid") ?? derivedUid(
            ide: ide, sessionId: sessionId, turnId: turnId,
            eventName: eventName, toolName: toolName, actionIdx: actionIdx, ts: ts
        )

        let payloadJSON = (try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) }

        return TUAEvent(
            eventUid: uid,
            ts: ts,
            ide: ide,
            model: string(dict, "model"),
            workspaceId: workspaceId,
            workspaceRoot: workspaceRoot,
            sessionId: sessionId,
            turnId: turnId,
            eventName: eventName,
            ioKind: string(dict, "io_kind"),
            toolName: toolName,
            actionIdx: actionIdx,
            credits: double(dict, "credits"),
            tokenIn: int(dict, "token_in"),
            tokenOut: int(dict, "token_out"),
            tokenCache: int(dict, "token_cache"),
            tokenSource: string(dict, "token_source"),
            latencyMs: int(dict, "latency_ms"),
            status: string(dict, "status"),
            prompt: string(dict, "prompt"),
            detail: string(dict, "detail"),
            payloadJSON: payloadJSON
        )
    }

    /// 由当前字段重算幂等键。**必须在 turn_id 确定后调用**（App 侧切分轮次之后），
    /// 否则同会话不同轮次的事件会撞键被误去重。
    ///
    /// `excludeTurn`：`UserPromptSubmit` 用它开启新轮，轮次号依赖已入库计数——
    /// 同一事件被重复投递时计数会变（→ 轮次号变 → 幂等键变 → 误判为新事件，生成幽灵轮次）。
    /// 故对开启轮次的事件**不含 turn_id** 参与哈希，使重投幂等。
    mutating func assignUid(excludeTurn: Bool = false) {
        let parts = [ide, excludeTurn ? nil : turnId, sessionId, eventName, toolName,
                     actionIdx.map(String.init), String(ts)]
            .compactMap { $0 }
        eventUid = "ev_" + Workspace.hash(parts.joined(separator: "|"))
    }

    private static func derivedUid(ide: String?, sessionId: String?, turnId: String?,
                                   eventName: String?, toolName: String?, actionIdx: Int?,
                                   ts: Int64) -> String {
        let parts = [ide, sessionId, turnId, eventName, toolName, actionIdx.map(String.init), String(ts)]
            .compactMap { $0 }
        let composite = parts.isEmpty ? String(ts) : parts.joined(separator: "|")
        return "ev_" + Workspace.hash(composite)
    }

    // MARK: - 宽松取值

    private static func string(_ dict: [String: Any], _ key: String) -> String? {
        if let value = dict[key] as? String, !value.isEmpty { return value }
        return nil
    }

    private static func int(_ dict: [String: Any], _ key: String) -> Int? {
        if let value = dict[key] as? Int { return value }
        if let value = dict[key] as? Double { return Int(value) }
        if let value = dict[key] as? String { return Int(value) }
        return nil
    }

    private static func int64(_ dict: [String: Any], _ key: String) -> Int64? {
        if let value = dict[key] as? Int64 { return value }
        if let value = dict[key] as? Int { return Int64(value) }
        if let value = dict[key] as? Double { return Int64(value) }
        if let value = dict[key] as? String { return Int64(value) }
        return nil
    }

    private static func double(_ dict: [String: Any], _ key: String) -> Double? {
        if let value = dict[key] as? Double { return value }
        if let value = dict[key] as? Int { return Double(value) }
        if let value = dict[key] as? String { return Double(value) }
        return nil
    }

    static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// 工作区身份（DESIGN §5.1）。
enum Workspace {
    /// 由根路径派生稳定 id（djb2）。
    static func deriveId(root: String) -> String { "ws_" + hash(root) }

    static func hash(_ text: String) -> String {
        var value: UInt64 = 5381
        for byte in text.utf8 { value = (value &* 33) &+ UInt64(byte) }
        return String(value, radix: 16)
    }
}