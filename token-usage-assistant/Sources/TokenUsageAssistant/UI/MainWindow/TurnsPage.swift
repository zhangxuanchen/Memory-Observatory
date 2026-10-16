import SwiftUI

/// 任务明细：turn 表格（Agent / 模型 / 积分 / 延迟 / 动作数 / 结果），按 Agent 筛选、
/// 时间窗切换；选中行右侧抽屉看构成与 prompt 摘要。**不跨 Agent 聚合**（D14）。
struct TurnsPage: View {
    @EnvironmentObject private var state: AppState

    @State private var window: TimeWindow = .today
    @State private var ideFilter: String? = nil
    @State private var rows: [TurnRow] = []
    @State private var selected: TurnRow?

    enum TimeWindow: String, CaseIterable, Identifiable {
        case today = "今日"
        case week = "近 7 天"
        case month = "近 30 天"
        var id: String { rawValue }
        var days: Int {
            switch self {
            case .today: return 1
            case .week: return 7
            case .month: return 30
            }
        }
        var startMillis: Int64 {
            switch self {
            case .today: return Database.startOfTodayMillis()
            default: return TUAEvent.nowMillis() - Int64(days) * 86_400_000
            }
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            mainColumn
            if let selected { detailDrawer(selected) }
        }
    }

    private var mainColumn: some View {
        VStack(alignment: .leading, spacing: 0) {
            filters
            Divider().overlay(TUA.border)
            if rows.isEmpty {
                EmptyState(text: "该窗口暂无任务 · 接入 IDE 或调整筛选后自动出现", systemImage: "list.bullet")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                scrollTable
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear(perform: reload)
        .onChange(of: window) { _ in reload() }
        .onChange(of: ideFilter) { _ in reload() }
        .onChange(of: state.findings.count) { _ in reload() }
    }

    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                ForEach(TimeWindow.allCases) { option in
                    FilterChip(title: option.rawValue, selected: window == option) {
                        window = option
                    }
                }
                Spacer()
                Text("\(rows.count) 轮")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(TUA.faint)
            }
            if !availableAgents.isEmpty {
                HStack(spacing: 8) {
                    FilterChip(title: "全部 Agent", selected: ideFilter == nil) { ideFilter = nil }
                    ForEach(availableAgents, id: \.self) { ide in
                        FilterChip(title: ide.isEmpty ? "未知" : ide, selected: ideFilter == ide) {
                            ideFilter = ide
                        }
                    }
                    Spacer()
                }
            }
        }
        .padding(PanelUI.pagePadding)
    }

    private var scrollTable: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                tableHeader
                Divider().overlay(TUA.border)
                ForEach(rows) { row in
                    tableRow(row)
                    Divider().overlay(TUA.border.opacity(0.5))
                }
            }
        }
    }

    private var tableHeader: some View {
        HStack(spacing: 8) {
            column("Agent", width: 84)
            column("模型", width: 120)
            column("工作区", width: 140)
            column("积分", width: 60, trailing: true)
            column("延迟", width: 64, trailing: true)
            column("动作", width: 44, trailing: true)
            column("状态", width: 52)
            Spacer()
        }
        .padding(.horizontal, PanelUI.pagePadding)
        .padding(.vertical, 8)
    }

    private func tableRow(_ row: TurnRow) -> some View {
        Button {
            selected = (selected?.id == row.id) ? nil : row
        } label: {
            HStack(spacing: 8) {
                HStack(spacing: 4) {
                    Circle().fill(TUA.agentColor(row.ide)).frame(width: 5, height: 5)
                    Text(row.ide.isEmpty ? "未知" : row.ide)
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.ink)
                        .lineLimit(1)
                }
                .frame(width: 84, alignment: .leading)
                Text(row.model ?? "-")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .lineLimit(1)
                    .frame(width: 120, alignment: .leading)
                Text(row.workspaceLabel)
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.ink)
                    .lineLimit(1)
                    .frame(width: 140, alignment: .leading)
                Text(TUA.number(row.credits))
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(TUA.ink)
                    .frame(width: 60, alignment: .trailing)
                Text(TUA.duration(row.latencyTotalMs))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .frame(width: 64, alignment: .trailing)
                Text("\(row.actionCount)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .frame(width: 44, alignment: .trailing)
                Text(row.ended ? "完成" : "进行")
                    .font(.system(size: 10))
                    .foregroundStyle(row.ended ? TUA.faint : TUA.warn)
                    .frame(width: 52, alignment: .leading)
                Spacer()
            }
            .padding(.horizontal, PanelUI.pagePadding)
            .padding(.vertical, 8)
            .background(selected?.id == row.id ? Color.black.opacity(0.04) : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func column(_ title: String, width: CGFloat, trailing: Bool = false) -> some View {
        Text(title)
            .font(.system(size: 10, weight: .medium))
            .foregroundStyle(TUA.faint)
            .frame(width: width, alignment: trailing ? .trailing : .leading)
    }

    private func detailDrawer(_ row: TurnRow) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("任务详情").font(.system(size: 13, weight: .semibold)).foregroundStyle(TUA.ink)
                Spacer()
                Button {
                    selected = nil
                } label: { Image(systemName: "xmark").font(.system(size: 11)).foregroundStyle(TUA.faint) }
                    .buttonStyle(.plain)
            }
            Divider().overlay(TUA.border)
            drawerField("轮次键", row.turnId)
            drawerField("Agent", row.ide.isEmpty ? "未知" : row.ide)
            drawerField("模型", row.model ?? "-")
            drawerField("工作区", row.workspaceLabel)
            drawerField("积分", TUA.number(row.credits))
            drawerField("延迟(总/峰)", "\(TUA.duration(row.latencyTotalMs)) / \(TUA.duration(row.latencyMaxMs))")
            drawerField("动作数", "\(row.actionCount)")
            drawerField("起止", "\(TUA.time(row.startedAt)) – \(row.ended ? TUA.time(row.endedAt) : "…")")
            drawerField("结果", row.outcome ?? "（无记录，不猜）")
            VStack(alignment: .leading, spacing: 4) {
                Text("用户提问").font(.system(size: 10)).foregroundStyle(TUA.faint)
                Text(row.prompt?.isEmpty == false ? row.prompt! : "（未捕获 prompt 原文）")
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: TUA.Radius.sm).fill(TUA.canvas))
            }
            Spacer()
        }
        .padding(16)
        .frame(width: 300)
        .frame(maxHeight: .infinity)
        .background(TUA.card)
        .overlay(Rectangle().frame(width: 1).foregroundColor(TUA.border), alignment: .leading)
    }

    private func drawerField(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 10)).foregroundStyle(TUA.faint)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(TUA.ink)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var availableAgents: [String] {
        var set = Set(state.today.agents.map(\.id))
        state.workspaceGroups.forEach { set.insert($0.ide) }
        return set.sorted()
    }

    private func reload() {
        rows = state.database.turnDetail(since: window.startMillis, ide: ideFilter, limit: 300)
        if let selected, !rows.contains(where: { $0.id == selected.id }) { self.selected = nil }
    }
}
