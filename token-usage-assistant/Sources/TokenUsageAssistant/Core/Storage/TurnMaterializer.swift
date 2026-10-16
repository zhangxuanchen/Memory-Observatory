import Foundation

/// 轮次物化（DESIGN §10.5 / M2）。
///
/// **为什么在 App 侧切分**：Qoder 虽有 `request_set_id`，但只有部分事件携带，
/// 直接用会把一轮拆成两个键；且 hook 适配器应保持无状态（不写本地状态文件）。
/// 故统一在 App 侧按「`UserPromptSubmit` 开启新轮、下一轮 `UserPromptSubmit` 为界」切分，
/// 状态由 App（SQLite）持有。
final class TurnMaterializer {
    static let shared = TurnMaterializer()

    private let db = Database.shared

    /// 为缺失 `turn_id` 的事件补上轮次键，并在确定轮次后重算幂等键。
    ///
    /// 轮次号 = 「该会话已入库的 `UserPromptSubmit` 数 + 本批内更早的 `UserPromptSubmit` 数」。
    /// 已入库计数从 DB 读（跨 HTTP 批次正确），批内计数用 `localPrompts` 累加（同批正确）。
    /// 非 `UserPromptSubmit` 事件归属「最近一次已开启的轮次」，无则落 `#1`（首个 prompt 之前）。
    func assign(_ events: [TUAEvent]) -> [TUAEvent] {
        var output: [TUAEvent] = []
        var localPrompts: [String: Int] = [:]   // 本批内该会话已开启的轮次数

        for var event in events.sorted(by: { $0.ts < $1.ts }) {
            guard let ide = event.ide, let sid = event.sessionId, !sid.isEmpty else {
                event.assignUid()
                output.append(event)
                continue
            }
            let sessionKey = "\(ide)|\(sid)"

            if let existing = event.turnId, !existing.isEmpty {
                // 适配器已给定轮次键（如 WorkBuddy 按 requestId 切轮）：尊重之；
                // 若也已预置幂等键（如 Stop 锚点的稳定 uid）则不重算，避免随 action 增长换 uid。
                if event.eventUid.isEmpty { event.assignUid() }
                output.append(event)
                continue
            }

            let stored = db.promptSubmitCount(ide: ide, sessionId: sid)
            let local = localPrompts[sessionKey] ?? 0
            let turnNo: Int
            if event.eventName == "UserPromptSubmit" {
                turnNo = stored + local + 1
                localPrompts[sessionKey] = local + 1
            } else {
                turnNo = max(1, stored + local)
            }
            event.turnId = "\(ide):\(sid)#\(turnNo)"
            event.assignUid(excludeTurn: event.eventName == "UserPromptSubmit")
            output.append(event)
        }
        return output
    }

    /// 重算受影响轮次的汇总行（幂等）。
    func refreshTurns(_ events: [TUAEvent]) {
        var prompts: [String: String] = [:]
        var turnIds: Set<String> = []
        for event in events {
            guard let turnId = event.turnId, !turnId.isEmpty else { continue }
            turnIds.insert(turnId)
            if event.eventName == "UserPromptSubmit", let prompt = event.prompt, !prompt.isEmpty {
                prompts[turnId] = prompt
            }
        }
        for turnId in turnIds {
            db.refreshTurn(turnId: turnId, prompt: prompts[turnId])
        }
    }
}