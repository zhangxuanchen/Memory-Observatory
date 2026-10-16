import SwiftUI

/// 总览：每个 Agent 一块——顶部是该 Agent 今日总积分（同 Agent 内各工作区相加，D14 允许），
/// 下面按工作区维度逐条列出消耗。**绝无跨 Agent 合计**（D14）。
struct OverviewPage: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PanelUI.gridGutter) {
                if state.workspaceGroups.isEmpty {
                    TUACard {
                        EmptyState(text: "尚无用量 · 接入 IDE 后自动统计", systemImage: "chart.bar")
                    }
                } else {
                    agentCards
                }
                neutralMetrics
            }
            .padding(PanelUI.pagePadding)
        }
    }

    private var agentCards: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: PanelUI.gridGutter)],
                  spacing: PanelUI.gridGutter) {
            ForEach(state.workspaceGroups) { group in
                agentCard(group)
            }
        }
    }

    private func agentCard(_ group: AgentWorkspaceGroup) -> some View {
        let totalCredits = group.items.reduce(0) { $0 + $1.credits }
        let totalTurns = group.items.reduce(0) { $0 + $1.turns }
        let events = state.today.agents.first { $0.id == group.ide }?.events ?? 0
        let anomaly = todayAnomalyCount(group.ide)
        let hours = state.trend.first { $0.id == group.ide }?.hours ?? []
        return TUACard {
            HStack(spacing: 6) {
                Circle().fill(TUA.agentColor(group.ide)).frame(width: 7, height: 7)
                Text(group.ide.isEmpty ? "未知" : group.ide)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(TUA.ink)
                Spacer()
            }
            StatMetric(title: "今日总积分", value: TUA.number(totalCredits),
                       unit: group.ide.isEmpty ? "" : group.ide)
            HStack(spacing: 18) {
                compact("轮次", "\(totalTurns)")
                compact("事件", "\(events)")
                compact("异常", "\(anomaly)", tone: anomaly > 0 ? TUA.warn : TUA.faint)
                Spacer()
            }
            if hours.contains(where: { $0 > 0 }) {
                SparklineView(values: hours, color: TUA.agentColor(group.ide))
                    .frame(height: 34)
            }
            Divider().overlay(TUA.border)
            Text("按工作区")
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
            ForEach(group.items.prefix(6)) { item in workspaceRow(item) }
        }
    }

    private func workspaceRow(_ item: WorkspaceStats) -> some View {
        HStack(spacing: 8) {
            Text(item.name)
                .font(.system(size: 12))
                .foregroundStyle(TUA.ink)
                .lineLimit(1)
            Spacer()
            Text(TUA.number(item.credits))
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(TUA.ink)
            Text("· \(item.turns) 轮")
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
    }

    /// 中性计数（不涉及计费，可合计）。
    private var neutralMetrics: some View {
        TUACard {
            HStack(spacing: 28) {
                StatMetric(title: "活跃工作区", value: "\(state.today.workspaces)")
                StatMetric(title: "今日异常", value: "\(state.anomalies.count)",
                           tone: state.anomalies.isEmpty ? TUA.ink : TUA.warn)
                StatMetric(title: "落库异常(7天)", value: "\(state.findings.count)")
                Spacer()
            }
        }
    }

    private func compact(_ title: String, _ value: String, tone: Color = TUA.ink) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                .foregroundStyle(tone)
            Text(title)
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
        }
    }

    private func todayAnomalyCount(_ ide: String) -> Int {
        state.anomalies.filter { $0.turn.ide == ide }.count
    }
}
