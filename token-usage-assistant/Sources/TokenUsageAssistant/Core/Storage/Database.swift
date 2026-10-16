import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

struct IngestResult {
    var inserted = 0
    var deduped = 0
}

struct TodayStats {
    /// 按 Agent 隔离的统计（D14：绝不跨 Agent 合计积分）。
    var agents: [AgentStats] = []
    /// 事件总数（中性计数，不涉及计费，可合计）。
    var events: Int = 0
    var workspaces: Int = 0
}

/// 单个 Agent 的今日统计。`credits` 为该 Agent 自身单位，**不可与其他 Agent 相加**。
struct AgentStats: Identifiable {
    let id: String
    var credits: Double = 0
    var turns: Int = 0
    var events: Int = 0

    var label: String { id.isEmpty ? "未知" : id }
}

/// `Agent × 模型` 两级的积分构成（D15）。
struct ModelStats: Identifiable {
    let ide: String
    let model: String
    var credits: Double = 0
    var events: Int = 0

    var id: String { "\(ide)|\(model)" }
    var modelLabel: String { model.isEmpty ? "未知模型" : model }
}

/// 单个 turn 的汇总（异常检出用）。
struct TurnSummary: Identifiable {
    let turnId: String
    let ide: String
    let model: String?
    let workspaceId: String?
    let workspaceName: String?
    let startedAt: Int64
    let endedAt: Int64
    let actionCount: Int
    let credits: Double
    let latencyMs: Int
    /// 该轮最近一次事件的 ts（判定「进行中」是否已僵死）。
    let lastEventTs: Int64
    /// 是否已收到 `Stop`（该 Agent 的 turn 结束标记）。
    let ended: Bool

    var id: String { turnId }
    var workspaceLabel: String { (workspaceName?.isEmpty == false) ? workspaceName! : "未知工作区" }
    var elapsedMs: Int { max(0, Int(endedAt - startedAt)) }
}

/// 某 Agent 今日逐小时积分（迷你趋势线用）。索引 = 当日第几小时。
struct AgentTrend: Identifiable {
    let id: String
    let hours: [Double]
}

/// 工作区今日积分（**按 Agent 隔离**，D14：绝不跨 Agent 合计）。
struct WorkspaceStats: Identifiable {
    let ide: String
    let workspaceId: String
    let name: String
    var credits: Double
    var turns: Int
    /// 该工作区最近一次事件时间（毫秒）；0 = 未知。
    var lastTs: Int64 = 0
    /// WorkBuddy 语义：true = 临时任务（`~/WorkBuddy/任务/<标题>`），false = 真实工程「空间」。
    /// 其它 Agent（qoder 等）恒为 false，UI 不展示该标签。
    var isTask: Bool = false

    var id: String { "\(ide)|\(workspaceId)" }
}

/// 工作区内单个会话的摘要（展示会话数 + 每会话轮次 + 首条提问详情）。
struct SessionSummary: Identifiable {
    let sessionId: String
    let workspaceId: String
    let turnCount: Int
    let prompt: String?
    var id: String { "\(workspaceId)|\(sessionId)" }
}

struct EventRow: Identifiable {
    let id: Int64
    let ts: Int64
    let ide: String?
    let model: String?
    let eventName: String?
    let toolName: String?
    let ioKind: String?
    let credits: Double
    let latencyMs: Int?
    let status: String?
    /// 一行人类可读详情（工具命令/路径、提问原文、「Turn 结束」）；旧数据可能为 nil。
    let detail: String?
    /// 所属工作区名（JOIN workspaces 得出；无归属为 nil）。
    let workspaceName: String?
    /// 所属会话 id（事件行上展示 #前缀 标签）。
    let sessionId: String?

    var sourceLabel: String { ide ?? "-" }
    var actionLabel: String { toolName ?? eventName ?? "-" }
}

/// 落库后读回的异常（供主面板「异常」页展示；JOIN turns/workspaces 补展示字段）。
struct FindingRow: Identifiable {
    let id: Int64
    let scope: String
    let refId: String
    let kind: String
    let severity: String
    let turnId: String
    let ide: String
    let model: String?
    let workspaceId: String?
    let workspaceName: String
    let credits: Double
    let latencyMs: Int
    let startedAt: Int64
    let evidence: [String: Any]

    var kindLabel: String { AnomalyDetector.kindLabel(kind) }
    var isHigh: Bool { severity == "high" }
    var workspaceLabel: String { workspaceName.isEmpty ? "未知工作区" : workspaceName }
}

/// 任务明细行（`turns` JOIN `workspaces`）。
struct TurnRow: Identifiable {
    let turnId: String
    let ide: String
    let model: String?
    let workspaceId: String?
    let workspaceName: String
    let prompt: String?
    let startedAt: Int64
    let endedAt: Int64
    let actionCount: Int
    let credits: Double
    let latencyTotalMs: Int
    let latencyMaxMs: Int
    let outcome: String?
    let ended: Bool

    var id: String { turnId }
    var workspaceLabel: String { workspaceName.isEmpty ? "未知工作区" : workspaceName }
}

/// 工作区维度综合统计（`workspaceAggregate` 返回，零 LLM 纯聚合）。
struct WorkspaceAggregate {
    var turns = 0
    var actions = 0
    var credits = 0.0
    var latencyMs: Int64 = 0
    var tokenIn: Int64 = 0
    var tokenOut: Int64 = 0
}

/// 单 Agent 的延迟 / 积分分位（D14：仅在单个 Agent 内计算）。
struct AgentLatencyStats: Identifiable {
    let ide: String
    let turns: Int
    let p50: Int
    let p90: Int
    let p95: Int
    let p99: Int
    let creditP95: Double

    var id: String { ide }
    var label: String { ide.isEmpty ? "未知" : ide }
}

/// 本地 SQLite（系统 libsqlite3，零外部依赖）。schema 对齐 DESIGN §5。
final class Database {
    static let shared = Database()

    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "tua.database")

    private init() {
        let url = AppConfig.supportDirectory.appendingPathComponent("tua.sqlite")
        if sqlite3_open(url.path, &handle) != SQLITE_OK {
            NSLog("TUA: 无法打开数据库 \(url.path)")
        }
        tightenFilePermissions(dbURL: url)
        queue.sync {
            execLocked(Self.schema)
            migrateLocked()
        }
        redactExistingSecretsIfNeeded()
    }

    /// 库文件只归当前用户读写（sqlite 默认按 umask 建成 0644，同机其他账号能读到原始命令/提问）。
    /// 顺带处理 WAL/SHM 旁文件。
    private func tightenFilePermissions(dbURL: URL) {
        let fm = FileManager.default
        for suffix in ["", "-wal", "-shm"] {
            let p = dbURL.path + suffix
            if fm.fileExists(atPath: p) {
                try? fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p)
            }
        }
    }

    // MARK: - 迁移

    /// 已存在的旧库不会因 `CREATE TABLE IF NOT EXISTS` 新增列，这里显式补齐（幂等）。
    private func migrateLocked() {
        if !columnExists(table: "events", column: "model") {
            execLocked("ALTER TABLE events ADD COLUMN model TEXT;")
        }
        if !columnExists(table: "events", column: "detail") {
            execLocked("ALTER TABLE events ADD COLUMN detail TEXT;")
        }
        if !columnExists(table: "turns", column: "model") {
            execLocked("ALTER TABLE turns ADD COLUMN model TEXT;")
        }
        if !columnExists(table: "guard_log", column: "stage") {
            execLocked("ALTER TABLE guard_log ADD COLUMN stage TEXT;")
        }
        if !columnExists(table: "guard_log", column: "prompt") {
            execLocked("ALTER TABLE guard_log ADD COLUMN prompt TEXT;")
        }
        // 跨改写目标可复用：两张表加 target 维度，旧行归到 prompt.precheck。
        if !columnExists(table: "eval_cases", column: "target") {
            execLocked("ALTER TABLE eval_cases ADD COLUMN target TEXT;")
            execLocked("UPDATE eval_cases SET target = 'prompt.precheck' WHERE target IS NULL;")
        }
        if !columnExists(table: "adoption_events", column: "target") {
            execLocked("ALTER TABLE adoption_events ADD COLUMN target TEXT;")
            execLocked("UPDATE adoption_events SET target = 'prompt.precheck' WHERE target IS NULL;")
        }
    }

    /// 一次性把历史行里已存的凭据值抹掉（入库脱敏上线前写入的老数据）。
    /// 用 UserDefaults 标记，仅跑一次；新数据已在各写入点实时脱敏。
    private func redactExistingSecretsIfNeeded() {
        let flag = "tua.secretRedaction.v1"
        if UserDefaults.standard.bool(forKey: flag) { return }
        queue.sync {
            execLocked("BEGIN IMMEDIATE;")
            redactTable("events", cols: ["detail", "payload"])
            redactTable("turns", cols: ["user_prompt"])
            redactTable("analyses", cols: ["what", "why", "impact", "fix", "raw"])
            execLocked("COMMIT;")
        }
        UserDefaults.standard.set(true, forKey: flag)
    }

    /// 逐行读取指定文本列，套 SecretRedactor；仅对有变化的行回写。先收集再写，避免读游标时写库。
    /// 用 `rowid` 定位（各表都是普通行存表，rowid 恒为整型），避开事件表 INTEGER 主键被当文本绑定而匹配不上。
    private func redactTable(_ table: String, cols: [String]) {
        let select = "SELECT rowid, \(cols.joined(separator: ", ")) FROM \(table);"
        var read: OpaquePointer?
        guard sqlite3_prepare_v2(handle, select, -1, &read, nil) == SQLITE_OK else { return }
        var updates: [(rowId: Int64, values: [String])] = []
        while sqlite3_step(read) == SQLITE_ROW {
            let rowId = sqlite3_column_int64(read, 0)
            var changed = false
            var newVals: [String] = []
            for i in 0..<cols.count {
                let raw = columnText(read, Int32(i + 1)) ?? ""
                let redacted = SecretRedactor.redact(raw)
                if redacted != raw { changed = true }
                newVals.append(redacted)
            }
            if changed { updates.append((rowId, newVals)) }
        }
        sqlite3_finalize(read)
        guard !updates.isEmpty else { return }
        let setClause = cols.map { "\($0) = ?" }.joined(separator: ", ")
        let sql = "UPDATE \(table) SET \(setClause) WHERE rowid = ?;"
        var upd: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &upd, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(upd) }
        for (rowId, vals) in updates {
            sqlite3_reset(upd); sqlite3_clear_bindings(upd)
            for (i, v) in vals.enumerated() { bind(upd, Int32(i + 1), v) }
            sqlite3_bind_int64(upd, Int32(vals.count + 1), rowId)
            sqlite3_step(upd)
        }
    }

    private func columnExists(table: String, column: String) -> Bool {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "PRAGMA table_info(\(table));", -1, &stmt, nil) == SQLITE_OK else {
            return false
        }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let name = columnText(stmt, 1), name == column { return true }
        }
        return false
    }

    // MARK: - Schema

    private static let schema = """
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS workspaces (
        id TEXT PRIMARY KEY,
        root_path TEXT,
        name TEXT,
        ide TEXT,
        source TEXT
    );
    CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        event_uid TEXT UNIQUE,
        ts INTEGER NOT NULL,
        ide TEXT,
        model TEXT,
        workspace_id TEXT,
        session_id TEXT,
        turn_id TEXT,
        event_name TEXT,
        io_kind TEXT,
        tool_name TEXT,
        action_idx INTEGER,
        credits REAL DEFAULT 0,
        token_in INTEGER DEFAULT 0,
        token_out INTEGER DEFAULT 0,
        token_cache INTEGER DEFAULT 0,
        token_source TEXT,
        latency_ms INTEGER,
        status TEXT,
        payload TEXT,
        detail TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
    CREATE INDEX IF NOT EXISTS idx_events_turn ON events(turn_id);
    CREATE INDEX IF NOT EXISTS idx_events_workspace ON events(workspace_id);
    CREATE TABLE IF NOT EXISTS turns (
        turn_id TEXT PRIMARY KEY,
        workspace_id TEXT,
        session_id TEXT,
        ide TEXT,
        model TEXT,
        user_prompt TEXT,
        started_at INTEGER,
        ended_at INTEGER,
        action_count INTEGER DEFAULT 0,
        credits_total REAL DEFAULT 0,
        token_total INTEGER DEFAULT 0,
        token_in INTEGER DEFAULT 0,
        token_out INTEGER DEFAULT 0,
        token_cache INTEGER DEFAULT 0,
        latency_total_ms INTEGER DEFAULT 0,
        latency_max_ms INTEGER DEFAULT 0,
        outcome TEXT,
        flags TEXT
    );
    CREATE TABLE IF NOT EXISTS findings (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        scope TEXT,
        ref_id TEXT,
        kind TEXT,
        severity TEXT,
        evidence TEXT,
        created_at INTEGER
    );
    CREATE UNIQUE INDEX IF NOT EXISTS idx_findings_key ON findings(scope, ref_id, kind);
    CREATE TABLE IF NOT EXISTS suggestions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        finding_id INTEGER,
        source TEXT,
        title TEXT,
        reason TEXT,
        evidence TEXT,
        impact TEXT,
        action_kind TEXT,
        payload TEXT,
        status TEXT DEFAULT 'new'
    );
    CREATE TABLE IF NOT EXISTS habits (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        kind TEXT,
        window_start INTEGER,
        window_end INTEGER,
        hit_count INTEGER DEFAULT 0,
        hit_ratio REAL DEFAULT 0,
        wasted_credit_est REAL DEFAULT 0,
        evidence TEXT,
        severity TEXT,
        created_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS analyses (
        turn_id TEXT PRIMARY KEY,
        what TEXT,
        why TEXT,
        impact TEXT,
        fix TEXT,
        raw TEXT,
        model TEXT,
        created_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS guard_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        session_id TEXT,
        turn_id TEXT,
        ide TEXT,
        tool_name TEXT,
        rule TEXT,
        decision TEXT,
        reason TEXT,
        evidence TEXT,
        user_choice TEXT,
        stage TEXT,
        prompt TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_guard_turn ON guard_log(turn_id);
    CREATE INDEX IF NOT EXISTS idx_guard_ts ON guard_log(ts);
    CREATE TABLE IF NOT EXISTS prompt_corpus (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        src_uid TEXT UNIQUE,
        ts INTEGER NOT NULL,
        ide TEXT,
        session_id TEXT,
        workspace_id TEXT,
        workspace_root TEXT,
        prompt TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_corpus_ts ON prompt_corpus(ts);
    CREATE INDEX IF NOT EXISTS idx_corpus_workspace ON prompt_corpus(workspace_id);
    CREATE TABLE IF NOT EXISTS adoption_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        source TEXT,
        target TEXT,
        ide TEXT,
        session_id TEXT,
        workspace_id TEXT,
        original_hash TEXT,
        original_prompt TEXT,
        shown_candidates TEXT,
        chosen_index INTEGER,
        chosen_prompt TEXT,
        action TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_adoption_ts ON adoption_events(ts);
    CREATE INDEX IF NOT EXISTS idx_adoption_version ON adoption_events(action);
    CREATE TABLE IF NOT EXISTS eval_cases (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        src_uid TEXT UNIQUE,
        target TEXT,
        prompt TEXT NOT NULL,
        workspace_id TEXT,
        ide TEXT,
        session_id TEXT,
        problems TEXT,
        readiness_before REAL,
        tags TEXT,
        cohort TEXT,
        frozen INTEGER DEFAULT 1,
        added_ts INTEGER
    );
    CREATE INDEX IF NOT EXISTS idx_evalcohort ON eval_cases(cohort);
    CREATE INDEX IF NOT EXISTS idx_evaltarget ON eval_cases(target, cohort);
    CREATE TABLE IF NOT EXISTS gold_rewrites (
        case_id INTEGER PRIMARY KEY,
        gold_text TEXT,
        written_by TEXT,
        written_ts INTEGER
    );
    CREATE TABLE IF NOT EXISTS preference_pairs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        case_id INTEGER,
        cand_a TEXT,
        cand_b TEXT,
        ver_a TEXT,
        ver_b TEXT,
        judge TEXT,
        choice TEXT,
        judged_ts INTEGER
    );
    CREATE INDEX IF NOT EXISTS idx_pref_case ON preference_pairs(case_id);
    """

    // MARK: - 写入

    /// 幂等入库：`event_uid` 冲突视为同一事件重投。事件本体不可变，但 **credits 例外**——
    /// WorkBuddy 一笔请求的消耗会随流式收尾被重算（初值→终值），故冲突时只刷新 credits，其余列不覆盖。
    /// （qoder 重投同事件带同值，此更新为无害空转。）
    func insert(events: [TUAEvent]) -> IngestResult {
        queue.sync {
            var result = IngestResult()
            execLocked("BEGIN IMMEDIATE;")
            let sql = """
            INSERT INTO events
                (event_uid, ts, ide, model, workspace_id, session_id, turn_id, event_name, io_kind,
                 tool_name, action_idx, credits, token_in, token_out, token_cache, token_source,
                 latency_ms, status, payload, detail)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(event_uid) DO UPDATE SET credits = excluded.credits;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
                execLocked("ROLLBACK;")
                NSLog("TUA: 预编译 events 插入失败")
                return result
            }
            for event in events {
                if let workspaceId = event.workspaceId {
                    upsertWorkspace(event: event, id: workspaceId)
                }
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
                bind(stmt, 1, event.eventUid)
                sqlite3_bind_int64(stmt, 2, event.ts)
                bind(stmt, 3, event.ide)
                bind(stmt, 4, event.model)
                bind(stmt, 5, event.workspaceId)
                bind(stmt, 6, event.sessionId)
                bind(stmt, 7, event.turnId)
                bind(stmt, 8, event.eventName)
                bind(stmt, 9, event.ioKind)
                bind(stmt, 10, event.toolName)
                bind(stmt, 11, event.actionIdx)
                bind(stmt, 12, event.credits)
                bind(stmt, 13, event.tokenIn)
                bind(stmt, 14, event.tokenOut)
                bind(stmt, 15, event.tokenCache)
                bind(stmt, 16, event.tokenSource)
                bind(stmt, 17, event.latencyMs)
                bind(stmt, 18, event.status)
                bind(stmt, 19, SecretRedactor.redactOptional(event.payloadJSON))
                bind(stmt, 20, SecretRedactor.redactOptional(event.detail))
                if sqlite3_step(stmt) == SQLITE_DONE {
                    if sqlite3_changes(handle) > 0 { result.inserted += 1 } else { result.deduped += 1 }
                }
            }
            sqlite3_finalize(stmt)
            execLocked("COMMIT;")
            return result
        }
    }

    private func upsertWorkspace(event: TUAEvent, id: String) {
        let root = event.workspaceRoot
        let sql = """
        INSERT INTO workspaces (id, root_path, name, ide, source)
        VALUES (?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
            root_path = COALESCE(excluded.root_path, workspaces.root_path),
            name = COALESCE(excluded.name, workspaces.name),
            ide = COALESCE(excluded.ide, workspaces.ide);
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        bind(stmt, 1, id)
        bind(stmt, 2, root)
        bind(stmt, 3, root.map { ($0 as NSString).lastPathComponent })
        bind(stmt, 4, event.ide)
        bind(stmt, 5, root == nil ? "inferred" : "ide_declared")
        sqlite3_step(stmt)
        sqlite3_finalize(stmt)
    }

    // MARK: - 读取

    /// 今日快照。传 `workspaceId` 时只统计该工作区（悬浮窗「工作区视角」）。
    func todayStats(workspaceId: String? = nil) -> TodayStats {
        queue.sync {
            let start = Self.startOfTodayMillis()
            var stats = TodayStats()
            stats.events = Int(countLocked(
                "SELECT COUNT(*) FROM events WHERE ts >= ?\(workspaceFilterClause(workspaceId));",
                start: start, workspaceId: workspaceId))
            stats.workspaces = Int(countLocked(
                "SELECT COUNT(DISTINCT workspace_id) FROM events WHERE ts >= ? AND workspace_id IS NOT NULL AND workspace_id <> ''\(workspaceFilterClause(workspaceId));",
                start: start, workspaceId: workspaceId))
            stats.agents = agentStatsLocked(since: start, workspaceId: workspaceId)
            return stats
        }
    }

    /// 按 Agent 隔离统计今日用量（D14：各行独立，**绝不跨 Agent 合计积分**）。
    private func agentStatsLocked(since start: Int64, workspaceId: String?) -> [AgentStats] {
        let sql = """
        SELECT COALESCE(ide,'') AS agent,
               COALESCE(SUM(credits),0) AS credits,
               COUNT(DISTINCT CASE WHEN turn_id IS NOT NULL AND turn_id <> '' THEN turn_id END) AS turns,
               COUNT(*) AS events
        FROM events WHERE ts >= ?\(workspaceFilterClause(workspaceId))
        GROUP BY COALESCE(ide,'')
        ORDER BY credits DESC, agent ASC;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, start)
        if let workspaceId { bind(stmt, 2, workspaceId) }
        var rows: [AgentStats] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            rows.append(AgentStats(
                id: columnText(stmt, 0) ?? "",
                credits: sqlite3_column_double(stmt, 1),
                turns: Int(sqlite3_column_int64(stmt, 2)),
                events: Int(sqlite3_column_int64(stmt, 3))
            ))
        }
        return rows
    }

    /// `Agent × 模型` 两级的积分构成（D15）。可传 `ide` 只看单个 Agent。
    func modelBreakdown(since start: Int64, ide: String? = nil) -> [ModelStats] {
        let filter = ide.map { "AND COALESCE(ide,'') = '\($0.replacingOccurrences(of: "'", with: "''"))'" } ?? ""
        let sql = """
        SELECT COALESCE(ide,'') AS agent,
               COALESCE(model,'') AS model,
               COALESCE(SUM(credits),0) AS credits,
               COUNT(*) AS events
        FROM events WHERE ts >= \(start) \(filter)
        GROUP BY COALESCE(ide,''), COALESCE(model,'')
        ORDER BY credits DESC;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        var rows: [ModelStats] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            rows.append(ModelStats(
                ide: columnText(stmt, 0) ?? "",
                model: columnText(stmt, 1) ?? "",
                credits: sqlite3_column_double(stmt, 2),
                events: Int(sqlite3_column_int64(stmt, 3))
            ))
        }
        return rows
    }

    // MARK: - 悬浮窗实时态（M3）

    /// 今日全部 turn（异常检出用；**在单个 Agent 内**计算基线，D14）。
    func todayTurns(since start: Int64, workspaceId: String? = nil) -> [TurnSummary] {
        queue.sync { turnsLocked(since: start, workspaceId: workspaceId, limit: nil) }
    }

    private func turnsLocked(since start: Int64, workspaceId: String?, limit: Int?) -> [TurnSummary] {
        let filter = workspaceFilterClause(workspaceId)
        let limiter = limit.map { "LIMIT \($0)" } ?? ""
        let sql = """
        SELECT t.turn_id, COALESCE(t.ide,''), t.model, t.workspace_id, COALESCE(w.name,''),
               COALESCE(t.started_at,0), COALESCE(t.ended_at,0), COALESCE(t.action_count,0),
               COALESCE(t.credits_total,0), COALESCE(t.latency_max_ms,0),
               COALESCE((SELECT MAX(ts) FROM events e WHERE e.turn_id = t.turn_id), COALESCE(t.ended_at,0)),
               (SELECT COUNT(*) FROM events e2 WHERE e2.turn_id = t.turn_id AND e2.event_name = 'Stop')
        FROM turns t LEFT JOIN workspaces w ON w.id = t.workspace_id
        WHERE COALESCE(t.started_at,0) >= ?\(filter.replacingOccurrences(of: "workspace_id", with: "t.workspace_id"))
        ORDER BY COALESCE((SELECT MAX(ts) FROM events e WHERE e.turn_id = t.turn_id), t.started_at) DESC \(limiter);
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, start)
        if let workspaceId { bind(stmt, 2, workspaceId) }
        var rows: [TurnSummary] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            rows.append(TurnSummary(
                turnId: columnText(stmt, 0) ?? "",
                ide: columnText(stmt, 1) ?? "",
                model: columnText(stmt, 2),
                workspaceId: columnText(stmt, 3),
                workspaceName: columnText(stmt, 4),
                startedAt: sqlite3_column_int64(stmt, 5),
                endedAt: sqlite3_column_int64(stmt, 6),
                actionCount: Int(sqlite3_column_int64(stmt, 7)),
                credits: sqlite3_column_double(stmt, 8),
                latencyMs: Int(sqlite3_column_int64(stmt, 9)),
                lastEventTs: sqlite3_column_int64(stmt, 10),
                ended: sqlite3_column_int64(stmt, 11) > 0
            ))
        }
        return rows
    }

    /// 某工作区一段时间内「已结束轮次」的积分列表（供事前护航算损失估计 P95）。D14：按工作区隔离。
    func endedTurnCredits(workspaceId: String, since: Int64) -> [Double] {
        queue.sync {
            let sql = """
            SELECT credits_total FROM turns
            WHERE workspace_id = ? AND COALESCE(started_at,0) >= ?
              AND EXISTS (SELECT 1 FROM events e WHERE e.turn_id = turns.turn_id AND e.event_name = 'Stop');
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, workspaceId)
            sqlite3_bind_int64(stmt, 2, since)
            var out: [Double] = []
            while sqlite3_step(stmt) == SQLITE_ROW { out.append(sqlite3_column_double(stmt, 0)) }
            return out
        }
    }

    /// 同一会话最近若干轮的提问原文（已脱敏，时间正序、最新在末尾）+ 工作区名。
    /// 供事前护航「AI 一键改写」拼当前会话情景上下文，让候选更贴合用户真正在改的对象/目标。
    func recentSessionPrompts(sessionId: String, limit: Int = 6) -> [(workspace: String, prompt: String)] {
        queue.sync {
            let sql = """
            SELECT COALESCE(t.user_prompt,''), COALESCE(w.name,'')
            FROM turns t LEFT JOIN workspaces w ON w.id = t.workspace_id
            WHERE t.session_id = ? AND COALESCE(t.user_prompt,'') <> ''
            ORDER BY COALESCE(t.started_at,0) DESC LIMIT ?;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, sessionId)
            sqlite3_bind_int(stmt, 2, Int32(limit))
            var rows: [(workspace: String, prompt: String)] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append((workspace: columnText(stmt, 1) ?? "", prompt: columnText(stmt, 0) ?? ""))
            }
            return rows.reversed()   // 取的是最近 N 条（倒序），翻正成时间正序
        }
    }
    func hourlyTrend(since start: Int64, workspaceId: String? = nil) -> [AgentTrend] {
        queue.sync {
            let now = TUAEvent.nowMillis()
            let bucketCount = min(24, max(1, Int((now - start) / 3_600_000) + 1))
            let sql = """
            SELECT COALESCE(ide,'') AS agent,
                   CAST((ts - ?) / 3600000 AS INTEGER) AS bucket,
                   COALESCE(SUM(credits),0) AS credits
            FROM events WHERE ts >= ?\(workspaceFilterClause(workspaceId))
            GROUP BY agent, bucket;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, start)
            sqlite3_bind_int64(stmt, 2, start)
            if let workspaceId { bind(stmt, 3, workspaceId) }
            var series: [String: [Double]] = [:]
            var order: [String] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let agent = columnText(stmt, 0) ?? ""
                let bucket = Int(sqlite3_column_int64(stmt, 1))
                let credits = sqlite3_column_double(stmt, 2)
                if series[agent] == nil {
                    series[agent] = Array(repeating: 0, count: bucketCount)
                    order.append(agent)
                }
                if bucket >= 0, bucket < bucketCount { series[agent]?[bucket] += credits }
            }
            return order.map { AgentTrend(id: $0, hours: series[$0] ?? []) }
        }
    }

    /// 今日工作区排行：**按 Agent 分组**，组内按积分降序（D14：绝不跨 Agent 合计）。
    func workspaceRanking(since start: Int64) -> [WorkspaceStats] {
        queue.sync {
            let sql = """
            SELECT COALESCE(e.ide,'') AS agent,
                   e.workspace_id,
                   COALESCE(w.name,'未知工作区') AS name,
                   COALESCE(SUM(e.credits),0) AS credits,
                   COUNT(DISTINCT CASE WHEN e.turn_id IS NOT NULL AND e.turn_id <> '' THEN e.turn_id END) AS turns,
                   COALESCE(MAX(e.ts),0) AS last_ts,
                   MAX(CASE WHEN w.root_path LIKE '%/WorkBuddy/任务/%' THEN 1 ELSE 0 END) AS is_task
            FROM events e LEFT JOIN workspaces w ON w.id = e.workspace_id
            WHERE e.ts >= ? AND e.workspace_id IS NOT NULL AND e.workspace_id <> ''
            GROUP BY COALESCE(e.ide,''), e.workspace_id
            ORDER BY COALESCE(e.ide,'') ASC, credits DESC;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, start)
            var rows: [WorkspaceStats] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(WorkspaceStats(
                    ide: columnText(stmt, 0) ?? "",
                    workspaceId: columnText(stmt, 1) ?? "",
                    name: columnText(stmt, 2) ?? "未知工作区",
                    credits: sqlite3_column_double(stmt, 3),
                    turns: Int(sqlite3_column_int64(stmt, 4)),
                    lastTs: sqlite3_column_int64(stmt, 5),
                    isTask: sqlite3_column_int(stmt, 6) != 0
                ))
            }
            return rows
        }
    }

    /// 按工作区查各会话及其轮次（今日）。
    func sessionsByWorkspace(since start: Int64) -> [SessionSummary] {
        queue.sync {
            let sql = """
            SELECT t.session_id, t.workspace_id, COUNT(*) AS turn_count,
                   (SELECT user_prompt FROM turns t2
                    WHERE t2.session_id = t.session_id AND t2.workspace_id = t.workspace_id
                      AND t2.user_prompt IS NOT NULL AND t2.user_prompt <> ''
                    ORDER BY t2.started_at ASC LIMIT 1) AS first_prompt
            FROM turns t
            WHERE t.started_at >= ? AND t.workspace_id IS NOT NULL AND t.workspace_id <> ''
              AND t.session_id IS NOT NULL AND t.session_id <> ''
            GROUP BY t.workspace_id, t.session_id
            ORDER BY turn_count DESC;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, start)
            var rows: [SessionSummary] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(SessionSummary(
                    sessionId: columnText(stmt, 0) ?? "",
                    workspaceId: columnText(stmt, 1) ?? "",
                    turnCount: Int(sqlite3_column_int64(stmt, 2)),
                    prompt: columnText(stmt, 3)
                ))
            }
            return rows
        }
    }

    /// 按会话查最新 N 条事件（时间倒序，最新在上）。
    func eventsForSessions(sessionIds: [String], since: Int64, limit: Int = 5) -> [String: [EventRow]] {
        guard !sessionIds.isEmpty else { return [:] }
        return queue.sync {
            var result: [String: [EventRow]] = [:]
            let sql = """
            SELECT e.id, e.ts, e.ide, e.model, e.event_name, e.tool_name, e.io_kind,
                   e.credits, e.latency_ms, e.status, e.detail, w.name, e.session_id
            FROM events e LEFT JOIN workspaces w ON w.id = e.workspace_id
            WHERE e.session_id = ? AND e.ts >= ?
            ORDER BY e.ts DESC LIMIT \(limit);
            """
            for sid in sessionIds {
                var stmt: OpaquePointer?
                guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { continue }
                sqlite3_bind_text(stmt, 1, sid, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
                sqlite3_bind_int64(stmt, 2, since)
                var rows: [EventRow] = []
                while sqlite3_step(stmt) == SQLITE_ROW {
                    rows.append(EventRow(
                        id: sqlite3_column_int64(stmt, 0),
                        ts: sqlite3_column_int64(stmt, 1),
                        ide: columnText(stmt, 2),
                        model: columnText(stmt, 3),
                        eventName: columnText(stmt, 4),
                        toolName: columnText(stmt, 5),
                        ioKind: columnText(stmt, 6),
                        credits: sqlite3_column_double(stmt, 7),
                        latencyMs: sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 8)),
                        status: columnText(stmt, 9),
                        detail: columnText(stmt, 10),
                        workspaceName: columnText(stmt, 11),
                        sessionId: columnText(stmt, 12)
                    ))
                }
                sqlite3_finalize(stmt)
                result[sid] = rows
            }
            return result
        }
    }

    /// 某一轮的交互明细（时间正序，含每步的详情文本），供异常详情弹窗展示「这一轮到底做了什么」。
    func eventsForTurn(turnId: String, limit: Int = 200) -> [EventRow] {
        return queue.sync {
            let sql = """
            SELECT e.id, e.ts, e.ide, e.model, e.event_name, e.tool_name, e.io_kind,
                   e.credits, e.latency_ms, e.status, e.detail, w.name, e.session_id
            FROM events e LEFT JOIN workspaces w ON w.id = e.workspace_id
            WHERE e.turn_id = ?
            ORDER BY e.ts ASC, e.id ASC LIMIT \(limit);
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, turnId, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            var rows: [EventRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(EventRow(
                    id: sqlite3_column_int64(stmt, 0),
                    ts: sqlite3_column_int64(stmt, 1),
                    ide: columnText(stmt, 2),
                    model: columnText(stmt, 3),
                    eventName: columnText(stmt, 4),
                    toolName: columnText(stmt, 5),
                    ioKind: columnText(stmt, 6),
                    credits: sqlite3_column_double(stmt, 7),
                    latencyMs: sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 8)),
                    status: columnText(stmt, 9),
                    detail: columnText(stmt, 10),
                    workspaceName: columnText(stmt, 11),
                    sessionId: columnText(stmt, 12)
                ))
            }
            return rows
        }
    }

    // MARK: - turn 物化

    /// 某会话已入库的 `UserPromptSubmit` 数（App 侧轮次号递增用）。
    func promptSubmitCount(ide: String, sessionId: String) -> Int {
        queue.sync {
            let sql = "SELECT COUNT(*) FROM events WHERE ide = ? AND session_id = ? AND event_name = 'UserPromptSubmit';"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, ide)
            bind(stmt, 2, sessionId)
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
        }
    }

    /// 由该 turn 的事件重算并 upsert 汇总行（幂等，可反复调用）。
    func refreshTurn(turnId: String, prompt: String?) {
        queue.sync {
            let aggSQL = """
            SELECT
              (SELECT workspace_id FROM events WHERE turn_id = ? ORDER BY ts LIMIT 1),
              (SELECT session_id   FROM events WHERE turn_id = ? ORDER BY ts LIMIT 1),
              (SELECT ide          FROM events WHERE turn_id = ? ORDER BY ts LIMIT 1),
              (SELECT model        FROM events WHERE turn_id = ? AND model IS NOT NULL AND model <> '' ORDER BY ts LIMIT 1),
              MIN(ts), MAX(ts),
              SUM(CASE WHEN event_name IN ('PostToolUse','PostToolUseFailure') THEN 1 ELSE 0 END),
              COALESCE(SUM(credits), 0),
              COALESCE(SUM(token_in), 0), COALESCE(SUM(token_out), 0), COALESCE(SUM(token_cache), 0),
              COALESCE(SUM(latency_ms), 0), COALESCE(MAX(latency_ms), 0)
            FROM events WHERE turn_id = ?;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, aggSQL, -1, &stmt, nil) == SQLITE_OK else { return }
            for index in 1...5 { bind(stmt, Int32(index), turnId) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { sqlite3_finalize(stmt); return }
            // 空轮次（无事件）不落库：MIN(ts) 为 NULL 即说明该 turn_id 没有任何事件。
            if sqlite3_column_type(stmt, 4) == SQLITE_NULL { sqlite3_finalize(stmt); return }

            let workspaceId = columnText(stmt, 0)
            let sessionId = columnText(stmt, 1)
            let ide = columnText(stmt, 2)
            let model = columnText(stmt, 3)
            let startedAt = sqlite3_column_int64(stmt, 4)
            let endedAt = sqlite3_column_int64(stmt, 5)
            let actionCount = Int(sqlite3_column_int64(stmt, 6))
            let creditsTotal = sqlite3_column_double(stmt, 7)
            let tokenIn = Int(sqlite3_column_int64(stmt, 8))
            let tokenOut = Int(sqlite3_column_int64(stmt, 9))
            let tokenCache = Int(sqlite3_column_int64(stmt, 10))
            let latencyTotal = Int(sqlite3_column_int64(stmt, 11))
            let latencyMax = Int(sqlite3_column_int64(stmt, 12))
            sqlite3_finalize(stmt)

            let upsert = """
            INSERT INTO turns (turn_id, workspace_id, session_id, ide, model, user_prompt,
                               started_at, ended_at, action_count, credits_total, token_total,
                               token_in, token_out, token_cache, latency_total_ms, latency_max_ms)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(turn_id) DO UPDATE SET
                workspace_id = COALESCE(excluded.workspace_id, turns.workspace_id),
                session_id   = COALESCE(excluded.session_id, turns.session_id),
                ide          = COALESCE(excluded.ide, turns.ide),
                model        = COALESCE(excluded.model, turns.model),
                user_prompt  = COALESCE(excluded.user_prompt, turns.user_prompt),
                started_at   = excluded.started_at,
                ended_at     = excluded.ended_at,
                action_count = excluded.action_count,
                credits_total = excluded.credits_total,
                token_total  = excluded.token_total,
                token_in     = excluded.token_in,
                token_out    = excluded.token_out,
                token_cache  = excluded.token_cache,
                latency_total_ms = excluded.latency_total_ms,
                latency_max_ms   = excluded.latency_max_ms;
            """
            var upsertStmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, upsert, -1, &upsertStmt, nil) == SQLITE_OK else { return }
            bind(upsertStmt, 1, turnId)
            bind(upsertStmt, 2, workspaceId)
            bind(upsertStmt, 3, sessionId)
            bind(upsertStmt, 4, ide)
            bind(upsertStmt, 5, model)
            bind(upsertStmt, 6, SecretRedactor.redactOptional(prompt))
            sqlite3_bind_int64(upsertStmt, 7, startedAt)
            sqlite3_bind_int64(upsertStmt, 8, endedAt)
            sqlite3_bind_int64(upsertStmt, 9, Int64(actionCount))
            sqlite3_bind_double(upsertStmt, 10, creditsTotal)
            sqlite3_bind_int64(upsertStmt, 11, Int64(tokenIn + tokenOut + tokenCache))
            sqlite3_bind_int64(upsertStmt, 12, Int64(tokenIn))
            sqlite3_bind_int64(upsertStmt, 13, Int64(tokenOut))
            sqlite3_bind_int64(upsertStmt, 14, Int64(tokenCache))
            sqlite3_bind_int64(upsertStmt, 15, Int64(latencyTotal))
            sqlite3_bind_int64(upsertStmt, 16, Int64(latencyMax))
            sqlite3_step(upsertStmt)
            sqlite3_finalize(upsertStmt)
        }
    }

    /// 删除某会话的全部事件 / 汇总行，并清掉探针伪造的工作区（自测不污染真库）。
    func purgeSession(_ sessionId: String, probeRoot: String? = nil) {
        queue.sync {
            execLocked("BEGIN IMMEDIATE;")
            deleteRows("events", sessionId: sessionId)
            deleteRows("turns", sessionId: sessionId)
            if let probeRoot { deleteWorkspace(id: Workspace.deriveId(root: probeRoot)) }
            execLocked("COMMIT;")
        }
    }

    private func deleteRows(_ table: String, sessionId: String) {
        var stmt: OpaquePointer?
        let sql = "DELETE FROM \(table) WHERE session_id = ?;"
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        bind(stmt, 1, sessionId)
        sqlite3_step(stmt)
        sqlite3_finalize(stmt)
    }

    private func deleteWorkspace(id: String) {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM workspaces WHERE id = ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        bind(stmt, 1, id)
        sqlite3_step(stmt)
        sqlite3_finalize(stmt)
    }

    func recentEvents(limit: Int, ide: String? = nil) -> [EventRow] {
        queue.sync {
            // 按来源取最近事件：不能先取全局 top-N 再过滤，否则非活跃来源
            // （如 WorkBuddy，最新事件在几天前）会因不在全局头部而被判空。
            let whereSQL = (ide == nil) ? "" : "WHERE e.ide = ?1"
            let sql = """
            SELECT e.id, e.ts, e.ide, e.model, e.event_name, e.tool_name, e.io_kind,
                   e.credits, e.latency_ms, e.status, e.detail, w.name, e.session_id
            FROM events e LEFT JOIN workspaces w ON w.id = e.workspace_id
            \(whereSQL)
            ORDER BY e.ts DESC, e.id DESC LIMIT \(limit);
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            if let ide { bind(stmt, 1, ide) }
            var rows: [EventRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(EventRow(
                    id: sqlite3_column_int64(stmt, 0),
                    ts: sqlite3_column_int64(stmt, 1),
                    ide: columnText(stmt, 2),
                    model: columnText(stmt, 3),
                    eventName: columnText(stmt, 4),
                    toolName: columnText(stmt, 5),
                    ioKind: columnText(stmt, 6),
                    credits: sqlite3_column_double(stmt, 7),
                    latencyMs: sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 8)),
                    status: columnText(stmt, 9),
                    detail: columnText(stmt, 10),
                    workspaceName: columnText(stmt, 11),
                    sessionId: columnText(stmt, 12)
                ))
            }
            sqlite3_finalize(stmt)
            return rows
        }
    }

    /// 某工作区的最近事件（浮窗右栏「按工作区展示最近事件」用）。
    func recentEventsForWorkspace(workspaceId: String, ide: String, limit: Int) -> [EventRow] {
        queue.sync {
            let sql = """
            SELECT e.id, e.ts, e.ide, e.model, e.event_name, e.tool_name, e.io_kind,
                   e.credits, e.latency_ms, e.status, e.detail, w.name, e.session_id
            FROM events e LEFT JOIN workspaces w ON w.id = e.workspace_id
            WHERE e.workspace_id = ?1 AND COALESCE(e.ide,'') = ?2
            ORDER BY e.ts DESC, e.id DESC LIMIT \(limit);
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            bind(stmt, 1, workspaceId)
            bind(stmt, 2, ide)
            var rows: [EventRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(EventRow(
                    id: sqlite3_column_int64(stmt, 0),
                    ts: sqlite3_column_int64(stmt, 1),
                    ide: columnText(stmt, 2),
                    model: columnText(stmt, 3),
                    eventName: columnText(stmt, 4),
                    toolName: columnText(stmt, 5),
                    ioKind: columnText(stmt, 6),
                    credits: sqlite3_column_double(stmt, 7),
                    latencyMs: sqlite3_column_type(stmt, 8) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, 8)),
                    status: columnText(stmt, 9),
                    detail: columnText(stmt, 10),
                    workspaceName: columnText(stmt, 11),
                    sessionId: columnText(stmt, 12)
                ))
            }
            sqlite3_finalize(stmt)
            return rows
        }
    }

    /// 持久化某轮的 AI 分析（按 turn_id upsert）：重启后直接复用，不重复调模型烧钱。
    func saveAnalysis(turnId: String, analysis: AnomalyAnalysis, model: String) {
        queue.sync {
            let sql = """
            INSERT INTO analyses (turn_id, what, why, impact, fix, raw, model, created_at)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(turn_id) DO UPDATE SET
                what=excluded.what, why=excluded.why, impact=excluded.impact,
                fix=excluded.fix, raw=excluded.raw, model=excluded.model,
                created_at=excluded.created_at;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, turnId)
            bind(stmt, 2, SecretRedactor.redact(analysis.what))
            bind(stmt, 3, SecretRedactor.redact(analysis.why))
            bind(stmt, 4, SecretRedactor.redact(analysis.impact))
            bind(stmt, 5, SecretRedactor.redact(analysis.fix))
            bind(stmt, 6, SecretRedactor.redact(analysis.raw))
            bind(stmt, 7, model)
            sqlite3_bind_int64(stmt, 8, TUAEvent.nowMillis())
            sqlite3_step(stmt)
        }
    }

    /// 载入全部已存分析（turn_id → 结果），启动时灌进内存缓存。
    func loadAnalyses() -> [String: AnomalyAnalysis] {
        queue.sync {
            let sql = "SELECT turn_id, what, why, impact, fix, raw FROM analyses;"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [:] }
            defer { sqlite3_finalize(stmt) }
            var map: [String: AnomalyAnalysis] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let turnId = columnText(stmt, 0) else { continue }
                map[turnId] = AnomalyAnalysis(
                    what: columnText(stmt, 1) ?? "",
                    why: columnText(stmt, 2) ?? "",
                    impact: columnText(stmt, 3) ?? "",
                    fix: columnText(stmt, 4) ?? "",
                    raw: columnText(stmt, 5) ?? ""
                )
            }
            return map
        }
    }

    // MARK: - 来源 / 异常检出（M5）

    /// 时间窗内出现过的来源（ide）列表，升序。供悬浮窗「来源切换」下拉。
    func distinctIde(since start: Int64) -> [String] {
        queue.sync {
            let sql = "SELECT DISTINCT ide FROM events WHERE ts >= ? AND ide IS NOT NULL AND ide <> '' ORDER BY ide ASC;"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, start)
            var rows: [String] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let ide = columnText(stmt, 0) { rows.append(ide) }
            }
            return rows
        }
    }

    /// 取回指定轮次的事件投影（供 6 类规则的事件级判定）。
    func eventsByTurns(turnIds: [String]) -> [String: [EventFact]] {
        guard !turnIds.isEmpty else { return [:] }
        return queue.sync {
            let placeholders = Array(repeating: "?", count: turnIds.count).joined(separator: ",")
            let sql = """
            SELECT turn_id, ts, event_name, tool_name, io_kind, status
            FROM events WHERE turn_id IN (\(placeholders));
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [:] }
            defer { sqlite3_finalize(stmt) }
            for (offset, id) in turnIds.enumerated() { bind(stmt, Int32(offset + 1), id) }
            var map: [String: [EventFact]] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let turnId = columnText(stmt, 0) else { continue }
                map[turnId, default: []].append(EventFact(
                    ts: sqlite3_column_int64(stmt, 1),
                    eventName: columnText(stmt, 2),
                    toolName: columnText(stmt, 3),
                    ioKind: columnText(stmt, 4),
                    status: columnText(stmt, 5)
                ))
            }
            return map
        }
    }

    /// 幂等落库：`(scope, ref_id, kind)` 冲突即更新（不新增幽灵行）。
    func upsertFindings(_ anomalies: [Anomaly]) {
        queue.sync {
            execLocked("BEGIN IMMEDIATE;")
            let sql = """
            INSERT INTO findings (scope, ref_id, kind, severity, evidence, created_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(scope, ref_id, kind) DO UPDATE SET
                severity = excluded.severity,
                evidence = excluded.evidence,
                created_at = excluded.created_at;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
                execLocked("ROLLBACK;")
                NSLog("TUA: 预编译 findings 写入失败")
                return
            }
            let now = TUAEvent.nowMillis()
            for anomaly in anomalies {
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
                bind(stmt, 1, anomaly.scope)
                bind(stmt, 2, anomaly.refId)
                bind(stmt, 3, anomaly.kind)
                bind(stmt, 4, anomaly.severity)
                bind(stmt, 5, jsonString(anomaly.evidence))
                sqlite3_bind_int64(stmt, 6, now)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
            execLocked("COMMIT;")
        }
    }

    /// 清掉指定时间窗内（按 turn.started_at）的全部 findings，供重算前调用。
    /// 阈值调高后，旧口径误报的行不会再被 upsert 命中，靠这个 prune 掉，避免幽灵异常赖在列表里。
    func deleteFindingsSince(_ windowStart: Int64) {
        queue.sync {
            execLocked("BEGIN IMMEDIATE;")
            let sql = """
            DELETE FROM findings
            WHERE scope = 'turn'
              AND ref_id IN (SELECT turn_id FROM turns WHERE started_at >= ?);
            """
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_int64(stmt, 1, windowStart)
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
            execLocked("COMMIT;")
        }
    }

    /// 读回异常（JOIN turns/workspaces）；可选按严重度 / 类型 / 工作区过滤。
    func loadFindings(since start: Int64, severity: String? = nil,
                      kind: String? = nil, workspaceId: String? = nil) -> [FindingRow] {
        queue.sync {
            var sql = """
            SELECT f.id, f.scope, f.ref_id, f.kind, f.severity,
                   COALESCE(t.turn_id, f.ref_id), COALESCE(t.ide,''), t.model,
                   t.workspace_id, COALESCE(w.name,''), COALESCE(t.credits_total,0),
                   COALESCE(t.latency_max_ms,0), COALESCE(t.started_at,0), f.evidence
            FROM findings f
            LEFT JOIN turns t ON t.turn_id = f.ref_id
            LEFT JOIN workspaces w ON w.id = t.workspace_id
            WHERE f.created_at >= ?
            """
            var conditions: [String] = []
            var args: [Any] = [start]
            if severity != nil { conditions.append("f.severity = ?"); args.append(severity!) }
            if kind != nil { conditions.append("f.kind = ?"); args.append(kind!) }
            if workspaceId != nil { conditions.append("t.workspace_id = ?"); args.append(workspaceId!) }
            if !conditions.isEmpty { sql += " AND " + conditions.joined(separator: " AND ") }
            sql += " ORDER BY CASE f.severity WHEN 'high' THEN 0 ELSE 1 END, COALESCE(t.credits_total,0) DESC;"

            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            for (offset, arg) in args.enumerated() {
                let index = Int32(offset + 1)
                if let number = arg as? Int64 { sqlite3_bind_int64(stmt, index, number) }
                else if let text = arg as? String { bind(stmt, index, text) }
            }
            var rows: [FindingRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let evidenceText = columnText(stmt, 13)
                var evidence: [String: Any] = [:]
                if let evidenceText, let data = evidenceText.data(using: .utf8),
                   let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    evidence = parsed
                }
                rows.append(FindingRow(
                    id: sqlite3_column_int64(stmt, 0),
                    scope: columnText(stmt, 1) ?? "turn",
                    refId: columnText(stmt, 2) ?? "",
                    kind: columnText(stmt, 3) ?? "",
                    severity: columnText(stmt, 4) ?? "warn",
                    turnId: columnText(stmt, 5) ?? "",
                    ide: columnText(stmt, 6) ?? "",
                    model: columnText(stmt, 7),
                    workspaceId: columnText(stmt, 8),
                    workspaceName: columnText(stmt, 9) ?? "",
                    credits: sqlite3_column_double(stmt, 10),
                    latencyMs: Int(sqlite3_column_int64(stmt, 11)),
                    startedAt: sqlite3_column_int64(stmt, 12),
                    evidence: evidence
                ))
            }
            return rows
        }
    }

    // MARK: - 聚合明细（M4）

    /// 任务明细：按 Agent / 工作区筛选，**不跨 Agent 聚合**。
    func turnDetail(since start: Int64, ide: String? = nil,
                    workspaceId: String? = nil, limit: Int = 200) -> [TurnRow] {
        queue.sync {
            var sql = """
            SELECT t.turn_id, COALESCE(t.ide,''), t.model, t.workspace_id, COALESCE(w.name,''),
                   t.user_prompt, COALESCE(t.started_at,0), COALESCE(t.ended_at,0),
                   COALESCE(t.action_count,0), COALESCE(t.credits_total,0),
                   COALESCE(t.latency_total_ms,0), COALESCE(t.latency_max_ms,0), t.outcome,
                   (SELECT COUNT(*) FROM events e WHERE e.turn_id = t.turn_id AND e.event_name = 'Stop')
            FROM turns t LEFT JOIN workspaces w ON w.id = t.workspace_id
            WHERE COALESCE(t.started_at,0) >= ?
            """
            var args: [Any] = [start]
            if let ide { sql += " AND COALESCE(t.ide,'') = ?"; args.append(ide) }
            if let workspaceId { sql += " AND t.workspace_id = ?"; args.append(workspaceId) }
            sql += " ORDER BY COALESCE(t.started_at,0) DESC LIMIT \(limit);"

            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            for (offset, arg) in args.enumerated() {
                let index = Int32(offset + 1)
                if let number = arg as? Int64 { sqlite3_bind_int64(stmt, index, number) }
                else if let text = arg as? String { bind(stmt, index, text) }
            }
            var rows: [TurnRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                rows.append(TurnRow(
                    turnId: columnText(stmt, 0) ?? "",
                    ide: columnText(stmt, 1) ?? "",
                    model: columnText(stmt, 2),
                    workspaceId: columnText(stmt, 3),
                    workspaceName: columnText(stmt, 4) ?? "",
                    prompt: columnText(stmt, 5),
                    startedAt: sqlite3_column_int64(stmt, 6),
                    endedAt: sqlite3_column_int64(stmt, 7),
                    actionCount: Int(sqlite3_column_int64(stmt, 8)),
                    credits: sqlite3_column_double(stmt, 9),
                    latencyTotalMs: Int(sqlite3_column_int64(stmt, 10)),
                    latencyMaxMs: Int(sqlite3_column_int64(stmt, 11)),
                    outcome: columnText(stmt, 12),
                    ended: sqlite3_column_int64(stmt, 13) > 0
                ))
            }
            return rows
        }
    }

    /// 工作区维度综合统计（浮窗展开区一行态）：轮数/动作/积分/耗时 + events 聚合 token。
    func workspaceAggregate(ide: String, workspaceId: String) -> WorkspaceAggregate {
        queue.sync {
            var agg = WorkspaceAggregate()
            var stmt: OpaquePointer?
            let turnSQL = """
            SELECT COUNT(*), COALESCE(SUM(action_count),0), COALESCE(SUM(credits_total),0),
                   COALESCE(SUM(latency_total_ms),0)
            FROM turns WHERE COALESCE(ide,'') = ? AND workspace_id = ?;
            """
            if sqlite3_prepare_v2(handle, turnSQL, -1, &stmt, nil) == SQLITE_OK {
                defer { sqlite3_finalize(stmt) }
                bind(stmt, 1, ide)
                bind(stmt, 2, workspaceId)
                if sqlite3_step(stmt) == SQLITE_ROW {
                    agg.turns = Int(sqlite3_column_int64(stmt, 0))
                    agg.actions = Int(sqlite3_column_int64(stmt, 1))
                    agg.credits = sqlite3_column_double(stmt, 2)
                    agg.latencyMs = sqlite3_column_int64(stmt, 3)
                }
            }
            let tokenSQL = """
            SELECT COALESCE(SUM(token_in),0), COALESCE(SUM(token_out),0)
            FROM events WHERE COALESCE(ide,'') = ? AND workspace_id = ?;
            """
            stmt = nil
            if sqlite3_prepare_v2(handle, tokenSQL, -1, &stmt, nil) == SQLITE_OK {
                defer { sqlite3_finalize(stmt) }
                bind(stmt, 1, ide)
                bind(stmt, 2, workspaceId)
                if sqlite3_step(stmt) == SQLITE_ROW {
                    agg.tokenIn = sqlite3_column_int64(stmt, 0)
                    agg.tokenOut = sqlite3_column_int64(stmt, 1)
                }
            }
            return agg
        }
    }

    /// 工作区根路径（Skill 推荐的项目指纹探测用）。
    func workspaceRootPath(workspaceId: String) -> String? {
        queue.sync {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_prepare_v2(handle, "SELECT root_path FROM workspaces WHERE id = ?;", -1, &stmt, nil) == SQLITE_OK else { return nil }
            bind(stmt, 1, workspaceId)
            return sqlite3_step(stmt) == SQLITE_ROW ? columnText(stmt, 0) : nil
        }
    }

    /// 工作区记忆总结素材：优先 M9 语料库；本区无语料回退轮次表提问（按文本去重取最近 2 条）。
    func workspaceMemorySummary(workspaceId: String) -> (count: Int, recent: [String], fromCorpus: Bool) {
        queue.sync {
            var count = 0
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM prompt_corpus WHERE workspace_id = ?;", -1, &stmt, nil) == SQLITE_OK {
                defer { sqlite3_finalize(stmt) }
                bind(stmt, 1, workspaceId)
                if sqlite3_step(stmt) == SQLITE_ROW { count = Int(sqlite3_column_int64(stmt, 0)) }
            }
            if count > 0 {
                var recent: [String] = []
                stmt = nil
                if sqlite3_prepare_v2(handle, "SELECT prompt FROM prompt_corpus WHERE workspace_id = ? AND COALESCE(prompt,'') <> '' ORDER BY ts DESC LIMIT 2;", -1, &stmt, nil) == SQLITE_OK {
                    defer { sqlite3_finalize(stmt) }
                    bind(stmt, 1, workspaceId)
                    while sqlite3_step(stmt) == SQLITE_ROW { recent.append(columnText(stmt, 0) ?? "") }
                }
                return (count, recent, true)
            }
            stmt = nil
            if sqlite3_prepare_v2(handle, "SELECT COUNT(DISTINCT user_prompt) FROM turns WHERE workspace_id = ? AND COALESCE(user_prompt,'') <> '';", -1, &stmt, nil) == SQLITE_OK {
                defer { sqlite3_finalize(stmt) }
                bind(stmt, 1, workspaceId)
                if sqlite3_step(stmt) == SQLITE_ROW { count = Int(sqlite3_column_int64(stmt, 0)) }
            }
            var recent: [String] = []
            stmt = nil
            if sqlite3_prepare_v2(handle, "SELECT user_prompt FROM turns WHERE workspace_id = ? AND COALESCE(user_prompt,'') <> '' GROUP BY user_prompt ORDER BY MAX(started_at) DESC LIMIT 2;", -1, &stmt, nil) == SQLITE_OK {
                defer { sqlite3_finalize(stmt) }
                bind(stmt, 1, workspaceId)
                while sqlite3_step(stmt) == SQLITE_ROW { recent.append(columnText(stmt, 0) ?? "") }
            }
            return (count, recent, false)
        }
    }

    /// 单 Agent 的延迟 / 积分分位（P50/P90/P95/P99，D14：按 Agent 隔离）。
    func latencyPercentiles(since start: Int64, ide: String? = nil) -> [AgentLatencyStats] {
        queue.sync {
            var sql = "SELECT COALESCE(ide,''), COALESCE(credits_total,0), COALESCE(latency_max_ms,0) FROM turns WHERE COALESCE(started_at,0) >= ?"
            if let ide { sql += " AND COALESCE(ide,'') = '\(ide.replacingOccurrences(of: "'", with: "''"))'" }
            sql += ";"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, start)
            var creditsByAgent: [String: [Double]] = [:]
            var latencyByAgent: [String: [Double]] = [:]
            var order: [String] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let agent = columnText(stmt, 0) ?? ""
                if creditsByAgent[agent] == nil { order.append(agent) }
                creditsByAgent[agent, default: []].append(sqlite3_column_double(stmt, 1))
                latencyByAgent[agent, default: []].append(Double(sqlite3_column_int64(stmt, 2)))
            }
            return order.map { agent in
                let latencies = latencyByAgent[agent] ?? []
                return AgentLatencyStats(
                    ide: agent,
                    turns: latencies.count,
                    p50: Int(Percentiles.value(latencies, 0.50)),
                    p90: Int(Percentiles.value(latencies, 0.90)),
                    p95: Int(Percentiles.value(latencies, 0.95)),
                    p99: Int(Percentiles.value(latencies, 0.99)),
                    creditP95: Percentiles.value(creditsByAgent[agent] ?? [], 0.95)
                )
            }
            .sorted { $0.turns > $1.turns }
        }
    }

    // MARK: - 护航（M13 L4）

    /// 某会话「当前轮」：该 session 最新一条带 turn_id 的事件所属轮（PreToolUse 时刻，
    /// 最新事件恒落在刚由 UserPromptSubmit 开启、尚未 Stop 的进行中轮）。无则 nil。
    func currentTurnOfSession(sessionId: String) -> String? {
        queue.sync {
            let sql = """
            SELECT turn_id FROM events
            WHERE session_id = ? AND turn_id IS NOT NULL AND turn_id <> ''
            ORDER BY ts DESC, id DESC LIMIT 1;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, sessionId)
            return sqlite3_step(stmt) == SQLITE_ROW ? columnText(stmt, 0) : nil
        }
    }

    /// 护航干预留痕（每次 ask/deny 都写，allow 不写）。供「同轮同模式只问一次」判定与事后审计。
    func insertGuardLog(sessionId: String?, turnId: String?, ide: String?, toolName: String?,
                        rule: String, decision: String, reason: String, evidence: [String: Any],
                        stage: String = "inflight", prompt: String? = nil) {
        queue.sync {
            let sql = """
            INSERT INTO guard_log (ts, session_id, turn_id, ide, tool_name, rule, decision, reason, evidence, stage, prompt)
            VALUES (?,?,?,?,?,?,?,?,?,?,?);
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, TUAEvent.nowMillis())
            bind(stmt, 2, sessionId)
            bind(stmt, 3, turnId)
            bind(stmt, 4, ide)
            bind(stmt, 5, toolName)
            bind(stmt, 6, rule)
            bind(stmt, 7, decision)
            bind(stmt, 8, SecretRedactor.redactOptional(reason))
            bind(stmt, 9, jsonString(evidence))
            bind(stmt, 10, stage)
            bind(stmt, 11, SecretRedactor.redactOptional(prompt))
            sqlite3_step(stmt)
        }
    }

    // MARK: - 历史提问语料（M9 / 测评体系 Phase 0）

    /// 幂等回灌历史真人轮提问：`src_uid`（transcript 记录 uuid）冲突即跳过，重跑不增行。
    /// prompt 入库前过 `SecretRedactor`（与主库同口径）；workspace_id 由 root 派生。
    @discardableResult
    func upsertPromptCorpus(_ records: [PromptCorpusRecord]) -> Int {
        queue.sync {
            var inserted = 0
            execLocked("BEGIN IMMEDIATE;")
            let sql = """
            INSERT INTO prompt_corpus (src_uid, ts, ide, session_id, workspace_id, workspace_root, prompt)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(src_uid) DO NOTHING;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
                execLocked("ROLLBACK;")
                NSLog("TUA: 预编译 prompt_corpus 插入失败")
                return 0
            }
            for r in records {
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
                bind(stmt, 1, r.srcUid)
                sqlite3_bind_int64(stmt, 2, r.ts)
                bind(stmt, 3, r.ide)
                bind(stmt, 4, r.sessionId)
                bind(stmt, 5, r.workspaceRoot.map { Workspace.deriveId(root: $0) })
                bind(stmt, 6, r.workspaceRoot)
                bind(stmt, 7, SecretRedactor.redactOptional(r.prompt))
                if sqlite3_step(stmt) == SQLITE_DONE, sqlite3_changes(handle) > 0 { inserted += 1 }
            }
            sqlite3_finalize(stmt)
            execLocked("COMMIT;")
            return inserted
        }
    }

    func promptCorpusCount() -> Int { countSimple("SELECT COUNT(*) FROM prompt_corpus;") }

    /// 一条相似历史提问语料（`similarPrompts` 返回，prompt 已脱敏）。
    struct PromptCorpusRow {
        let prompt: String
        let workspaceId: String?
    }

    /// 语料表里与当前提问最相似的历史提问（M9 消费端，§6 v1 粗排）。
    /// 候选池 = 最近 500 条（本机量级小，全量进内存算）；池 `< 5` 条不猜（宁缺毋滥）。
    /// 已按分数降序、`minScore` 截断、归一化去重，并排除与当前提问同文的行。
    /// minScore=0.4：本机 228 条语料实测——随机对 p99≈0.35，直观同类对≥0.45（初值口径，将按数据校准）。
    func similarPrompts(text: String, workspaceId: String?, limit: Int = 3, minScore: Double = 0.4) -> [PromptCorpusRow] {
        let query = PromptSimilarity.normalize(text)
        guard !query.isEmpty else { return [] }
        return queue.sync {
            let sql = """
            SELECT prompt, workspace_id FROM prompt_corpus
            WHERE COALESCE(prompt,'') <> '' ORDER BY ts DESC LIMIT 500;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            var seen = Set<String>()
            var poolCount = 0
            var scored: [(score: Double, row: PromptCorpusRow)] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let raw = columnText(stmt, 0) else { continue }
                let p = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                let norm = PromptSimilarity.normalize(p)
                guard !norm.isEmpty, norm != query, !seen.contains(norm) else { continue }
                seen.insert(norm)
                poolCount += 1
                let ws = columnText(stmt, 1)
                let sameWs = ws != nil && ws == workspaceId
                let sc = PromptSimilarity.score(queryText: text, candidateText: p, sameWorkspace: sameWs)
                if sc >= minScore { scored.append((sc, PromptCorpusRow(prompt: p, workspaceId: ws))) }
            }
            guard poolCount >= 5 else { return [] }   // 语料池有有效样本 <5 条不猜（§6）
            return scored.sorted { $0.score != $1.score ? $0.score > $1.score : $0.row.prompt < $1.row.prompt }
                .prefix(limit).map(\.row)
        }
    }

    /// 语料表里该会话记录过的 workspace_id（重启从 guard_log 恢复护航卡时还原工作区归属；无则 nil）。
    func corpusWorkspaceId(sessionId: String?) -> String? {
        guard let sessionId, !sessionId.isEmpty else { return nil }
        return queue.sync {
            var stmt: OpaquePointer?
            let sql = """
            SELECT workspace_id FROM prompt_corpus
            WHERE session_id = ? AND COALESCE(workspace_id,'') <> '' ORDER BY ts DESC LIMIT 1;
            """
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, sessionId)
            return sqlite3_step(stmt) == SQLITE_ROW ? columnText(stmt, 0) : nil
        }
    }

    // MARK: - L3 采纳埋点（测评体系 Phase 0）

    /// 记一次事前拦截卡上的采纳行为：`copy`+选中下标 / `reject`（关卡但没复制）。原文与候选均脱敏。
    func insertAdoptionEvent(target: String, ide: String?, sessionId: String?, workspaceId: String?,
                             originalPrompt: String, shown: [String], chosenIndex: Int?, action: String) {
        queue.sync {
            let trimmed = originalPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
            let hash = "p_" + Workspace.hash(trimmed)
            let chosenText = chosenIndex.flatMap { shown.indices.contains($0) ? shown[$0] : nil }
            let shownRedacted = shown.map { SecretRedactor.redact($0) }
            let sql = """
            INSERT INTO adoption_events
                (ts, source, target, ide, session_id, workspace_id, original_hash, original_prompt,
                 shown_candidates, chosen_index, chosen_prompt, action)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?);
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, TUAEvent.nowMillis())
            bind(stmt, 2, "live")
            bind(stmt, 3, target)
            bind(stmt, 4, ide)
            bind(stmt, 5, sessionId)
            bind(stmt, 6, workspaceId)
            bind(stmt, 7, hash)
            bind(stmt, 8, SecretRedactor.redactOptional(trimmed))
            bind(stmt, 9, jsonString(["items": shownRedacted]) ?? "[]")
            if let ci = chosenIndex { sqlite3_bind_int(stmt, 10, Int32(ci)) } else { sqlite3_bind_null(stmt, 10) }
            bind(stmt, 11, SecretRedactor.redactOptional(chosenText))
            bind(stmt, 12, action)
            sqlite3_step(stmt)
        }
    }

    func adoptionEventCount() -> Int { countSimple("SELECT COUNT(*) FROM adoption_events;") }

    private func countSimple(_ sql: String) -> Int {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
        }
    }

    // MARK: - 评测集读写（Phase 1 造锚）

    @discardableResult
    func upsertEvalCases(_ rows: [EvalCaseRow]) -> Int {
        queue.sync {
            var inserted = 0
            execLocked("BEGIN IMMEDIATE;")
            let sql = """
            INSERT INTO eval_cases
                (src_uid, target, prompt, workspace_id, ide, session_id, problems, readiness_before, tags, cohort, frozen, added_ts)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(src_uid) DO NOTHING;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
                execLocked("ROLLBACK;"); NSLog("TUA: 预编译 eval_cases 插入失败"); return 0
            }
            for r in rows {
                sqlite3_reset(stmt); sqlite3_clear_bindings(stmt)
                bind(stmt, 1, r.srcUid)
                bind(stmt, 2, r.target)
                bind(stmt, 3, SecretRedactor.redactOptional(r.prompt))
                bind(stmt, 4, r.workspaceId)
                bind(stmt, 5, r.ide)
                bind(stmt, 6, r.sessionId)
                bind(stmt, 7, jsonArray(r.problems))
                sqlite3_bind_double(stmt, 8, r.readinessBefore)
                bind(stmt, 9, jsonArray(r.tags))
                bind(stmt, 10, r.cohort)
                sqlite3_bind_int(stmt, 11, 1)
                sqlite3_bind_int64(stmt, 12, r.addedTs)
                if sqlite3_step(stmt) == SQLITE_DONE, sqlite3_changes(handle) > 0 { inserted += 1 }
            }
            sqlite3_finalize(stmt)
            execLocked("COMMIT;")
            return inserted
        }
    }

    func evalCaseCount() -> Int { countSimple("SELECT COUNT(*) FROM eval_cases;") }

    func evalCaseCount(cohort: String) -> Int {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM eval_cases WHERE cohort = ?;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, cohort)
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
        }
    }

    func evalCaseCount(target: String) -> Int {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM eval_cases WHERE target = ?;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, target)
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
        }
    }

    func loadEvalCases(target: String? = nil, cohort: String? = nil) -> [EvalCase] {
        queue.sync { () -> [EvalCase] in
            var sql = "SELECT id, src_uid, target, prompt, workspace_id, ide, problems, readiness_before, cohort FROM eval_cases WHERE 1=1"
            if target != nil { sql += " AND target = ?" }
            if cohort != nil { sql += " AND cohort = ?" }
            sql += " ORDER BY id ASC;"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            var pidx: Int32 = 1
            if let target { bind(stmt, pidx, target); pidx += 1 }
            if let cohort { bind(stmt, pidx, cohort) }
            var out: [EvalCase] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                out.append(EvalCase(
                    id: sqlite3_column_int64(stmt, 0),
                    srcUid: columnText(stmt, 1) ?? "",
                    target: columnText(stmt, 2) ?? "",
                    prompt: columnText(stmt, 3) ?? "",
                    workspaceId: columnText(stmt, 4),
                    ide: columnText(stmt, 5),
                    problems: stringArray(fromJSON: columnText(stmt, 6)),
                    readinessBefore: sqlite3_column_double(stmt, 7),
                    cohort: columnText(stmt, 8) ?? ""
                ))
            }
            return out
        }
    }

    /// 语料 + guard_log 里可作评测种子的真实提问（均已脱敏）。
    func seedingPrompts() -> [SeedingPrompt] {
        queue.sync {
            var out: [SeedingPrompt] = []
            out.append(contentsOf: readSeeding("SELECT src_uid, prompt, workspace_id, session_id, ide, ts FROM prompt_corpus WHERE COALESCE(prompt,'') <> '';"))
            out.append(contentsOf: readSeeding("SELECT 'gl_' || id, prompt, NULL, session_id, ide, ts FROM guard_log WHERE stage = 'precheck' AND decision = 'block' AND COALESCE(prompt,'') <> '';"))
            return out
        }
    }

    private func readSeeding(_ sql: String) -> [SeedingPrompt] {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        var out: [SeedingPrompt] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            out.append(SeedingPrompt(
                uid: columnText(stmt, 0) ?? "",
                prompt: columnText(stmt, 1) ?? "",
                workspaceId: columnText(stmt, 2),
                sessionId: columnText(stmt, 3),
                ide: columnText(stmt, 4),
                ts: sqlite3_column_int64(stmt, 5)
            ))
        }
        return out
    }

    func upsertGold(caseId: Int64, text: String, writtenBy: String) {
        queue.sync {
            let sql = """
            INSERT INTO gold_rewrites (case_id, gold_text, written_by, written_ts)
            VALUES (?,?,?,?)
            ON CONFLICT(case_id) DO UPDATE SET gold_text=excluded.gold_text, written_by=excluded.written_by, written_ts=excluded.written_ts;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, caseId)
            bind(stmt, 2, SecretRedactor.redactOptional(text))
            bind(stmt, 3, writtenBy)
            sqlite3_bind_int64(stmt, 4, TUAEvent.nowMillis())
            sqlite3_step(stmt)
        }
    }

    func insertPreferencePair(caseId: Int64, candA: String, candB: String, verA: String, verB: String, judge: String, choice: String) {
        queue.sync {
            let sql = "INSERT INTO preference_pairs (case_id, cand_a, cand_b, ver_a, ver_b, judge, choice, judged_ts) VALUES (?,?,?,?,?,?,?,?);"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, caseId)
            bind(stmt, 2, SecretRedactor.redactOptional(candA))
            bind(stmt, 3, SecretRedactor.redactOptional(candB))
            bind(stmt, 4, verA)
            bind(stmt, 5, verB)
            bind(stmt, 6, judge)
            bind(stmt, 7, choice)
            sqlite3_bind_int64(stmt, 8, TUAEvent.nowMillis())
            sqlite3_step(stmt)
        }
    }

    func goldCount() -> Int { countSimple("SELECT COUNT(*) FROM gold_rewrites;") }
    func preferencePairCount() -> Int { countSimple("SELECT COUNT(*) FROM preference_pairs;") }

    private func jsonArray(_ arr: [String]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: arr, options: []),
              let s = String(data: d, encoding: .utf8) else { return "[]" }
        return s
    }

    private func stringArray(fromJSON raw: String?) -> [String] {
        guard let raw, let d = raw.data(using: .utf8),
              let a = (try? JSONSerialization.jsonObject(with: d)) as? [String] else { return [] }
        return a
    }

    /// 该轮某规则是否已提醒过（ask/deny 均算）——同 turn 同模式只问一次，不重复骚扰。
    // MARK: - 护航日志读取（事前拦截）

    /// 最近一次事前拦截（stage='precheck' 且 decision='block'）：供护航卡重启后仍展示待改写原文。
    func latestPrecheckBlock() -> PrecheckLog? {
        queue.sync {
            let sql = """
            SELECT ts, session_id, ide, prompt, reason, evidence FROM guard_log
            WHERE stage = 'precheck' AND decision = 'block'
            ORDER BY ts DESC, id DESC LIMIT 1;
            """
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return PrecheckLog(
                ts: sqlite3_column_int64(stmt, 0),
                sessionId: columnText(stmt, 1),
                ide: columnText(stmt, 2),
                prompt: columnText(stmt, 3) ?? "",
                reason: columnText(stmt, 4) ?? "",
                evidenceJSON: columnText(stmt, 5) ?? "{}"
            )
        }
    }

    /// 该轮某规则是否已提醒过（ask/deny 均算）——同 turn 同模式只问一次，不重复骚扰。
    func guardAlreadyRaised(turnId: String, rule: String) -> Bool {
        queue.sync {
            let sql = "SELECT COUNT(*) FROM guard_log WHERE turn_id = ? AND rule = ? AND decision IN ('ask','deny');"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
            defer { sqlite3_finalize(stmt) }
            bind(stmt, 1, turnId)
            bind(stmt, 2, rule)
            return sqlite3_step(stmt) == SQLITE_ROW && sqlite3_column_int64(stmt, 0) > 0
        }
    }

    // MARK: - 底层

    private func execLocked(_ sql: String) {
        var error: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(handle, sql, nil, nil, &error) != SQLITE_OK {
            if let error { NSLog("TUA: SQL 错误 \(String(cString: error))"); sqlite3_free(error) }
        }
    }

    /// 工作区过滤片段（配合第 2 个绑定参数）。列名可在 turns 查询里换成别名列。
    private func workspaceFilterClause(_ workspaceId: String?) -> String {
        workspaceId == nil ? "" : " AND workspace_id = ?"
    }

    /// 带 (start, workspaceId) 两个绑定的标量计数。
    private func countLocked(_ sql: String, start: Int64, workspaceId: String?) -> Int64 {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, start)
        if let workspaceId { bind(stmt, 2, workspaceId) }
        return sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int64(stmt, 0) : 0
    }

    private func columnText(_ stmt: OpaquePointer?, _ index: Int32) -> String? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL,
              let cString = sqlite3_column_text(stmt, index) else { return nil }
        return String(cString: cString)
    }

    /// 依据字典序列化为 JSON 文本（失败落 nil，不塞兑底值）。
    private func jsonString(_ dict: [String: Any]) -> String? {
        guard JSONSerialization.isValidJSONObject(dict),
              let data = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func bind(_ stmt: OpaquePointer?, _ index: Int32, _ value: String?) {
        if let value { sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT) }
        else { sqlite3_bind_null(stmt, index) }
    }

    private func bind(_ stmt: OpaquePointer?, _ index: Int32, _ value: Int?) {
        if let value { sqlite3_bind_int64(stmt, index, Int64(value)) }
        else { sqlite3_bind_null(stmt, index) }
    }

    private func bind(_ stmt: OpaquePointer?, _ index: Int32, _ value: Double?) {
        if let value { sqlite3_bind_double(stmt, index, value) }
        else { sqlite3_bind_null(stmt, index) }
    }

    static func startOfTodayMillis() -> Int64 {
        Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
    }
}

/// 提问粗排相似度（M9 §6 v1 最小实现）：字符 2-gram 重叠 + 长度桶 + 同工作区加权。
/// 中文分词未就绪，不做语义推断——**初值口径，将按数据校准**。
enum PromptSimilarity {
    /// 归一化：小写、剔除所有空白（仅用于对比/去重，不改展示原文）。
    static func normalize(_ s: String) -> String {
        let kept = s.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }
        return String(String.UnicodeScalarView(kept)).lowercased()
    }

    /// 字符 2-gram 集合（单字退化为单字符 gram，避免短句空集）。
    static func bigrams(_ s: String) -> Set<String> {
        let chars = Array(s)
        guard chars.count > 1 else { return chars.isEmpty ? [] : Set([String(chars[0])]) }
        var out = Set<String>()
        for i in 0..<(chars.count - 1) { out.insert(String(chars[i..<(i + 2)])) }
        return out
    }

    /// 长度桶（0-39 / 40-119 / 120-399 / 400+）：同桶视为体量同档。
    static func lengthBucket(_ count: Int) -> Int {
        switch count {
        case ..<40: return 0
        case ..<120: return 1
        case ..<400: return 2
        default: return 3
        }
    }

    /// 多因子分：2-gram 重叠系数（交集/较短集）为主，同长度桶 +0.05、同工作区 +0.15（权重初值，将按数据校准）。
    /// 选重叠系数而非 Dice：被拦的多为短模糊提问，Dice 会被长候选的长度稀释掉。
    static func score(queryText: String, candidateText: String, sameWorkspace: Bool) -> Double {
        let a = normalize(queryText), b = normalize(candidateText)
        let ga = bigrams(a), gb = bigrams(b)
        guard !ga.isEmpty, !gb.isEmpty else { return 0 }
        var s = Double(ga.intersection(gb).count) / Double(min(ga.count, gb.count))
        if lengthBucket(a.count) == lengthBucket(b.count) { s += 0.05 }
        if sameWorkspace { s += 0.15 }
        return min(s, 1.0)
    }
}