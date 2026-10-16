import Foundation
import SQLite3

/// WorkBuddy 监控导入器（DESIGN §5 多来源；M5+）。
///
/// **为什么和 Qoder 走不同通道**：Qoder 有 IDE hook 主动 POST 到本机接收端；
/// WorkBuddy 不给 TUA 发事件，但它把行为落在本机两处，可直接读：
/// - `~/.workbuddy/audit-log/*.jsonl`：安全审计流，逐条**工具动作**
///   （command-safety / file-safety / WebFetch…），带 sessionId / requestId / toolCallId /
///   decision / timestamp(ms) / commandPreview。→ 归一成 `PostToolUse` 动作事件。
///   注意：写入进程存活期间，当日新动作先落 `audit-log/spool/*.jsonl` 暂存，轮转才并进
///   当日文件——两处都要读（只读顶层会错过「正在进行」的会话）。
/// - `~/.workbuddy/workbuddy.db`：`sessions`（cwd→工作区、model）+
///   `session_usage.credit_json`（`requestId → 积分`）。→ 每请求积分。
///
/// **轮次口径**：WorkBuddy 无 `UserPromptSubmit`/`Stop`，取 `requestId` 作 turn 键
/// （它是 agent 单次请求边界，P99≈166 动作，足以撑起膨胀/循环判定）；
/// 每组补一条合成 `Stop` 锚点：令该轮 `ended=true`（否则不进检出），并挂该请求的积分。
///
/// **诚实边界**：WorkBuddy 无真实 token 读数 → token 一律留空（不估算、不假装认识）；
/// 审计流里没有用户提问原文 → `user_prompt` 留空。
final class WorkBuddyImporter {
    static let shared = WorkBuddyImporter()

    private let home = NSHomeDirectory()
    private var auditDir: String { home + "/.workbuddy/audit-log" }
    private var dbPath: String { home + "/.workbuddy/workbuddy.db" }

    /// 单次导入的事件上限（防御异常膨胀的审计流；首次全量拉全部历史，留足余量）。
    private let maxEvents = 200_000

    private init() {}

    /// WorkBuddy 是否装了（审计目录存在即可尝试；缺库时上层静默跳过）。
    var isAvailable: Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: auditDir, isDirectory: &isDir) && isDir.boolValue
    }

    /// WorkBuddy 每开一个会话就在 `~/WorkBuddy` 下建一个以时间戳命名的临时目录
    /// （`2026-10-09-15-28-33`），不是用户真实工程。这类「任务」会话按**会话标题**
    /// 各自归一条（标题即 IDE 里看到的对话名），不再全并成一个「临时会话」桶把具体对话埋掉；
    /// 真实工程路径（oh / laya …）原样保留。无标题的才退回「临时会话」兼容桶。
    private func normalizeWorkspaceRoot(_ cwd: String, title: String?) -> String {
        let base = (cwd as NSString).lastPathComponent
        guard Self.isTimestampDir(base) else { return cwd }
        return home + "/WorkBuddy/任务/" + Self.taskWorkspaceName(title)
    }

    /// 会话标题→合法的工作区名（路径最后一段）：去首尾空白、去掉路径分隔符与换行、限长。
    private static func taskWorkspaceName(_ title: String?) -> String {
        guard var t = title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return "临时会话" }
        t = t.replacingOccurrences(of: "/", with: "·")
             .replacingOccurrences(of: "\\", with: "·")
             .replacingOccurrences(of: "\n", with: " ")
        if t.count > 60 { t = String(t.prefix(60)) }
        return t.isEmpty ? "临时会话" : t
    }

    /// 目录名是否形如 `YYYY-MM-DD-HH-MM-SS`（WorkBuddy 临时会话目录的命名）。
    private static func isTimestampDir(_ name: String) -> Bool {
        let p = Array(name)
        let dash: Set<Int> = [4, 7, 10, 13, 16]
        guard p.count == 19 else { return false }
        for i in 0..<19 {
            if dash.contains(i) {
                if p[i] != "-" { return false }
            } else if !p[i].isNumber {
                return false
            }
        }
        return true
    }

    /// 采集近 `days` 天的 WorkBuddy 数据，归一成 `[TUAEvent]`（含合成 Stop 锚点）。
    /// 产出直接喂进既有管线：`TurnMaterializer.assign → Database.insert → refreshTurns`。
    func collect(days: Int = 14) -> [TUAEvent] {
        guard isAvailable else { return [] }
        let cutoff = TUAEvent.nowMillis() - Int64(max(1, days)) * 86_400_000

        let sessions = loadSessions()          // sessionId → (cwd, model, workspaceId)
        let creditEntries = loadCreditEntries() // (sessionId, requestId → 积分)
        var credits: [String: Double] = [:]
        for e in creditEntries { credits[e.requestId] = e.credit }

        // 按 (sessionId, requestId) 聚合审计动作；保持插入序（文件按天升序、行内按时间升序）。
        var groups: [String: [AuditAction]] = [:]
        var order: [String] = []
        var total = 0
        for line in readAuditLines(cutoffMillis: cutoff) {
            guard let action = parseAction(line, cutoff: cutoff) else { continue }
            let key = action.sessionId + "\u{1}" + action.requestId
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(action)
            total += 1
            if total >= maxEvents { break }
        }

        var events: [TUAEvent] = []
        for key in order {
            guard let raw = groups[key], !raw.isEmpty else { continue }
            // spool 与当日文件可能先后各带一份同一动作（轮转后双份）：按内容去重，防 actionIdx 错位双计。
            var seen = Set<String>()
            let actions = raw.filter { seen.insert("\($0.ts)|\($0.tool)|\($0.status)|\($0.detail ?? "")").inserted }
            let parts = key.split(separator: "\u{1}", omittingEmptySubsequences: false)
            let sessionId = String(parts[0])
            let requestId = parts.count > 1 ? String(parts[1]) : ""
            let turnId = "workbuddy:\(sessionId)#\(requestId)"
            let meta = sessions[sessionId]

            var idx = 0
            var maxTs: Int64 = 0
            for action in actions.sorted(by: { $0.ts < $1.ts }) {
                maxTs = max(maxTs, action.ts)
                events.append(TUAEvent(
                    eventUid: "", ts: action.ts, ide: "workbuddy", model: meta?.model,
                    workspaceId: meta?.workspaceId, workspaceRoot: meta?.cwd,
                    sessionId: sessionId, turnId: turnId,
                    eventName: "PostToolUse", ioKind: action.ioKind, toolName: action.tool,
                    actionIdx: idx, credits: 0,
                    tokenIn: nil, tokenOut: nil, tokenCache: nil, tokenSource: nil,
                    latencyMs: nil, status: action.status,
                    prompt: nil, detail: action.detail, payloadJSON: nil
                ))
                idx += 1
            }
            // 合成 Stop 锚点：置 ended=true 并承载该请求积分（ts 取该轮末动作，保证 ended_at 正确）。
            // eventUid 固定按 (sessionId,requestId) 派生、不随 action_idx/ts 变——否则同一请求流式增长时
            // 每次轮询会生成不同 uid 的 Stop，多笔各带全额积分叠加成 N 倍（双计）。
            let credit = requestId.isEmpty ? nil : credits[requestId]
            let stopUid = requestId.isEmpty ? "" : "ev_wbstop_" + Workspace.hash(sessionId + "|" + requestId)
            events.append(TUAEvent(
                eventUid: stopUid, ts: maxTs, ide: "workbuddy", model: meta?.model,
                workspaceId: meta?.workspaceId, workspaceRoot: meta?.cwd,
                sessionId: sessionId, turnId: turnId,
                eventName: "Stop", ioKind: nil, toolName: nil,
                actionIdx: idx, credits: credit,
                tokenIn: nil, tokenOut: nil, tokenCache: nil, tokenSource: nil,
                latencyMs: nil, status: "ok",
                prompt: nil, detail: nil, payloadJSON: nil
            ))
        }

        // 纯聊天轮补建：有积分、但审计流里零工具动作的 requestId。
        // WorkBuddy 只在调用工具时写审计流，纯问答不留痕；这类轮靠 session_usage 的积分条目
        // + requestId 内嵌的时间戳还原，否则「今天只聊了天」的会话在 TUA 里彻底隐身。
        // 只认带精确时间戳的 UUIDv7 requestId；老/随机格式 id 无法定时间，不臆造（宁可漏历史也不造未来轮）。
        let covered = Set(order)   // 已有审计动作的 "sessionId\u{1}requestId"
        for entry in creditEntries {
            let key = entry.sessionId + "\u{1}" + entry.requestId
            if covered.contains(key) { continue }
            guard let ts = Self.timestampMillis(fromRequestId: entry.requestId), ts >= cutoff else { continue }
            let meta = sessions[entry.sessionId]
            // 与审计 Stop 同一稳定 uid 方案：若该请求后续长出工具动作（从 chat-only 转成审计路径），
            // 两路会撞同一 uid → 只留一条 Stop，不会各存一笔造成双计。
            events.append(TUAEvent(
                eventUid: "ev_wbstop_" + Workspace.hash(entry.sessionId + "|" + entry.requestId),
                ts: ts, ide: "workbuddy", model: meta?.model,
                workspaceId: meta?.workspaceId, workspaceRoot: meta?.cwd,
                sessionId: entry.sessionId, turnId: "workbuddy:\(entry.sessionId)#\(entry.requestId)",
                eventName: "Stop", ioKind: nil, toolName: nil,
                actionIdx: 0, credits: entry.credit,
                tokenIn: nil, tokenOut: nil, tokenCache: nil, tokenSource: nil,
                latencyMs: nil, status: "ok",
                prompt: nil, detail: nil, payloadJSON: nil
            ))
        }
        return events
    }

    // MARK: - 审计流解析

    private struct AuditAction {
        let ts: Int64
        let sessionId: String
        let requestId: String
        let tool: String
        let ioKind: String
        let status: String
        let detail: String?
    }

    /// 逐行读近 `days` 天的审计（文件名即日期 `YYYY-MM-DD.jsonl`，按名升序＝按时间升序），
    /// 并纳入 `spool/` 下当日暂存流——写入进程存活期间新动作只在 spool，不读就永远「慢一拍」。
    private func readAuditLines(cutoffMillis: Int64) -> [String] {
        let fm = FileManager.default
        let cutoffDay = dayString(cutoffMillis)
        var lines: [String] = []
        if let names = try? fm.contentsOfDirectory(atPath: auditDir) {
            for name in names.sorted() where name.hasSuffix(".jsonl") {
                let day = String(name.dropLast(6))            // "2026-10-13"
                // 文件名日期早于窗口起点的整份跳过（YYYY-MM-DD 字典序＝时间序）。
                if day.count == 10, day < cutoffDay { continue }
                lines.append(contentsOf: readLines(fromFile: auditDir + "/" + name))
            }
        }
        // spool：文件名形如 audit-spool-<pid>-<date>.jsonl；整份读入，窗口靠行级时间戳过滤（parseAction）。
        let spoolDir = auditDir + "/spool"
        if let names = try? fm.contentsOfDirectory(atPath: spoolDir) {
            for name in names.sorted() where name.hasSuffix(".jsonl") {
                lines.append(contentsOf: readLines(fromFile: spoolDir + "/" + name))
            }
        }
        return lines
    }

    private func readLines(fromFile path: String) -> [String] {
        guard let text = try? String(contentsOfFile: path, encoding: .utf8) else { return [] }
        var out: [String] = []
        text.enumerateLines { line, _ in
            if !line.isEmpty { out.append(line) }
        }
        return out
    }

    /// 解析一行审计事件 → 动作（无 sessionId/requestId 或不在窗口内的丢弃）。
    private func parseAction(_ line: String, cutoff: Int64) -> AuditAction? {
        guard let data = line.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        guard let ts = int64(obj["timestamp"]), ts >= cutoff else { return nil }
        guard let sessionId = str(obj["sessionId"]), !sessionId.isEmpty,
              let requestId = str(obj["requestId"]), !requestId.isEmpty else { return nil }

        let eventType = str(obj["eventType"]) ?? ""
        let category = str(obj["category"]) ?? ""
        let decision = str(obj["decision"]) ?? ""

        // 只认「工具动作」类；其余（config/approval/无 category 的封条行）跳过，不猜。
        let classified = classify(eventType: eventType, category: category)
        guard let tool = classified.tool else { return nil }

        let detail = detailText(obj: obj, category: category)
        let status = (decision == "rejected" || decision == "denied" || decision == "blocked") ? "failed" : "ok"

        return AuditAction(ts: ts, sessionId: sessionId, requestId: requestId,
                           tool: tool, ioKind: classified.ioKind, status: status, detail: detail)
    }

    /// eventType/category → (工具名, io_kind)。认不出的动作类返回 tool=nil（丢弃）。
    private func classify(eventType: String, category: String) -> (tool: String?, ioKind: String) {
        let et = eventType.lowercased()
        if et.contains("webfetch") || et.contains("web") { return ("WebFetch", "content_search") }
        if et == "bash" || category == "command-safety" { return ("Bash", "tool_other") }
        if category == "file-safety" {
            // 批量删除 / 写类动作归 file_write，其余文件访问归 file_read。
            let write = et.contains("delete") || et.contains("write") || et.contains("modified")
            return ("File", write ? "file_write" : "file_read")
        }
        if category == "network" { return ("WebFetch", "content_search") }
        return (nil, "tool_other")
    }

    /// 一行人类可读详情：命令预览 / messageParams.command / 目标路径，截断留痕。
    private func detailText(obj: [String: Any], category: String) -> String? {
        if let preview = str(obj["commandPreview"]), !preview.isEmpty {
            return String(preview.prefix(200))
        }
        if let params = obj["messageParams"] as? [String: Any] {
            if let command = str(params["command"]), !command.isEmpty {
                return String(command.prefix(200))
            }
            if let target = str(params["targets"]) ?? str(params["path"]), !target.isEmpty {
                return String(target.prefix(200))
            }
        }
        return nil
    }

    // MARK: - workbuddy.db 只读

    private struct SessionMeta { let cwd: String?; let model: String?; let workspaceId: String? }

    /// sessionId → (cwd→workspaceId, model)。库缺失/被占用时返回空表（用量事件仍可入库，仅无工作区归属）。
    private func loadSessions() -> [String: SessionMeta] {
        guard let db = openReadonly() else { return [:] }
        defer { sqlite3_close(db) }
        var map: [String: SessionMeta] = [:]
        let sql = "SELECT id, cwd, model, COALESCE(NULLIF(custom_title, ''), title) FROM sessions;"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [:] }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            guard let id = text(stmt, 0) else { continue }
            let model = text(stmt, 2)
            // 时间戳临时目录按会话标题命名工作区；真实工程路径不动。
            let title = text(stmt, 3)
            let root = text(stmt, 1).map { normalizeWorkspaceRoot($0, title: title) }
            map[id] = SessionMeta(
                cwd: root, model: (model?.isEmpty == false) ? model : nil,
                workspaceId: root.map { Workspace.deriveId(root: $0) }
            )
        }
        return map
    }

    /// session_usage 里每条 (sessionId, requestId → 积分)。既用于给审计轮挂积分，
    /// 也用于补建「纯聊天、零工具动作」的轮（这类在审计流里没有任何记录）。
    private struct CreditEntry { let sessionId: String; let requestId: String; let credit: Double }

    private func loadCreditEntries() -> [CreditEntry] {
        guard let db = openReadonly() else { return [] }
        defer { sqlite3_close(db) }
        var out: [CreditEntry] = []
        var stmt: OpaquePointer?
        let sql = "SELECT session_id, credit_json FROM session_usage WHERE credit_json IS NOT NULL AND credit_json <> '';"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            guard let sid = text(stmt, 0), let json = text(stmt, 1), let data = json.data(using: .utf8),
                  let dict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            for (requestId, value) in dict {
                if let credit = double(value) { out.append(CreditEntry(sessionId: sid, requestId: requestId, credit: credit)) }
            }
        }
        return out
    }

    /// 仅从**真正的 UUIDv7** requestId 解出毫秒时间戳：前 12 个 hex 位是 48bit 时间戳，
    /// 第 13 位（version）必须为 '7'、第 17 位（variant）∈ {8,9,a,b}。
    /// WorkBuddy 另有一批老/随机 hex 格式的 requestId 不满足该结构——它们的头 12 位是纯随机数，
    /// 盲解会造出 2071 之类的未来轮，故这里严格判结构，非 v7 一律返回 nil（由调用方退回会话活跃时间）。
    private static func timestampMillis(fromRequestId rid: String) -> Int64? {
        guard rid.count >= 32, rid.allSatisfy({ $0.isHexDigit }) else { return nil }
        let c = Array(rid)
        guard c[12] == "7", c[16] == "8" || c[16] == "9" || c[16] == "a" || c[16] == "b" else { return nil }
        guard let ms = Int64(String(c[0..<12]), radix: 16) else { return nil }
        guard ms >= 1_577_836_800_000, ms <= TUAEvent.nowMillis() else { return nil }   // 不早于 2020、不晚于当下
        return ms
    }

    private func openReadonly() -> OpaquePointer? {
        var handle: OpaquePointer?
        guard FileManager.default.fileExists(atPath: dbPath) else { return nil }
        if sqlite3_open_v2(dbPath, &handle, SQLITE_OPEN_READONLY, nil) != SQLITE_OK {
            sqlite3_close(handle)
            return nil
        }
        return handle
    }

    // MARK: - 宽松取值

    private func int64(_ value: Any?) -> Int64? {
        if let v = value as? Int64 { return v }
        if let v = value as? Int { return Int64(v) }
        if let v = value as? Double { return Int64(v) }
        if let v = value as? String { return Int64(v) }
        return nil
    }

    private func double(_ value: Any?) -> Double? {
        if let v = value as? Double { return v }
        if let v = value as? Int { return Double(v) }
        if let v = value as? String { return Double(v) }
        return nil
    }

    private func str(_ value: Any?) -> String? {
        if let v = value as? String, !v.isEmpty { return v }
        return nil
    }

    private func text(_ stmt: OpaquePointer?, _ index: Int32) -> String? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL,
              let cString = sqlite3_column_text(stmt, index) else { return nil }
        return String(cString: cString)
    }

    private func dayString(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
