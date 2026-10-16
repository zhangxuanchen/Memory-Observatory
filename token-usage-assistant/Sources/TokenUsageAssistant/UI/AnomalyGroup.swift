import Foundation

/// 今日异常按类型聚合的一组：列表里一类只占一行，详情弹窗一页展示一类。
struct AnomalyGroup: Identifiable {
    let kind: String
    let findings: [FindingRow]

    var id: String { kind }
    var count: Int { findings.count }
    var kindLabel: String { AnomalyDetector.kindLabel(kind) }
    var isHigh: Bool { findings.contains { $0.isHigh } }
    var worstCredits: Double { findings.map(\.credits).max() ?? 0 }

    /// 代表条目：high 优先、再取积分最高的一条，作为四段式解读口径。
    var representative: FindingRow {
        findings.max(by: {
            if $0.isHigh != $1.isHigh { return !$0.isHigh }
            return $0.credits < $1.credits
        }) ?? findings[0]
    }

    /// 去重后的涉及工作区（保持首次出现顺序）。
    var workspaces: [String] {
        var seen = Set<String>()
        var out: [String] = []
        for f in findings where !f.workspaceLabel.isEmpty && !seen.contains(f.workspaceLabel) {
            seen.insert(f.workspaceLabel)
            out.append(f.workspaceLabel)
        }
        return out
    }

    /// 按工作区分组的命中轮数（保持首次出现顺序），供顶部逐工作区高亮展示。
    var workspaceCounts: [(name: String, ide: String, count: Int)] {
        var order: [String] = []
        var counts: [String: (ide: String, count: Int)] = [:]
        for f in findings {
            let name = f.workspaceLabel
            if counts[name] == nil {
                counts[name] = (f.ide, 0)
                order.append(name)
            }
            counts[name]?.count += 1
        }
        return order.map { (name: $0, ide: counts[$0]!.ide, count: counts[$0]!.count) }
    }

    var workspaceSummary: String {
        switch workspaces.count {
        case 0: return "未知工作区"
        case 1: return workspaces[0]
        default: return "\(workspaces[0]) 等 \(workspaces.count) 个工作区"
        }
    }

    /// 把一批落库异常按类型聚合成组，按「是否高危 → 条数 → 积分」排序。
    static func groups(from findings: [FindingRow]) -> [AnomalyGroup] {
        Dictionary(grouping: findings, by: { $0.kind })
            .map { AnomalyGroup(kind: $0.key, findings: $0.value) }
            .sorted {
                if $0.isHigh != $1.isHigh { return $0.isHigh }
                if $0.count != $1.count { return $0.count > $1.count }
                // 同级同条数时用稳定的 kind 兼底，不用易变的 worstCredits（积分随事件流刷新会上下跳）。
                return $0.kind < $1.kind
            }
    }
}
