import Foundation

/// 把一条异常翻译成人能听懂的**四段式**：现象 → 原因 → 会造成什么问题 → 怎么改进。
///
/// 单一真相源：悬浮窗内联展开与主面板异常页共用，话术一致。
/// 只依据 `AnomalyDetector` 落进 `evidence` 的实测数字组织语言，认不出的类型如实回退。
struct AnomalyInsight {
    let symptom: String   // 现象：到底发生了什么（含实测数字与阈值对比）
    let cause: String     // 原因：为什么会这样
    let impact: String    // 后果：会造成什么问题（如 token 反复消耗浪费）
    let fix: String       // 改进：用户如何改

    static func explain(kind: String,
                        evidence: [String: Any],
                        credits: Double,
                        latencyMs: Int) -> AnomalyInsight {
        switch kind {
        case "credit_spike":
            let base = num(evidence["baseline_p95"]) ?? 0
            let th = num(evidence["threshold"]) ?? 5
            let vs = base > 0 ? "平时 95% 的轮次也就 \(fmt(base))" : "绝对下限 \(fmt(th))"
            return AnomalyInsight(
                symptom: "这一轮花了 \(fmt(credits)) 积分，明显高于\(vs)。",
                cause: "上下文越滚越大，或同一件事被反复重做，把 token 顶了上去。",
                impact: "积分/费用直接上升；会话越长每一步响应也越慢。",
                fix: "任务告一段落就开新会话；别整块贴大文件/日志；一次太重的活拆成几步。"
            )

        case "slow_call":
            let th = num(evidence["threshold"]) ?? 60_000
            return AnomalyInsight(
                symptom: "这一轮耗时 \(dur(latencyMs))，超过平时节奏，也越过 \(dur(Int(th))) 的下限。",
                cause: "某一步工具调用卡住或在反复等待（网络请求、编译、大仓检索最常见）。",
                impact: "你在干等，整体吞吐下降；等待中还重试会连带多烧 token。",
                fix: "定位耗时最长那一步，给它加超时或改成分批/增量；能缓存的结果别每次重算。"
            )

        case "loop_thrash":
            let tool = (str(evidence["tool"]) ?? "").split(separator: "|").first.map(String.init) ?? "同一工具"
            let repeatN = Int(num(evidence["repeat"]) ?? 0)
            let th = Int(num(evidence["threshold"]) ?? 0)
            return AnomalyInsight(
                symptom: "这一轮里「\(tool)」重复了 \(repeatN) 次（超过 \(th) 次就算异常）。",
                cause: "在原地打转——反复读同一文件、用同样参数重试同一操作，没拿到新进展。",
                impact: "每重复一次就多带一轮上下文，token 反复消耗浪费，任务却不动。",
                fix: "连续两次没进展就停下重新规划；换思路或先确认前置条件，别用相同操作硬碰。"
            )

        case "frequent_compact":
            let hits = Int(num(evidence["compact_hits"]) ?? 0)
            let th = Int(num(evidence["threshold"]) ?? 0)
            return AnomalyInsight(
                symptom: "这一轮上下文被压缩了 \(hits) 次（阈值 \(th) 次）。",
                cause: "单轮塞进去的东西太多，撑不住被反复压缩。",
                impact: "压缩会丢信息导致跑偏、重做，同时把成本推高。",
                fix: "把大任务拆到多个会话，减少一次性载入；及时清理无关的历史。"
            )

        case "retry_storm":
            let tool = str(evidence["tool"]) ?? "同一工具"
            let fails = Int(num(evidence["consecutive_failures"]) ?? 0)
            let th = Int(num(evidence["threshold"]) ?? 0)
            return AnomalyInsight(
                symptom: "「\(tool)」在这一轮连续失败了 \(fails) 次（\(th) 次即判定异常）。",
                cause: "不是偶发抖动，而是稳定的前置问题（依赖缺失、路径/权限、命令写错）。",
                impact: "每次失败重试都在耗时间和 token，越试越亏。",
                fix: "先读清楚报错根因再动手，把前置问题修好再继续，别盲目重试。"
            )

        case "expire_storm":
            let burst = Int(num(evidence["burst"]) ?? 0)
            let win = Int(num(evidence["window_ms"]) ?? 0) / 1000
            let th = Int(num(evidence["threshold"]) ?? 0)
            return AnomalyInsight(
                symptom: "\(win) 秒内集中出现了 \(burst) 条记忆/上下文过期（阈值 \(th) 条）。",
                cause: "大量条目同时到期，短期上下文集中失效。",
                impact: "Agent 会突然「忘记」关键约束，进而跑偏或重做，重复消耗。",
                fix: "把长期有效的约束固化进规则/记忆，别依赖易失的短期上下文。"
            )

        default:
            return AnomalyInsight(
                symptom: "检出一次「\(AnomalyDetector.kindLabel(kind))」异常。",
                cause: "该类型暂无细分口径。",
                impact: "影响未知，建议结合最近事件判断。",
                fix: "到最近事件里看这一轮具体做了什么。"
            )
        }
    }

    // MARK: 取值/格式化（Core 层不依赖 UI token）

    private static func num(_ value: Any?) -> Double? {
        if let d = value as? Double { return d }
        if let i = value as? Int { return Double(i) }
        if let n = value as? NSNumber { return n.doubleValue }
        return nil
    }

    private static func str(_ value: Any?) -> String? { value as? String }

    private static func fmt(_ v: Double) -> String {
        v >= 100 ? String(format: "%.0f", v) : String(format: "%.2f", v)
    }

    private static func dur(_ ms: Int) -> String {
        if ms >= 60_000 { return String(format: "%.1f 分钟", Double(ms) / 60_000) }
        return String(format: "%.1f 秒", Double(ms) / 1000)
    }
}
