import Foundation

/// 异常检出阈值（**单一真相源**，DESIGN §6.2）。可 `Codable`，允许 `AppConfig` 覆盖。
///
/// M5 补齐 6 类规则；判据一旦改动即视为新口径版本（`version`），
/// 避免「拿两把尺子量同一批数据」。
struct Thresholds: Codable, Equatable {
    /// 单 turn 积分的绝对下限（超过才有资格算「高耗」）。
    var creditAbsolute: Double
    /// 单 turn 延迟的绝对下限（毫秒）。
    var slowCallAbsoluteMs: Int
    /// 分位基线（P95）。
    var percentile: Double
    /// 计算分位基线所需的最少样本数；不足则只按绝对阈值判定。
    var minTurnsForPercentile: Int
    /// 判为 `high` 的倍数（超过阈值该倍数）。
    var highMultiple: Double

    // —— M5 事件级规则阈值 ——
    /// 同一 turn 内同工具/同操作重复次数上限（循环震荡）。
    var loopRepeatN: Int
    /// 同一 turn 内动作事件总量上限（上下文膨胀：不靠单一工具重复，而是总交互量过大）。
    var turnActionBloatN: Int
    /// 同一 turn 内压缩/consolidate 类事件频次上限（上下文抖动）。
    var compactFreqN: Int
    /// 同一 turn 内同工具连续失败次数下限（失败重试风暴）。
    var retryStormN: Int
    /// 遗忘风暴：时间桶宽度（毫秒）。
    var expireWindowMs: Int
    /// 遗忘风暴：单桶内 EXPIRE 类事件瞬时量下限。
    var expireBurstN: Int

    static let `default` = Thresholds(
        creditAbsolute: 12.0,
        slowCallAbsoluteMs: 180_000,
        percentile: 0.95,
        minTurnsForPercentile: 8,
        highMultiple: 2.0,
        loopRepeatN: 25,
        turnActionBloatN: 80,
        compactFreqN: 6,
        retryStormN: 5,
        expireWindowMs: 60_000,
        expireBurstN: 40
    )

    /// 口径版本（改判据即升版本）。
    static let version = "m5.r4"

    /// 取生效阈值：有覆盖用覆盖，否则默认。
    static func configured(_ overrides: Thresholds?) -> Thresholds {
        overrides ?? .default
    }
}

/// 事件级判定的最小投影（从 `events` 表取回，不含 payload 原文）。
struct EventFact {
    let ts: Int64
    let eventName: String?
    let toolName: String?
    let ioKind: String?
    let status: String?

    var isAction: Bool {
        eventName == "PostToolUse" || eventName == "PostToolUseFailure"
    }
    var failed: Bool { status == "failed" }
}

/// 单条异常检出。turn 级锚定（`refId = turn_id`），`evidence` 供主面板展开依据。
struct Anomaly: Identifiable {
    let id: String            // = turnId
    let scope: String         // turn（本轮全部规则 turn 级锚定）
    let refId: String         // 指向 turn_id
    let kind: String          // 6 类之一
    let severity: String      // warn / high
    let turn: TurnSummary
    var evidence: [String: Any] = [:]

    var kindLabel: String { AnomalyDetector.kindLabel(kind) }
}

/// 异常类型的中文标签（`Anomaly` 与落库读回的 `FindingRow` 共用单一来源）。
extension AnomalyDetector {
    /// 当前数据源（Qoder hook + transcript）**真正能采到信号**的异常类型白名单。
    /// 反复压缩 / 突然失忆 依赖 compact / expire 事件，Qoder 不上报 → 不列入、不显示。
    /// 将来适配器能采到对应事件（或 token 字段）时，把 kind 加进来即可重新启用。
    static let detectableKinds: Set<String> = [
        "credit_spike", "slow_call", "loop_thrash", "context_bloat", "retry_storm"
    ]

    static func kindLabel(_ kind: String) -> String {
        switch kind {
        case "credit_spike": return "太费钱"
        case "slow_call": return "太慢"
        case "loop_thrash": return "原地打转"
        case "context_bloat": return "上下文膨胀"
        case "frequent_compact": return "反复压缩"
        case "retry_storm": return "反复失败"
        case "expire_storm": return "突然失忆"
        default: return kind
        }
    }
}

/// 规则检出。**基线一律在单个 Agent 内计算**（D14）——
/// 跨 Agent 共用基线会把某个 Agent 的常态误判成另一个的异常。
enum AnomalyDetector {
    // MARK: 轻量：悬浮窗角标（今日 2 规则）

    static func detect(turns: [TurnSummary], thresholds: Thresholds = .default) -> [Anomaly] {
        var anomalies: [Anomaly] = []
        let byAgent = Dictionary(grouping: turns.filter { $0.ended }, by: { $0.ide })

        for (_, list) in byAgent {
            let enoughSamples = list.count >= thresholds.minTurnsForPercentile
            let creditBaseline = enoughSamples
                ? Percentiles.value(list.map(\.credits), thresholds.percentile) : 0
            let latencyBaseline = enoughSamples
                ? Percentiles.value(list.map { Double($0.latencyMs) }, thresholds.percentile) : 0

            for turn in list {
                if turn.credits > thresholds.creditAbsolute, turn.credits > creditBaseline {
                    anomalies.append(Anomaly(
                        id: turn.turnId, scope: "turn", refId: turn.turnId,
                        kind: "credit_spike",
                        severity: turn.credits > thresholds.creditAbsolute * thresholds.highMultiple ? "high" : "warn",
                        turn: turn,
                        evidence: [
                            "credits": turn.credits,
                            "threshold": thresholds.creditAbsolute,
                            "baseline_p95": creditBaseline,
                            "agentTurns": list.count,
                        ]
                    ))
                }
                if turn.latencyMs > thresholds.slowCallAbsoluteMs, Double(turn.latencyMs) > latencyBaseline {
                    anomalies.append(Anomaly(
                        id: turn.turnId, scope: "turn", refId: turn.turnId,
                        kind: "slow_call",
                        severity: turn.latencyMs > Int(Double(thresholds.slowCallAbsoluteMs) * thresholds.highMultiple) ? "high" : "warn",
                        turn: turn,
                        evidence: [
                            "latency_ms": turn.latencyMs,
                            "threshold": thresholds.slowCallAbsoluteMs,
                            "baseline_p95": latencyBaseline,
                            "agentTurns": list.count,
                        ]
                    ))
                }
            }
        }
        return sort(anomalies)
    }

    // MARK: 全量：M5 六类（含事件级规则），供落库

    /// `eventsByTurn`：`turn_id → 该轮事件`。仅对已 `Stop` 的轮次评估，避免把进行中的轮误判。
    static func analyze(turns: [TurnSummary],
                        eventsByTurn: [String: [EventFact]],
                        thresholds: Thresholds = .default) -> [Anomaly] {
        var anomalies = detect(turns: turns, thresholds: thresholds)
        let endedIds = Set(turns.filter { $0.ended }.map(\.turnId))

        for turn in turns where endedIds.contains(turn.turnId) {
            guard let events = eventsByTurn[turn.turnId], !events.isEmpty else { continue }
            let sorted = events.sorted { $0.ts < $1.ts }
            appendLoopThrash(turn: turn, events: sorted, thresholds: thresholds, into: &anomalies)
            appendContextBloat(turn: turn, events: sorted, thresholds: thresholds, into: &anomalies)
            appendFrequentCompact(turn: turn, events: sorted, thresholds: thresholds, into: &anomalies)
            appendRetryStorm(turn: turn, events: sorted, thresholds: thresholds, into: &anomalies)
        }
        appendExpireStorm(turns: turns, eventsByTurn: eventsByTurn,
                          thresholds: thresholds, into: &anomalies)
        // 只保留当前数据源真能采到信号的异常类型（反复压缩 / 突然失忆 靠 compact/expire 事件，
        // 而 Qoder 不上报这类事件 → 不显示；将来能采到了，加进 detectableKinds 即可重新启用）。
        return sort(anomalies.filter { detectableKinds.contains($0.kind) })
    }

    // MARK: 事件级规则

    /// 循环震荡：同 turn 内同工具（含 io_kind）重复动作次数 > loopRepeatN。
    private static func appendLoopThrash(turn: TurnSummary, events: [EventFact],
                                         thresholds: Thresholds, into out: inout [Anomaly]) {
        var counts: [String: Int] = [:]
        for event in events where event.isAction {
            let key = "\(event.toolName ?? "-")|\(event.ioKind ?? "-")"
            counts[key, default: 0] += 1
        }
        guard let top = counts.max(by: { $0.value < $1.value }), top.value > thresholds.loopRepeatN else { return }
        out.append(Anomaly(
            id: turn.turnId, scope: "turn", refId: turn.turnId,
            kind: "loop_thrash",
            severity: top.value > thresholds.loopRepeatN * 2 ? "high" : "warn",
            turn: turn,
            evidence: ["tool": top.key, "repeat": top.value, "threshold": thresholds.loopRepeatN]
        ))
    }

    /// 上下文膨胀：单 turn 内动作事件总量 > turnActionBloatN。不靠单一工具重复，
    /// 而是总交互量过大→早期关键信息被大量工具返回淹没、注意力稀释（《上下文记忆管理》信噪比/腐烂）。
    private static func appendContextBloat(turn: TurnSummary, events: [EventFact],
                                           thresholds: Thresholds, into out: inout [Anomaly]) {
        let actions = events.filter { $0.isAction }.count
        guard actions > thresholds.turnActionBloatN else { return }
        out.append(Anomaly(
            id: turn.turnId, scope: "turn", refId: turn.turnId,
            kind: "context_bloat",
            severity: actions > thresholds.turnActionBloatN * 2 ? "high" : "warn",
            turn: turn,
            evidence: ["actions": actions, "threshold": thresholds.turnActionBloatN]
        ))
    }

    /// 频繁压缩：turn 内压缩/consolidate 类事件频次 > compactFreqN。
    private static func appendFrequentCompact(turn: TurnSummary, events: [EventFact],
                                              thresholds: Thresholds, into out: inout [Anomaly]) {
        let hits = events.filter { isCompact($0) }.count
        guard hits > thresholds.compactFreqN else { return }
        out.append(Anomaly(
            id: turn.turnId, scope: "turn", refId: turn.turnId,
            kind: "frequent_compact",
            severity: hits > thresholds.compactFreqN * 2 ? "high" : "warn",
            turn: turn,
            evidence: ["compact_hits": hits, "threshold": thresholds.compactFreqN]
        ))
    }

    /// 重试风暴：turn 内同工具连续失败 ≥ retryStormN。
    private static func appendRetryStorm(turn: TurnSummary, events: [EventFact],
                                         thresholds: Thresholds, into out: inout [Anomaly]) {
        var maxRun = 0
        var runTool = ""
        var currentTool: String?
        var currentRun = 0
        for event in events where event.isAction {
            if event.failed {
                if currentTool == event.toolName {
                    currentRun += 1
                } else {
                    currentTool = event.toolName
                    currentRun = 1
                }
                if currentRun > maxRun { maxRun = currentRun; runTool = event.toolName ?? "-" }
            } else {
                currentTool = nil
                currentRun = 0
            }
        }
        guard maxRun >= thresholds.retryStormN else { return }
        out.append(Anomaly(
            id: turn.turnId, scope: "turn", refId: turn.turnId,
            kind: "retry_storm",
            severity: maxRun >= thresholds.retryStormN * 2 ? "high" : "warn",
            turn: turn,
            evidence: ["tool": runTool, "consecutive_failures": maxRun, "threshold": thresholds.retryStormN]
        ))
    }

    /// 遗忘风暴：跨窗口把 EXPIRE 类事件按时间桶聚合，某桶瞬时量 > expireBurstN 即命中，
    /// 锚定到该桶末条事件所属的轮次（保持单一 turn 级落库口径）。
    private static func appendExpireStorm(turns: [TurnSummary],
                                          eventsByTurn: [String: [EventFact]],
                                          thresholds: Thresholds, into out: inout [Anomaly]) {
        let turnById = Dictionary(uniqueKeysWithValues: turns.map { ($0.turnId, $0) })
        var expired: [(ts: Int64, turnId: String)] = []
        for (turnId, events) in eventsByTurn {
            for event in events where isExpire(event) {
                expired.append((event.ts, turnId))
            }
        }
        guard expired.count > thresholds.expireBurstN else { return }
        let sorted = expired.sorted { $0.ts < $1.ts }
        var bucketStart = sorted[0].ts
        var bucket: [(ts: Int64, turnId: String)] = []
        for item in sorted {
            if item.ts - bucketStart > Int64(thresholds.expireWindowMs) {
                if bucket.count > thresholds.expireBurstN, let last = bucket.last,
                   let turn = turnById[last.turnId] {
                    emitExpire(turn: turn, count: bucket.count, thresholds: thresholds, into: &out)
                }
                bucketStart = item.ts
                bucket.removeAll()
            }
            bucket.append(item)
        }
        if bucket.count > thresholds.expireBurstN, let last = bucket.last,
           let turn = turnById[last.turnId] {
            emitExpire(turn: turn, count: bucket.count, thresholds: thresholds, into: &out)
        }
    }

    private static func emitExpire(turn: TurnSummary, count: Int,
                                   thresholds: Thresholds, into out: inout [Anomaly]) {
        out.append(Anomaly(
            id: turn.turnId, scope: "turn", refId: turn.turnId,
            kind: "expire_storm",
            severity: count > thresholds.expireBurstN * 2 ? "high" : "warn",
            turn: turn,
            evidence: ["burst": count, "window_ms": thresholds.expireWindowMs,
                       "threshold": thresholds.expireBurstN]
        ))
    }

    // MARK: 关键字判定（认不出即不命中，不猜）

    private static func isCompact(_ event: EventFact) -> Bool {
        matches(event, ["compact", "compress", "consolidate", "summarize", "condense"])
    }

    private static func isExpire(_ event: EventFact) -> Bool {
        matches(event, ["expire", "forget", "decay", "evict"])
    }

    private static func matches(_ event: EventFact, _ keywords: [String]) -> Bool {
        let haystacks = [event.toolName, event.ioKind, event.eventName]
            .compactMap { $0?.lowercased() }
        return keywords.contains { keyword in haystacks.contains(where: { $0.contains(keyword) }) }
    }

    /// 影响大者优先：先按严重度，再按积分 / 延迟。
    private static func sort(_ anomalies: [Anomaly]) -> [Anomaly] {
        anomalies.sorted { lhs, rhs in
            if lhs.severity != rhs.severity { return lhs.severity == "high" }
            return lhs.turn.credits > rhs.turn.credits
        }
    }
}
