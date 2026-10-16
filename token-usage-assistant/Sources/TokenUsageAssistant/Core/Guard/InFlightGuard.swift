import Foundation

/// 护航（L4 实时事中干预）阈值——**独立于事后 M5 的 `Thresholds`（`m5.r4`）**。
///
/// 事后阈值回答「这轮有多异常」，护航阈值回答「现在要不要叫停」，后者必须更敏感，
/// 故单独一套阈值、单独口径版本 `m6.r1`（沿用「不拿两把尺子量同一批数据」纪律）。
struct GuardThresholds: Codable, Equatable {
    /// 同 turn 内同工具（含 io_kind）动作数下限 → ask（比事后 25 更敏感）。
    var loopRepeatN: Int
    /// 同 turn 内动作总量下限 → ask。
    var turnActionBloatN: Int
    /// 同工具连续失败次数下限 → ask。
    var retryStormN: Int
    /// 熔断下限：同工具动作数 ≥ 此值且本轮已 ask 过仍继续 → deny。
    var breakerLoopN: Int
    /// 熔断（deny）总开关：默认关（误拦代价远大于漏拦）。
    var breakerEnabled: Bool
    /// 事前就绪度拦截阈值（0–15）：prompt 打分 < 此值即拦下重提。调低更宽松（只拦明显笼统的），当前 4。
    var promptReadinessBlockBelow: Double

    static let `default` = GuardThresholds(
        loopRepeatN: 15,
        turnActionBloatN: 80,
        retryStormN: 5,
        breakerLoopN: 30,
        breakerEnabled: false,
        promptReadinessBlockBelow: 4.0
    )

    /// 口径版本（改判据即升版本）。
    static let version = "m6.r1"

    static func configured(_ overrides: GuardThresholds?) -> GuardThresholds {
        overrides ?? .default
    }
}

/// 一次护航评估的结果。`decision` ∈ allow / ask / deny。
struct GuardOutcome {
    let decision: String
    let rule: String?
    let reason: String?
    let evidence: [String: Any]

    static let allow = GuardOutcome(decision: "allow", rule: nil, reason: nil, evidence: [:])
}

/// 适配器 POST /api/v1/guard 携带的 PreToolUse 摘要（App 据此定位进行中 turn）。
struct GuardRequest {
    let sessionId: String?
    let toolName: String?
    let ioKind: String?
    let ide: String?

    static func from(_ dict: [String: Any]) -> GuardRequest {
        func str(_ k: String) -> String? {
            guard let v = dict[k] as? String, !v.isEmpty else { return nil }
            return v
        }
        return GuardRequest(
            sessionId: str("session_id"), toolName: str("tool_name"),
            ioKind: str("io_kind"), ide: str("ide")
        )
    }
}

/// 事前拦截留痕读回行（供护航卡重启后展示待改写原文）。
struct PrecheckLog {
    let ts: Int64
    let sessionId: String?
    let ide: String?
    let prompt: String
    let reason: String
    let evidenceJSON: String
}

/// 适配器 POST /api/v1/precheck 携带的 UserPromptSubmit 摘要（提交时已知字段）。
struct PrecheckRequest {
    let sessionId: String?
    let requestId: String?
    let prompt: String?
    let cwd: String?
    let ide: String?

    static func from(_ dict: [String: Any]) -> PrecheckRequest {
        func str(_ k: String) -> String? {
            guard let v = dict[k] as? String, !v.isEmpty else { return nil }
            return v
        }
        return PrecheckRequest(
            sessionId: str("session_id"), requestId: str("request_set_id"),
            prompt: dict["prompt"] as? String, cwd: str("cwd"), ide: str("ide")
        )
    }
}

/// 一次事前评估结果。`decision` ∈ pass / block。
struct PrecheckOutcome {
    let decision: String
    let reason: String?
    let score: Double?
    let problems: [String]

    static let pass = PrecheckOutcome(decision: "pass", reason: nil, score: nil, problems: [])
}

/// in-flight 规则引擎（DESIGN 策划 §3.3）。对「进行中 turn」的动作事实流跑轻量规则，
/// 命中且未在本轮提醒过 → 出 ask；超熔断下限且熔断开 → deny。**纯规则、零 LLM**。
///
/// credit_burn_inflight（烧积分 ≥ 个人基线 P95 → ask）**本期待实现**：积分只在 Stop 落库、
/// 进行中 turn 实时读不到，且个人基线属 L5/M10——数据不支撑，不猜（诚实边界）。
enum InFlightGuard {
    static func evaluate(facts: [EventFact],
                         thresholds: GuardThresholds = .default,
                         alreadyRaised: (String) -> Bool) -> GuardOutcome {
        let actions = facts.filter { $0.isAction }.sorted { $0.ts < $1.ts }
        guard !actions.isEmpty else { return .allow }

        // 1) 原地打转（含熔断）。
        if let hit = loopHit(actions, thresholds: thresholds),
           hit.repeat >= thresholds.loopRepeatN {
            let asked = alreadyRaised("loop_thrash_inflight")
            // 熔断优先：开、且已达熔断下限、且本轮已问过仍继续 → deny。
            if thresholds.breakerEnabled, hit.repeat >= thresholds.breakerLoopN, asked {
                return GuardOutcome(
                    decision: "deny", rule: "loop_thrash_breaker",
                    reason: "TUA 护航熔断：本轮已对「\(hit.label)」重复 \(hit.repeat) 次且提醒过仍在继续，先拦下止损。",
                    evidence: ["tool": hit.key, "repeat": hit.repeat, "breaker": thresholds.breakerLoopN]
                )
            }
            if !asked {
                return GuardOutcome(
                    decision: "ask", rule: "loop_thrash_inflight",
                    reason: "TUA 护航：本轮已对「\(hit.label)」重复 \(hit.repeat) 次（阈值 \(thresholds.loopRepeatN)），还在原地打转，要继续吗？",
                    evidence: ["tool": hit.key, "repeat": hit.repeat, "threshold": thresholds.loopRepeatN]
                )
            }
            // 已问过、熔断未开 → 不再骚扰，继续评估其它模式。
        }

        // 2) 上下文膨胀。
        if actions.count >= thresholds.turnActionBloatN,
           !alreadyRaised("context_bloat_inflight") {
            return GuardOutcome(
                decision: "ask", rule: "context_bloat_inflight",
                reason: "TUA 护航：本轮已发起 \(actions.count) 个动作（阈值 \(thresholds.turnActionBloatN)），上下文可能在膨胀，要继续吗？",
                evidence: ["actions": actions.count, "threshold": thresholds.turnActionBloatN]
            )
        }

        // 3) 失败重试风暴。
        let (run, runTool) = longestFailureRun(actions)
        if run >= thresholds.retryStormN, !alreadyRaised("retry_storm_inflight") {
            return GuardOutcome(
                decision: "ask", rule: "retry_storm_inflight",
                reason: "TUA 护航：本轮「\(runTool)」已连续失败 \(run) 次（阈值 \(thresholds.retryStormN)），还要继续重试吗？",
                evidence: ["tool": runTool, "consecutive_failures": run, "threshold": thresholds.retryStormN]
            )
        }

        return .allow
    }

    // MARK: - 单规则计数（与 M5 AnomalyDetector 同口径，仅阈值不同）

    private static func loopHit(_ actions: [EventFact], thresholds: GuardThresholds)
        -> (key: String, label: String, repeat: Int)? {
        var counts: [String: Int] = [:]
        for action in actions {
            counts["\(action.toolName ?? "-")|\(action.ioKind ?? "-")", default: 0] += 1
        }
        guard let top = counts.max(by: { $0.value < $1.value }) else { return nil }
        let label = String(top.key.split(separator: "|").first ?? Substring(top.key))
        return (top.key, label, top.value)
    }

    private static func longestFailureRun(_ actions: [EventFact]) -> (Int, String) {
        var maxRun = 0
        var runTool = "-"
        var currentTool: String?
        var currentRun = 0
        for action in actions where action.isAction {
            if action.failed {
                if currentTool == action.toolName {
                    currentRun += 1
                } else {
                    currentTool = action.toolName
                    currentRun = 1
                }
                if currentRun > maxRun { maxRun = currentRun; runTool = action.toolName ?? "-" }
            } else {
                currentTool = nil
                currentRun = 0
            }
        }
        return (maxRun, runTool)
    }
}
