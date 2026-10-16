import Foundation

/// 分位计算（**单一真相源**）：异常检出的基线与主面板的延迟/积分分位表都走这里，
/// 避免「拿两把尺子量同一批数据」。始终在**单个 Agent 内**调用（D14）。
enum Percentiles {
    /// 线性插值分位（小样本友好）。p ∈ [0,1]。
    static func value(_ values: [Double], _ p: Double) -> Double {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        if sorted.count == 1 { return sorted[0] }
        let rank = p * Double(sorted.count - 1)
        let lower = Int(rank.rounded(.down))
        let upper = Int(rank.rounded(.up))
        if lower == upper { return sorted[lower] }
        let weight = rank - Double(lower)
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }
}
