import SwiftUI

/// 工作区：排行（按 Agent 分组，点击切该工作区视角）+ 延迟分位表（单 Agent 内，D14）。
struct WorkspacesPage: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PanelUI.gridGutter) {
                if let focus = state.focus { focusBar(focus) }

                TUACard {
                    SectionHeader(text: "工作区排行 · 按 Agent（各自单位）")
                    if state.workspaceGroups.isEmpty {
                        EmptyState(text: "尚无工作区数据", systemImage: "folder")
                    } else {
                        ForEach(state.workspaceGroups) { group in
                            VStack(alignment: .leading, spacing: 4) {
                                agentTag(group.ide)
                                ForEach(group.items) { item in row(item) }
                            }
                            .padding(.top, 4)
                        }
                    }
                }

                TUACard {
                    SectionHeader(text: "延迟分位 · 单 Agent 内（今日）",
                                  trailing: AnyView(Text("P50/P90/P95/P99").font(.system(size: 9)).foregroundStyle(TUA.faint)))
                    if state.agentLatency.isEmpty {
                        EmptyState(text: "样本不足 · 今日暂无可分位的轮次", systemImage: "clock")
                    } else {
                        percentileTable
                    }
                    Text("口径：分位在单个 Agent 内计算，跨 Agent 不共用基线（D14）")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                }
            }
            .padding(PanelUI.pagePadding)
        }
        .onAppear { state.loadSnapshots() }
    }

    private var percentileTable: some View {
        VStack(spacing: 0) {
            headerRow(["Agent", "轮次", "P50", "P90", "P95", "P99", "积分P95"])
            Divider().overlay(TUA.border)
            ForEach(state.agentLatency) { stats in
                metricRow([
                    stats.label,
                    "\(stats.turns)",
                    TUA.duration(stats.p50),
                    TUA.duration(stats.p90),
                    TUA.duration(stats.p95),
                    TUA.duration(stats.p99),
                    TUA.number(stats.creditP95),
                ], ide: stats.ide)
                Divider().overlay(TUA.border.opacity(0.6))
            }
        }
    }

    private func headerRow(_ titles: [String]) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(titles.enumerated()), id: \.offset) { _, title in
                Text(title)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(TUA.faint)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(.vertical, 6)
    }

    private func metricRow(_ values: [String], ide: String) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                HStack(spacing: 4) {
                    if index == 0 {
                        Circle().fill(TUA.agentColor(ide)).frame(width: 5, height: 5)
                    }
                    Text(value)
                        .font(.system(size: 11, design: index == 0 ? .default : .monospaced))
                        .foregroundStyle(index == 0 ? TUA.ink : TUA.muted)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(.vertical, 6)
    }

    private func agentTag(_ ide: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(TUA.agentColor(ide)).frame(width: 5, height: 5)
            Text(ide.isEmpty ? "未知" : ide)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(TUA.faint)
        }
    }

    private func row(_ item: WorkspaceStats) -> some View {
        Button {
            state.focusWorkspace(id: item.workspaceId, name: item.name)
        } label: {
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
                Image(systemName: "chevron.right")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
        }
        .buttonStyle(.plain)
    }

    private func focusBar(_ focus: WorkspaceFocus) -> some View {
        TUACard(padding: 10) {
            HStack(spacing: 6) {
                Image(systemName: "scope").font(.system(size: 10)).foregroundStyle(TUA.muted)
                Text("当前视角：\(focus.name)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(TUA.ink)
                    .lineLimit(1)
                Spacer()
                Button("返回全部") { state.clearFocus() }
                    .buttonStyle(.plain)
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.muted)
            }
        }
    }
}
