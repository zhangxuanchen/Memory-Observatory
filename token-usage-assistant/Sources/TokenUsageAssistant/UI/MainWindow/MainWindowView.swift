import SwiftUI

/// 主面板（DESIGN §7.3 多页）：侧边栏极简导航 总览 / 工作区 / 任务明细 / 异常 / 设置。
/// 本轮 M4/M5 落地前五页；「使用习惯 / 建议」属 M6/M7，暂不列出以免空壳。
struct MainWindowView: View {
    @EnvironmentObject private var state: AppState
    @State private var page: MainPage = .overview

    enum MainPage: String, CaseIterable, Identifiable {
        case overview = "总览"
        case workspaces = "工作区"
        case turns = "任务明细"
        case findings = "异常"
        case settings = "设置"

        var id: String { rawValue }
        var symbol: String {
            switch self {
            case .overview: return "square.grid.2x2"
            case .workspaces: return "folder"
            case .turns: return "list.bullet"
            case .findings: return "exclamationmark.triangle"
            case .settings: return "gearshape"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detail
                .background(TUA.canvas)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            state.loadSnapshots()
                            state.runDetection(days: 7)
                        } label: { Label("刷新", systemImage: "arrow.clockwise") }
                            .help("刷新快照并重跑检出")
                    }
                }
        }
        .navigationSplitViewColumnWidth(min: 168, ideal: 188, max: 220)
        .onAppear { state.loadSnapshots() }
    }

    private var sidebar: some View {
        List(selection: $page) {
            ForEach(MainPage.allCases) { item in
                Label { Text(item.rawValue) } icon: { Image(systemName: item.symbol) }
                    .tag(item)
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) { footer }
    }

    private var detail: some View {
        Group {
            switch page {
            case .overview: OverviewPage()
            case .workspaces: WorkspacesPage()
            case .turns: TurnsPage()
            case .findings: FindingsPage()
            case .settings: SettingsPage()
            }
        }
        .environmentObject(state)
    }

    private var footer: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(state.receiverRunning ? TUA.success : TUA.error)
                .frame(width: 6, height: 6)
            Text(state.receiverMessage)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(TUA.faint)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}
