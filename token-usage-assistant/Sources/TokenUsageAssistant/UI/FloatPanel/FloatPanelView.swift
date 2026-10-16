import AppKit
import SwiftUI

/// 悬浮窗内容：
/// ① 今日用量 · 按 Agent（含逐小时迷你趋势，各自单位不合计）
/// ② 异常角标（高耗 / 高延迟，点击展开列表并直达工作区）
/// ③ 工作区排行 · 按 Agent（点击切换工作区视角）
/// 最近事件逐行带 事件 / 动作 / 耗时（参照 memory-observatory Agent 的动作链：
/// 每步显示 op · 时间 · latency，未知取值原样透出不假装认识）。
/// 折叠 = 状态点 + 今日积分摘要。标题栏可拖拽、双击收起。
struct FloatPanelView: View {
    @EnvironmentObject private var state: AppState

    @State private var collapsed = false
    @State private var showAnomalies = false
    /// 异常列表里点中的类型：非空 = 右扩列从「异常列表」切到该类型的「异常详情」（同工作区详情那样向右滑出）；nil = 显示列表。
    @State private var selectedAnomalyKind: String?
    /// 收起为浮球：✕ 点击后缩成可拖动小圆，点圆展开。
    @State private var minimized = false
    /// 收起前记住的窗口 frame，展开时还原。
    @State private var preMinimizeFrame: NSRect?
    private let ballSize: CGFloat = 62
    @State private var window: NSWindow?
    /// 卡片内容的自然高度（填满前测量）；折叠态用它把窗口贴住摘要。
    @State private var naturalHeight: CGFloat = 0
    /// 展开态高度（用户拉边缘调过 / 上次启动留下），折叠↔展开切换时还原。
    @State private var expandedBodyHeight: CGFloat?
    /// 展开态是否已把「记忆高度」落到位：进入展开态时还原一次即置真，之后高度完全交给用户拉边缘，
    /// updatePanelSize 不再按记忆值锁回——否则每次内容重新测量都会把窗口拽回旧高，导致「下拉扩展高度」失效。
    @State private var appliedExpandedHeight = false
    /// 展开区可视高度（滚动视口），用于推算最近事件能塞下几行。
    @State private var viewportHeight: CGFloat = 0
    /// 最近事件条数自适应的防抖任务。
    @State private var recentSyncTask: DispatchWorkItem?
    /// 展开详情前的主页区域宽度（由 HomeWidthKey 在未展开时持续测量记录）；展开时把主页列钉在此宽、窗口向右扩 popupWidth。
    @State private var homeWidth: CGFloat = 0
    /// 弹窗会话期间冻结 homeWidth：防止收回瞬间主页列短暂铺满未缩回的宽窗口被测量回写、造成宽度越收越宽。
    @State private var homeWidthFrozen = false
    /// 当前是否已为详情向右撑过窗（按 popupWidth 增量扩/收，而非绝对基准，保留用户在展开态拖拽的宽度）。
    @State private var popupExpanded = false
    private let popupWidth: CGFloat = 321   // 320 详情 + 1 分隔线
    private let bodyHeightKey = "tua.floatPanel.bodyHeight"
    /// 未展开主页基准宽（与 FloatPanelController.restorePosition 共享同一键）；只存基准宽、不存向右扩后的宽。
    private let homeWidthKey = "tua.floatPanel.homeWidth"

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // 铺满到标题栏区域，消除顶部那条被安全区顶出来的空白。
            .ignoresSafeArea()
            .background(WindowAccessor { newWindow in
                DispatchQueue.main.async {
                    if window !== newWindow { window = newWindow }
                    updatePanelSize()
                }
            })
            .onPreferenceChange(ContentHeightKey.self) { height in
                naturalHeight = height
                updatePanelSize()
                scheduleRecentSync()
            }
            .onPreferenceChange(HomeWidthKey.self) { width in
                // 未展开且未冻结时记录主页区域宽度，作为展开后钉定的基准；
                // 冻结（弹窗会话期间）不更新，避免收回瞬间的瞬态宽窗口测量污染基准。
                // 只持久化这个「未展开基准宽」，绝不把向右扩后的宽写回，防跨启动越扩越宽。
                if !showPopup, !minimized, !homeWidthFrozen, !demoMode, width > 0 {
                    homeWidth = width
                    UserDefaults.standard.set(width, forKey: homeWidthKey)
                }
            }
            .onPreferenceChange(ViewportHeightKey.self) { height in
                viewportHeight = height
                scheduleRecentSync()
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didResizeNotification, object: window)) { _ in
                // 用户拉边缘改高度→记录展开态高度（仅展开时，折叠时窗口贴内容不算）。
                // 一旦用户手动调过，就标记已就位，后续 updatePanelSize 不再按记忆值锁回，避免与拖拽抢高度。
                guard collapsed == false, let h = window?.frame.height, h > 200 else { return }
                expandedBodyHeight = h
                appliedExpandedHeight = true
                UserDefaults.standard.set(Double(h), forKey: bodyHeightKey)
                scheduleRecentSync()
            }
            .onChange(of: collapsed) { _ in
                // 折叠↔展开切换：重置就位标记，让 updatePanelSize 在进入展开态时把记忆高度还原一次。
                appliedExpandedHeight = false
                DispatchQueue.main.async { updatePanelSize(); resizeForPopup() }
            }
            .onChange(of: state.selectedWorkspaceRowId) { _ in
                // 工作区详情与异常详情共用同一右扩列，互斥：选中工作区时收起异常详情。
                if state.selectedWorkspaceRowId != nil { selectedAnomalyKind = nil }
                resizeForPopup()
            }
            .onChange(of: selectedAnomalyKind) { _ in
                resizeForPopup()
            }
            .onAppear {
                let h = UserDefaults.standard.double(forKey: bodyHeightKey)
                if h > 200 { expandedBodyHeight = min(CGFloat(h), 1400) }
                if let scene = state.demoScene { applyDemoScene(scene) }
            }
    }

    /// 演示场景摆位（仅 TUA_DEMO_SCENE 时调用）：把浮窗直接开到录制所需画面，省掉真人点击。
    /// 选中异常会触发 onChange→resizeForPopup 向右撑出详情列，与真人点击同路径。
    private func applyDemoScene(_ scene: String) {
        switch scene {
        case "overview":
            collapsed = false
        case "anomalies":
            collapsed = false; showAnomalies = true
        case "detail_loop", "detail_bloat":
            collapsed = false; showAnomalies = true
            selectedAnomalyKind = (scene == "detail_bloat") ? "context_bloat" : "loop_thrash"
        case "detail_workspace":
            // 工作区分类详情：选中排行第一个工作区 → 右扩列内联展开 workspaceDetail。
            collapsed = false
            if state.selectedWorkspaceRowId == nil, let item = state.workspaceGroups.first?.items.first {
                state.selectWorkspace(item)
            }
        case "precheck":
            // 事前护航拦截卡：只需展开（卡由 lastPrecheck 驱动，已展开态顶部露出）。
            collapsed = false
        case "ball":
            // 收起态圆盘（量窗口实际尺寸用）：先渲染卡片测得 naturalHeight，再收起，贴近真实“先开面板再点✕”。
            collapsed = false
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                minimized = true
                updatePanelSize()
            }
        default:
            break
        }
    }

    // MARK: - 卡片

    private var card: some View {
        HStack(alignment: .top, spacing: 0) {
            // 主页列：标题栏 + 用量/列表。向右扩出详情列时它保持原宽不被挤窄（窗口同步向右变宽）。
            VStack(alignment: .leading, spacing: 0) {
                header
                if collapsed { collapsedBody } else { expandedBody }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            // 主页列自适应填充剩余宽（窗口变窄时同步变窄，不钉死、不溢出遮挡）；
            // 展开详情靠 preGrowForPopup 先把窗口向右撑出 popupWidth，保证这一帧不被详情列挤窄。
            .frame(maxWidth: .infinity, alignment: .leading)
            // 在填满前测内容自然高度（折叠态用它把窗口收拢）与主页宽度（记录展开前区域宽）
            .background(GeometryReader { proxy in
                Color.clear
                    .preference(key: ContentHeightKey.self, value: proxy.size.height)
                    .preference(key: HomeWidthKey.self, value: proxy.size.width)
            })

            // 右扩详情列：在主页右侧新扩出的一块（窗口向右变宽 = 向右展开），左侧一条分隔竖线。异常详情与工作区详情二选一。
            if showPopup {
                Rectangle().fill(TUA.border).frame(width: 1)
                    .frame(maxHeight: .infinity)
                if let group = selectedAnomalyGroup {
                    AnomalyDetailView(group: group) { selectedAnomalyKind = nil }
                        .environmentObject(state)
                        .frame(width: 320)
                        .frame(maxHeight: .infinity)
                        // 边缘与工作区弹窗一致：内容区上下各留 12pt（同主页列 padding.vertical），
                        // 让「异常详情」标题/页脚不顶到卡片上下边。
                        .padding(.vertical, 12)
                        .transition(.move(edge: .trailing))
                } else if let item = selectedDetailItem {
                    ScrollView(.vertical, showsIndicators: true) {
                        workspaceDetail(item)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                    .frame(width: 320)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .transition(.move(edge: .trailing))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(TUA.card)
        .overlay(Rectangle().stroke(TUA.border, lineWidth: 1))
        .clipShape(Rectangle())   // 方角：边缘不要圆滑。
        .overlay { if state.workBuddyImporting { importProgressBox } }
    }

    /// WorkBuddy 大刷新进度框：切到 WorkBuddy 全量重拉时盖在面板上，导入完成自动消失。
    private var importProgressBox: some View {
        VStack(spacing: 10) {
            ProgressView().controlSize(.regular)
            Text(state.workBuddyImportPhase.isEmpty ? "处理中…" : state.workBuddyImportPhase)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(TUA.ink)
            Text("首次全量导入需几秒，完成后每 10 秒自动刷新")
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
                .multilineTextAlignment(.center)
        }
        .padding(18)
        .frame(maxWidth: 240)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.md).stroke(TUA.border, lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
    }

    @ViewBuilder
    private var content: some View {
        if minimized {
            ballView
        } else {
            card
        }
    }

    /// 收起态浮球：可拖动小圆——拖动即移动窗口、轻点展开回面板。黑白立体主色 + 异常角标。
    private var ballView: some View {
        let credits = allWorkspaceItems.reduce(0) { $0 + $1.credits }
        let anomalyCount = state.todayFindings.count
        return ZStack {
            // 球体：上亮下暗的渐变 + 顶部高光 + 深黑描边 + 内侧高光，做出鼓起的立体感
            Circle()
                .fill(LinearGradient(
                    colors: [Color(white: 1.0), Color(white: 0.85)],
                    startPoint: .top, endPoint: .bottom))
            Circle()
                .fill(RadialGradient(
                    colors: [Color.white.opacity(0.95), Color.white.opacity(0.0)],
                    center: UnitPoint(x: 0.5, y: 0.28), startRadius: 1, endRadius: ballSize * 0.55))
            Circle().stroke(Color.black.opacity(0.9), lineWidth: 2.5)
            Circle().strokeBorder(Color.white.opacity(0.5), lineWidth: 1).padding(3)

            if credits > 0 {
                VStack(spacing: 0) {
                    Text(TUA.number(credits))
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.black)
                        .lineLimit(1)
                        .minimumScaleFactor(0.55)
                        .padding(.horizontal, 5)
                    Text("积分")
                        .font(.system(size: 8, weight: .medium))
                        .foregroundStyle(Color.black.opacity(0.55))
                }
            } else {
                Image(systemName: "gauge.with.dots.needle.bottom.50percent")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.black.opacity(0.75))
            }
        }
        .overlay(alignment: .topTrailing) {
            if anomalyCount > 0 {
                Text("\(anomalyCount)")
                    .font(.system(size: 8, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                    .frame(minWidth: 15, minHeight: 15)
                    .background(Circle().fill(Color.black))
                    .overlay(Circle().stroke(Color.white.opacity(0.85), lineWidth: 1))
                    .padding(1)
            }
        }
        .padding(3)   // 给描边圆环与右上角标留出窗口内边距，避免被边框裁切
        .frame(width: ballSize, height: ballSize)
        .contentShape(Circle())
        // 移动交给窗口原生 isMovableByWindowBackground（跟手不卡）；这里只识别轻点展开。
        .onTapGesture { expandFromBall() }
        .help("拖动移动 · 轻点展开 Agent 用量助手")
    }

    /// ✕：收起为浮球（记住当前 frame，把窗口缩到小球）。
    private func minimizeToBall() {
        preMinimizeFrame = window?.frame
        minimized = true
        DispatchQueue.main.async { updatePanelSize() }
    }

    /// 点浮球：还原到收起前的面板尺寸。
    private func expandFromBall() {
        minimized = false
        if let window, let f = preMinimizeFrame {
            window.contentMinSize = NSSize(width: 500, height: 200)
            window.contentMaxSize = NSSize(width: 10_000, height: 10_000)
            window.setFrame(f, display: true, animate: true)
        }
        preMinimizeFrame = nil
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text("Agent 用量助手")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TUA.ink)
            Spacer()
            if !state.todayFindings.isEmpty { anomalyBadge }
            iconButton("gearshape", help: "模型设置（全局）") { state.openModelConfig() }
            iconButton("arrow.clockwise") { state.refreshWorkBuddy(); state.loadSnapshots() }
            iconButton(collapsed ? "chevron.down" : "chevron.up") { collapsed.toggle() }
            iconButton("xmark", help: "收起为浮球") { minimizeToBall() }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { collapsed.toggle() }
        .padding(.bottom, collapsed ? 8 : 12)
    }

    /// 异常角标：今日检出任意异常时出现，点击展开异常列表。
    private var anomalyBadge: some View {
        Button {
            collapsed = false
            showAnomalies.toggle()
            // 收起内联异常列表时，一并关掉可能已向右扩出的异常详情。
            if !showAnomalies { selectedAnomalyKind = nil }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 8, weight: .semibold))
                Text("\(state.todayFindings.count)")
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
            }
            .foregroundStyle(TUA.error)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(Capsule().fill(TUA.error.opacity(0.12)))
        }
        .buttonStyle(.plain)
        .help("今日检出的异常")
    }

    private func iconButton(_ symbol: String, help: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(TUA.faint)
                .frame(width: 16, height: 16)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .modifier(OptionalHelp(text: help))
    }

    // MARK: - 展开态

    /// 常驻「把关/关」总闸（原叫「指导」，黑话改白话）：与护航卡、菜单栏、设置页共用 guardEnabled 总闸。
    /// 关掉后即使不再弹拦截卡，也能从这里一键开回。
    private var guidanceRow: some View {
        HStack(spacing: 8) {
            Image(systemName: state.config.guardEnabled ? "shield.lefthalf.filled" : "shield.slash")
                .font(.system(size: 11))
                .foregroundStyle(state.config.guardEnabled ? TUA.success : TUA.faint)
            Text(state.config.guardEnabled ? "提问前帮你把关 · 没写清楚的会被拦下" : "已关 · 不再检查，全部直接发送")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(TUA.ink)
            Spacer()
            Toggle("",
                   isOn: Binding(get: { state.config.guardEnabled },
                                 set: { state.setGuardEnabled($0) }))
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.mini)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(Color.black.opacity(0.03)))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
    }

    /// 常驻「敏感信息护航」独立闸：与「把关」总闸平行——关掉把关也仍拦凭据外发。
    /// 高置信口径（明确前缀令牌/JWT/PEM/URL 口令等），散文里的 token 字样不误伤。
    private var secretGuardRow: some View {
        HStack(spacing: 8) {
            Image(systemName: state.config.secretGuardEnabled ? "lock.shield.fill" : "lock.open")
                .font(.system(size: 11))
                .foregroundStyle(state.config.secretGuardEnabled ? TUA.success : TUA.faint)
            Text(state.config.secretGuardEnabled ? "保密把关 · 带密钥的提问会拦下" : "保密已关 · 带密钥的也直接发出去")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(TUA.ink)
            Spacer()
            Toggle("",
                   isOn: Binding(get: { state.config.secretGuardEnabled },
                                 set: { state.setSecretGuardEnabled($0) }))
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.mini)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(Color.black.opacity(0.03)))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
    }

    private var expandedBody: some View {
        ScrollView(.vertical, showsIndicators: true) {
            VStack(alignment: .leading, spacing: 14) {
                guidanceRow
                secretGuardRow
                if state.lastPrecheck != nil { PrecheckCard() }
                // 异常列表内联在主页（点 ⚠ 展开/收起）；只有点列表里某一行，才把该类的「异常详情」向右扩出。
                if showAnomalies { anomaliesSection }
                usageSection
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .frame(minHeight: 220)
        .background(GeometryReader { proxy in
            Color.clear.preference(key: ViewportHeightKey.self, value: proxy.size.height)
        })
    }

    // MARK: ① 今日用量（直接平铺所有工作区）

    private var usageSection: some View {
        let items = allWorkspaceItems
        let totalCredits = items.reduce(0) { $0 + $1.credits }
        let totalTurns = items.reduce(0) { $0 + $1.turns }
        let hours = state.trend.first?.hours ?? []
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                sectionTitle("\(state.panelShowsToday ? "今日用量" : "近 7 天") · \(AppState.sourceDisplayName(state.sourceFilter))")
                Spacer()
                sourcePicker
            }
            if !state.selectedSourceHasData {
                connectCard
            } else if items.isEmpty {
                Text("尚无用量 · 接入 IDE 后自动统计")
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.muted)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    Text(TUA.number(totalCredits))
                        .font(.system(size: 22, weight: .semibold, design: .monospaced))
                        .foregroundStyle(TUA.ink)
                    Text("积分")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                    Text("· \(totalTurns) 轮")
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.faint)
                    Spacer()
                }
                if hours.contains(where: { $0 > 0 }) {
                    MiniSparkline(values: hours, color: TUA.agentColor(state.workspaceGroups.first?.ide ?? ""))
                        .frame(height: 24)
                }
                // 工作区列表（整宽）：活跃绿点 + 点选 → 从右侧滑出详情弹层。
                // TimelineView 每 10s 推一次 now，让「近期有活动」绿点在无新数据时也能自行熄灭。
                TimelineView(.periodic(from: .now, by: 10)) { ctx in
                    let now = ctx.date.timeIntervalSince1970 * 1000
                    VStack(spacing: 4) {
                        ForEach(items) { item in
                            workspaceRow(item, now: now, selectedId: state.selectedWorkspaceRowId)
                        }
                    }
                }
            }
        }
    }

    private var allWorkspaceItems: [WorkspaceStats] {
        state.workspaceGroups.flatMap { $0.items }
    }

    /// 当前滑出详情弹层的工作区（nil = 未选中/已收起）。
    private var selectedDetailItem: WorkspaceStats? {
        guard let id = state.selectedWorkspaceRowId else { return nil }
        return allWorkspaceItems.first { $0.id == id }
    }

    /// 右扩列里点中的异常类型对应的分组（nil = 未点进详情，显示异常列表）。
    private var selectedAnomalyGroup: AnomalyGroup? {
        guard let kind = selectedAnomalyKind else { return nil }
        return AnomalyGroup.groups(from: state.todayFindings).first { $0.kind == kind }
    }

    /// 弹层是否展示：仅展开态（非折叠/非浮球）且右扩列有详情（异常详情 / 选中工作区）。异常列表是内联的，不算。
    private var showPopup: Bool { !collapsed && !minimized && (selectedAnomalyKind != nil || selectedDetailItem != nil) }

    /// 来源切换：单选胶囊下拉（无「全部」），列出可监控来源（WorkBuddy 暂隐藏，不进选项）。
    private var sourcePicker: some View {
        Menu {
            ForEach(state.knownSources.filter { $0 != "workbuddy" }, id: \.self) { source in
                Button {
                    state.setSourceFilter(source)
                } label: {
                    if source == state.sourceFilter {
                        Label(AppState.sourceDisplayName(source), systemImage: "checkmark")
                    } else {
                        Text(AppState.sourceDisplayName(source))
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Circle().fill(TUA.agentColor(state.sourceFilter)).frame(width: 7, height: 7)
                Text(AppState.sourceDisplayName(state.sourceFilter))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(TUA.ink)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(TUA.faint)
            }
            .padding(.leading, 10)
            .padding(.trailing, 8)
            .padding(.vertical, 5)
            .background(Capsule().fill(TUA.canvas))
            .overlay(Capsule().stroke(TUA.border, lineWidth: 1))
            .contentShape(Capsule())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
    }

    /// 未接入 / 无数据时的「一键接入」卡片。
    private var connectCard: some View {
        let name = AppState.sourceDisplayName(state.sourceFilter)
        return VStack(alignment: .leading, spacing: 10) {
            Text("\(name) 还没有用量数据")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TUA.ink)
            Text(connectHint)
                .font(.system(size: 11))
                .foregroundStyle(TUA.muted)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button {
                    state.connectSelectedSource()
                } label: {
                    HStack(spacing: 5) {
                        if state.connectingSource { ProgressView().controlSize(.small) }
                        Text(state.connectingSource ? "接入中…" : "一键接入 \(name)")
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(state.connectingSource)
                if state.selectedSourceInstalled {
                    Button("重新加载") { state.loadSnapshots() }
                        .font(.system(size: 11))
                        .buttonStyle(.plain)
                        .foregroundStyle(TUA.muted)
                }
            }
            if let msg = state.connectMessage {
                Text(msg)
                    .font(.system(size: 10))
                    .foregroundStyle(TUA.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
    }

    private var connectHint: String {
        if state.selectedSourceInstalled {
            return "已接入，但该来源近 7 天没有用量记录。回到对应 IDE 正常用一次，再点「重新加载」即可。"
        }
        switch state.sourceFilter {
        case "qoder": return "点「一键接入」写入 Qoder hook，重启 Qoder 后自动上报用量与异常。"
        case "workbuddy": return "WorkBuddy 无需 hook：点「一键接入」直接读取本机审计流与用量库（首次拉全部历史）。"
        default: return "接入该来源后自动统计用量与异常。"
        }
    }

    // MARK: ② 异常

    private var anomaliesSection: some View {
        let groups = anomalyGroups
        let total = state.todayFindings.count
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionTitle("异常 · \(Thresholds.version)")
                Spacer()
                Text("\(state.panelShowsToday ? "今日" : "近 7 天") \(total) 条 · \(groups.count) 类")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
            }
            if groups.isEmpty {
                Text("今日未检出异常")
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.faint)
            } else {
                VStack(spacing: 6) {
                    ForEach(groups) { group in anomalyGroupRow(group) }
                }
                Text("点击某类看：发生了什么 · 怎么发生 · 影响 · 怎么改")
                    .font(.system(size: 8))
                    .foregroundStyle(TUA.faint)
            }
        }
    }

    /// 今日异常按类型聚合，一类一行。
    private var anomalyGroups: [AnomalyGroup] {
        AnomalyGroup.groups(from: state.todayFindings)
    }

    private func anomalyGroupRow(_ group: AnomalyGroup) -> some View {
        let tone = group.isHigh ? TUA.error : TUA.warn
        let selected = selectedAnomalyKind == group.kind
        return Button {
            // 先撑窗再置态：详情出现那一帧主页列就有空间、不被挤窄（主页列自适应填充）。
            preGrowForPopup()
            // 向右滑出该类型异常详情（取代旧独立弹窗）；先收起工作区详情避免右扩列内容冲突；详情内 ✕ 返回内联列表。
            state.closeWorkspaceDetail()
            selectedAnomalyKind = group.kind
        } label: {
            HStack(spacing: 5) {
                // 行首严重度点：高=红、普通=橙，对齐工作区行首的活动点。
                Circle().fill(tone).frame(width: 6, height: 6)
                Text(group.kindLabel)
                    .font(.system(size: 10, weight: selected ? .semibold : .regular))
                    .foregroundStyle(TUA.ink)
                    .lineLimit(1)
                    .fixedSize()
                Spacer(minLength: 4)
                Text("\(group.count) 条")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(TUA.faint)
                    .fixedSize()
                Circle().fill(TUA.agentColor(group.representative.ide)).frame(width: 5, height: 5)
                Text(group.workspaceSummary)
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                    .lineLimit(1)
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .fill(selected ? tone.opacity(0.12) : TUA.canvas))
            .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .stroke(selected ? tone : TUA.border, lineWidth: selected ? 1.5 : 1))
            // 整行（含两侧与 Spacer 空白）都是点击区。
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: ③ 工作区行（左栏列表项：活跃绿点 + 点选驱动右栏详情）

    /// 近期（5 分钟内）有事件 = 亮绿点表示“在工作中”；无活动不亮（靠 TimelineView 推 now 自行熄灭）。
    private func workspaceRow(_ item: WorkspaceStats, now: Double, selectedId: String?) -> some View {
        let sessionCount = state.sessions.filter { $0.workspaceId == item.workspaceId }.count
        let selected = item.id == selectedId
        let active = item.lastTs > 0 && (now - Double(item.lastTs)) <= 5 * 60 * 1000
        return Button {
            // 先撑窗再置态（已撑则幂等 no-op）：保证工作区详情出现那一帧主页列不被挤窄。
            preGrowForPopup()
            state.selectWorkspace(item)
        } label: {
            HStack(spacing: 5) {
                // 活动状态点：近期有活动 = 绿，已停止 = 灰。
                Circle()
                    .fill(active ? TUA.success : TUA.muted)
                    .frame(width: 6, height: 6)
                Text(item.name)
                    .font(.system(size: 10, weight: selected ? .semibold : .regular))
                    .foregroundStyle(TUA.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 4)
                Text("\(item.turns) 轮")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(TUA.faint)
                    .fixedSize()
                if sessionCount > 0 {
                    Text("\(sessionCount) 会话")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                        .fixedSize()
                }
                Text(TUA.number(item.credits))
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(TUA.ink)
                    .fixedSize()
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .fill(selected ? TUA.agentColor(item.ide).opacity(0.12) : TUA.canvas))
            .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .stroke(selected ? TUA.agentColor(item.ide) : TUA.border, lineWidth: selected ? 1.5 : 1))
            // 整行（含两侧空白）都是点击区。
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(active ? "近期有活动" : "")
    }

    /// 右栏常驻详情（均工作区维度）：① 顶部 turn 数量统计 ② 记忆总结 ③ 可用 Skill ④ 本区最近事件。
    /// 展示的提问文本均来自入库前已脱敏的数据，不会在浮窗里泄露凭据。
    private func workspaceDetail(_ item: WorkspaceStats) -> some View {
        let agg = state.database.workspaceAggregate(ide: item.ide, workspaceId: item.workspaceId)
        // 轮次只取提问文本供 ③ 匹配用，不再展示轮次卡。
        let turns = state.database.turnDetail(since: 0, ide: item.ide,
                                              workspaceId: item.workspaceId, limit: 10)
        // 耗时/token 有数才上屏（qoder 来源不报 token、部分轮不报耗时，挂一串 0 是噪音）。
        var extraParts: [String] = []
        if agg.latencyMs > 0 { extraParts.append(TUA.duration(Int(agg.latencyMs))) }
        if agg.tokenIn + agg.tokenOut > 0 {
            extraParts.append("tokens ↑\(Self.fmtTokens(agg.tokenIn))/↓\(Self.fmtTokens(agg.tokenOut))")
        }
        let extraStatLine = extraParts.joined(separator: " · ")
        // ② 工作区记忆总结（零 LLM）：本区历史提问条数 + 近期代表；兼作 ③ 匹配文本。
        let mem = workspaceMemory(item.workspaceId)
        let recentText = [turns.compactMap { $0.prompt }.joined(separator: " "),
                          mem?.recent.joined(separator: " ") ?? ""].joined(separator: " ")
        // ③ 该工作区可用 Skill：项目指纹 + 近期提问关键词双信号，点击开浏览器。
        let skills = mergedSkills(text: recentText, workspaceId: item.workspaceId)
        // ④ 本区最近事件（按选中工作区过滤）。
        let events = state.database.recentEventsForWorkspace(workspaceId: item.workspaceId,
                                                             ide: item.ide, limit: 8)
        return VStack(alignment: .leading, spacing: 8) {
            // ① 右上角：选中工作区的 turn 数量统计（大数字 + 补充维度）。
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Circle().fill(TUA.agentColor(item.ide)).frame(width: 5, height: 5)
                    Text(item.name)
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.muted)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 0)
                    Button { state.closeWorkspaceDetail() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(TUA.faint)
                    }
                    .buttonStyle(.plain)
                    .help("收起详情")
                }
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text("\(agg.turns)")
                        .font(.system(size: 20, weight: .semibold, design: .monospaced))
                        .foregroundStyle(TUA.ink)
                    Text("轮 · \(agg.actions) 动作 · \(TUA.number(agg.credits)) 积分")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                    Spacer(minLength: 0)
                }
                // 耗时/token 有数才显示，免得挂一串 0。
                if agg.latencyMs > 0 || agg.tokenIn + agg.tokenOut > 0 {
                    Text(extraStatLine)
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(TUA.faint)
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
            .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
            // ② 记忆总结：工作区维度一条，卡片化展示。
            if let mem {
                HStack(alignment: .top, spacing: 5) {
                    Image(systemName: "brain.head.profile")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                        .padding(.top, 1)
                    Text("记忆 · 本区 \(mem.count) 条历史提问（\(mem.source)）· 近期「\(mem.recent.joined(separator: "」「"))」")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.muted)
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
                .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
            }
            // ③ 可用 Skill：卡片化，每条一行「名称 —— 能帮干什么」，点击跳转。
            if !skills.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 4) {
                        Image(systemName: "puzzlepiece.extension.fill")
                            .font(.system(size: 9))
                            .foregroundStyle(TUA.faint)
                        Text("可用 Skill")
                            .font(.system(size: 9))
                            .foregroundStyle(TUA.faint)
                    }
                    ForEach(skills) { skill in
                        Link(destination: URL(string: skill.url) ?? URL(fileURLWithPath: "/")) {
                            HStack(spacing: 5) {
                                Text(skill.name)
                                    .font(.system(size: 9, weight: .medium))
                                    .foregroundStyle(TUA.ink)
                                Text("—— \(skill.desc)")
                                    .font(.system(size: 9))
                                    .foregroundStyle(TUA.faint)
                                    .lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
                .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
            }
            // ④ 最近事件：只列选中工作区的。
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 4) {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 8))
                        .foregroundStyle(TUA.faint)
                    Text("本区最近事件")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                    Spacer(minLength: 0)
                }
                if events.isEmpty {
                    Text("本区暂无事件")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                } else {
                    ForEach(events) { row in eventRow(row) }
                }
            }
        }
    }

    // MARK: 展开区辅助（②工作区记忆总结 / ③Skill 推荐，均零 LLM）

    /// 工作区记忆总结展示；本区无历史提问返回 nil。
    private func workspaceMemory(_ workspaceId: String) -> (count: Int, source: String, recent: [String])? {
        let raw = state.database.workspaceMemorySummary(workspaceId: workspaceId)
        guard raw.count > 0, !raw.recent.isEmpty else { return nil }
        let short = raw.recent.map { $0.count > 14 ? String($0.prefix(14)) + "…" : $0 }
        return (raw.count, raw.fromCorpus ? "语料库" : "轮次", short)
    }

    /// 用工作区近期提问文本匹内置关键词表。
    private func matchedSkills(text: String) -> [SkillPick] {
        let lower = text.lowercased()
        guard !lower.isEmpty else { return [] }
        return Self.skillPicks.filter { pick in
            pick.keywords.contains { lower.contains($0) }
        }
    }

    /// Skill 推荐合并去重：项目指纹（硬信号）优先，提问关键词补位，共最多 3 条。
    private func mergedSkills(text: String, workspaceId: String) -> [SkillPick] {
        var out = projectSkills(workspaceId: workspaceId)
        for p in matchedSkills(text: text) where !out.contains(where: { $0.url == p.url }) {
            out.append(p)
        }
        return Array(out.prefix(3))
    }

    /// 项目指纹探测（零 LLM）：走查根目录（深度≤3、跳过 .git/node_modules 等，上限 4000 条目），
    /// 按标记文件 + 扩展名计数识别工程形态——提问是大白话没关系，工程结构骗不了人。
    /// 结果按路径缓存（仅主线程读写）。
    private func projectSkills(workspaceId: String) -> [SkillPick] {
        guard let root = state.database.workspaceRootPath(workspaceId: workspaceId) else { return [] }
        if let cached = Self.projectSkillCache[root] { return cached }
        let fm = FileManager.default
        var markers = Set<String>()
        var extCount: [String: Int] = [:]
        var hasSwiftUI = false, hasMenuBar = false, swiftChecked = 0, visited = 0
        let skipDirs: Set<String> = [".git", "node_modules", ".build", "dist", "build", "__pycache__", ".godot", "Builds"]
        func walk(_ dir: String, _ depth: Int) {
            guard depth <= 3, visited < 4000,
                  let entries = try? fm.contentsOfDirectory(atPath: dir) else { return }
            for e in entries {
                guard visited < 4000 else { return }
                visited += 1
                if e.hasPrefix(".") { continue }
                let full = dir + "/" + e
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: full, isDirectory: &isDir) else { continue }
                if isDir.boolValue {
                    if !skipDirs.contains(e) { walk(full, depth + 1) }
                    continue
                }
                // 标记文件只认根目录第一层（依赖包里碰巧同名的不算）。
                if depth == 1 { markers.insert(e) }
                guard let dot = e.lastIndex(of: ".") else { continue }
                let ext = String(e[e.index(after: dot)...]).lowercased()
                extCount[ext, default: 0] += 1
                if ext == "swift", swiftChecked < 200 {
                    swiftChecked += 1
                    if let data = fm.contents(atPath: full) {
                        let head = String(decoding: data.prefix(2048), as: UTF8.self)
                        if head.contains("import SwiftUI") { hasSwiftUI = true }
                        if head.contains("NSStatusItem") || head.contains("MenuBarExtra") { hasMenuBar = true }
                    }
                }
            }
        }
        walk(root, 1)
        // 形态判据要够数：一两个杂散文件不算一种工程（ohmygame 实测：145 个 tsx 不该被 2 个 .swift 抢位）。
        let hasSwift = extCount["swift", default: 0] >= 3 || markers.contains("Package.swift")
        let hasGodot = markers.contains("project.godot") || extCount["gd", default: 0] >= 3
        let hasReact = markers.contains("package.json")
            && (extCount["tsx", default: 0] > 0 || extCount["jsx", default: 0] > 0)
        let hasJS = extCount["js", default: 0] + extCount["ts", default: 0]
            + extCount["mjs", default: 0] >= 5
        let hasPython = markers.contains("pyproject.toml") || markers.contains("requirements.txt")
            || extCount["py", default: 0] >= 5
        let docHeavy = extCount["md", default: 0] >= 10
            && !hasSwift && !hasGodot && !hasReact && !hasJS && !hasPython

        var picks: [SkillPick] = []
        func add(_ name: String) {
            if let p = Self.skillPicks.first(where: { $0.name == name }),
               !picks.contains(where: { $0.url == p.url }) { picks.append(p) }
        }
        if hasSwift {
            add("SwiftUI Pro")
            add(hasMenuBar || hasSwiftUI ? "macOS 打包/菜单栏" : "废弃 API 扫描")
            add("废弃 API 扫描")
        }
        if hasGodot { add("Godot 4 技能") }
        if hasReact || hasJS { add("React 最佳实践") }
        if hasPython { add("工程纪律") }
        if docHeavy { add("文档技能包") }
        Self.projectSkillCache[root] = picks
        return picks
    }

    /// token 数紧凑显示：1.2M / 34K / 900。
    private static func fmtTokens(_ n: Int64) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.0fK", Double(n) / 1_000) }
        return "\(n)"
    }

    /// 内置 GitHub Skill 推荐表（均为真实高星仓库；项目指纹 + 关键词双信号，零 LLM 不烧积分）。
    private static let skillPicks: [SkillPick] = [
        SkillPick(name: "SwiftUI Pro", url: "https://github.com/twostraws/SwiftUI-Agent-Skill",
                  desc: "审查 SwiftUI 废弃 API/性能/无障碍",
                  keywords: ["swiftui", "界面", "视图", "动画", "无障碍", "voiceover"]),
        SkillPick(name: "macOS 打包/菜单栏", url: "https://github.com/Dimillian/Skills",
                  desc: "SPM 构建、菜单栏 App 打包分发",
                  keywords: ["打包", "spm", "菜单栏", "menubar", "分发", "tuist", "签名", "release"]),
        SkillPick(name: "废弃 API 扫描", url: "https://github.com/AvdLee/SwiftUI-Agent-Skill",
                  desc: "扫 Apple 文档更新废弃 API",
                  keywords: ["废弃", "deprecated", "升级", "xcode"]),
        SkillPick(name: "Godot 4 技能", url: "https://github.com/jame581/GodotPrompter",
                  desc: "55 个领域技能：场景/信号/GDScript/优化",
                  keywords: ["godot", "gdscript", "场景树", "tscn"]),
        SkillPick(name: "React 最佳实践", url: "https://github.com/vercel-labs/agent-skills",
                  desc: "查重渲染/优化加载/组件边界",
                  keywords: ["react", "nextjs", "前端", "组件", "小程序", "tsx"]),
        SkillPick(name: "工程纪律", url: "https://github.com/addyosmani/agent-skills",
                  desc: "Google 工程师的规→建→审→发工作流",
                  keywords: ["python", "脚本", "重构", "测试", "代码评审", "架构"]),
        SkillPick(name: "文档技能包", url: "https://github.com/anthropics/skills",
                  desc: "官方 pdf/docx/pptx 文档生成技能",
                  keywords: ["文档", "排版", "报告", "幻灯片", "pdf"]),
        SkillPick(name: "原生性能剖析", url: "https://github.com/termio-sh/termio",
                  desc: "原生 App 卡顿/内存剖析",
                  keywords: ["性能", "卡顿", "内存", "泄漏", "profiling", "掉帧"]),
        SkillPick(name: "macOS 交互模式", url: "https://github.com/robinebers/openusage",
                  desc: "macOS 原生交互/窗口模式",
                  keywords: ["交互", "窗口", "面板", "快捷键", "悬浮"]),
    ]

    /// 项目指纹探测结果缓存（按根路径；仅主线程读写）。工程结构基本不变，无需失效。
    private static var projectSkillCache: [String: [SkillPick]] = [:]

    // MARK: - 折叠态

    private var collapsedBody: some View {
        let hours = state.trend.first?.hours ?? []
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                if state.today.agents.isEmpty {
                    Text("—")
                        .font(.system(size: 15, weight: .semibold, design: .monospaced))
                        .foregroundStyle(TUA.faint)
                    Text("尚无用量")
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.muted)
                } else {
                    ForEach(state.today.agents) { agent in
                        HStack(spacing: 3) {
                            Circle().fill(TUA.agentColor(agent.id)).frame(width: 5, height: 5)
                            Text(TUA.number(agent.credits))
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                .foregroundStyle(TUA.ink)
                        }
                    }
                    Text("积分 · 今日")
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.muted)
                }
                Spacer()
            }
            // 折叠态也保留今日逐小时用量曲线，一眼看到趋势。
            if hours.contains(where: { $0 > 0 }) {
                MiniSparkline(values: hours, color: TUA.agentColor(state.workspaceGroups.first?.ide ?? ""))
                    .frame(height: 24)
            }
        }
    }

    // MARK: - 组件

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10))
            .foregroundStyle(TUA.faint)
    }

    private func smallMetric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundStyle(TUA.ink)
            Text(title)
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
        }
    }

    /// 一行 = 时间 · Agent 色点 · 动作(工具) · 事件名 … 耗时 · 积分。
    /// 参照 memory-observatory：未知取值原样透出；失败动作标红。
    private func eventRow(_ row: EventRow) -> some View {
        let failed = (row.status != nil && row.status != "ok") || row.eventName == "PostToolUseFailure"
        let label = row.actionLabel
        let detail = row.detail ?? ""
        let prefix = label + ": "
        let raw = detail.hasPrefix(prefix) ? String(detail.dropFirst(prefix.count)) : detail
        // 空输入等场景适配器会把 detail 回退成事件名（如 UserPromptSubmit），这不是真实内容，不显示。
        let secondary = (raw.isEmpty || raw == label || raw == (row.eventName ?? "")) ? "" : raw
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(TUA.time(row.ts))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.faint)
                Circle()
                    .fill(TUA.agentColor(row.ide ?? ""))
                    .frame(width: 5, height: 5)
                // 工具名高亮标签（不折行）
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(failed ? TUA.error : TUA.ink)
                    .fixedSize()
                    .padding(.horizontal, 4)
                    .padding(.vertical, 1)
                    .background(RoundedRectangle(cornerRadius: 3).fill(
                        (failed ? TUA.error : TUA.agentColor(row.ide ?? "")).opacity(0.10)
                    ))
                if let eventName = row.eventName, !eventName.isEmpty, eventName != label {
                    Text(eventName)
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.faint)
                        .fixedSize()
                }
                // 所属工作区（主信息，高亮框突出）
                if let ws = row.workspaceName, !ws.isEmpty {
                    Text(ws)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(TUA.ink)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 3).fill(TUA.agentColor(row.ide ?? "").opacity(0.12)))
                }
                // 所属会话（次要信息，无底色轻文本）
                if let sid = row.sessionId, !sid.isEmpty {
                    Text("#" + String(sid.prefix(6)))
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(TUA.faint.opacity(0.8))
                        .fixedSize()
                }
                Spacer(minLength: 4)
                if let latency = row.latencyMs, latency > 0 {
                    Text(TUA.duration(latency))
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(TUA.faint)
                        .fixedSize()
                }
                if row.credits > 0 {
                    Text(TUA.number(row.credits))
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(TUA.ink)
                }
            }
            // 详情另起一行，宽度不够自动折行，展示全不截断
            if !secondary.isEmpty {
                Text(secondary)
                    .font(.system(size: 11))
                    .foregroundStyle(failed ? TUA.error : TUA.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .lineLimit(nil)
            }
        }
    }

    // MARK: - 面板自适应尺寸

    /// 防抖：布局稳定后再推算最近事件条数。
    private func scheduleRecentSync() {
        recentSyncTask?.cancel()
        let task = DispatchWorkItem { syncRecentLimit() }
        recentSyncTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: task)
    }

    /// 按窗口高度推算最近事件条数：顶部标题/用量/异常约留 300pt，其余每 ~36pt 放一行；下限 5，上限 40。
    private func syncRecentLimit() {
        guard !collapsed, let h = window?.frame.height, h > 200 else { return }
        let capacity = Int((h - 300) / 36)
        let limit = min(40, max(5, capacity))
        if limit != state.recentLimitCache {
            state.loadSnapshots(recentLimit: limit)
        }
    }

    /// 演示模式（TUA_DEMO_SCENE）：锁死窗口尺寸，不让内容测量/详情撑宽改窗，保证逐场景录制的区域坐标一致。
    private var demoMode: Bool { state.demoScene != nil }

    /// 弹层开/关时按 popupWidth 增量向右扩/收回窗口：左边缘固定、只改 width（=「向右展开」）。
    /// 用增量而非绝对基准宽，是为了让用户在展开态拖窗口改的宽在收起后保留（整体自适应）；主页列本身 maxWidth 自适应。
    private func resizeForPopup() {
        guard window != nil, !minimized, !demoMode else { return }
        if showPopup, !popupExpanded {
            growPopupWindow()
        } else if !showPopup, popupExpanded {
            shrinkPopupWindow()
        }
    }

    /// 详情出现前同步把窗口向右撑出 popupWidth，使自适应的主页列在详情那一帧不被挤窄。
    /// 由两行列表项的点击动作在置状态前调用；若已撑过（弹窗已在）则幂等 no-op。
    private func preGrowForPopup() {
        guard window != nil, !minimized, !popupExpanded, !demoMode else { return }
        growPopupWindow()
    }

    private func growPopupWindow() {
        guard let window else { return }
        homeWidthFrozen = true   // 会话期间不让测量回写基准宽
        var f = window.frame
        f.size.width += popupWidth
        window.setFrame(f, display: true, animate: false)   // 瞬时，勿 animate:true
        popupExpanded = true
    }

    private func shrinkPopupWindow() {
        guard let window else { return }
        var f = window.frame
        f.size.width = max(500, f.size.width - popupWidth)   // 不低于内容最小宽
        window.setFrame(f, display: true, animate: false)
        popupExpanded = false
        // 窗口缩回后异步解冻，让主页列在稳定宽度下重新测量刷新基准。
        DispatchQueue.main.async { self.homeWidthFrozen = false }
    }

    /// 宽高都交给窗口边缘原生拉伸；这里只维护约束与折叠/展开的高度切换。
    private func updatePanelSize() {
        guard let window else { return }
        // 收起为圆盘：窗口必须紧贴圆盘尺寸，且不受 naturalHeight（卡片一消失即归 0）与演示锁尺寸的影响，
        // 否则大透明窗留在屏幕上——既能从远处拖到圆盘、又遮挡其后内容。用 animate:false 防动画被打断回弹。
        if minimized {
            window.contentMinSize = NSSize(width: ballSize, height: ballSize)
            window.contentMaxSize = NSSize(width: ballSize, height: ballSize)
            if abs(window.frame.width - ballSize) > 0.5 || abs(window.frame.height - ballSize) > 0.5 {
                var frame = window.frame
                let left = frame.minX, top = frame.maxY
                frame.size = NSSize(width: ballSize, height: ballSize)
                frame.origin = NSPoint(x: left, y: top - ballSize)
                window.setFrame(frame, display: true, animate: false)
            }
            return
        }
        guard naturalHeight > 0 else { return }
        if demoMode { return }
        window.contentMinSize = NSSize(width: 500, height: collapsed ? naturalHeight : 200)
        window.contentMaxSize = NSSize(width: 10_000, height: 10_000)
        // 折叠态：窗口贴住摘要内容高度；展开态：仅在刚进入展开态时还原一次记忆高度，
        // 之后高度完全交给用户拉边缘（appliedExpandedHeight 置真后即不再锁回），否则下拉扩展会被每次重测拽回旧高。
        var frame = window.frame
        let h: CGFloat
        if collapsed {
            h = naturalHeight
        } else {
            guard !appliedExpandedHeight else { return }
            h = expandedBodyHeight ?? 460
            appliedExpandedHeight = true
        }
        guard abs(frame.height - h) > 0.5 else { return }
        // 锚定左下角：高度变化时顶部与左边缘不动
        frame.origin.y += frame.size.height - h
        frame.size.height = h
        window.setFrame(frame, display: true, animate: true)
    }
}

/// 迷你趋势线（今日逐小时积分，按 Agent 分色）。
private struct MiniSparkline: View {
    let values: [Double]
    let color: Color

    var body: some View {
        GeometryReader { proxy in
            let maxValue = max(values.max() ?? 0, .leastNonzeroMagnitude)
            let stepX = values.count > 1 ? proxy.size.width / CGFloat(values.count - 1) : 0
            let points = values.enumerated().map { index, value in
                CGPoint(
                    x: CGFloat(index) * stepX,
                    y: proxy.size.height - CGFloat(value / maxValue) * (proxy.size.height - 2) - 1
                )
            }
            ZStack {
                if points.count > 1 {
                    Path { path in
                        path.move(to: CGPoint(x: 0, y: proxy.size.height))
                        for point in points { path.addLine(to: point) }
                        path.addLine(to: CGPoint(x: proxy.size.width, y: proxy.size.height))
                        path.closeSubpath()
                    }
                    .fill(color.opacity(0.12))

                    Path { path in
                        path.move(to: points[0])
                        for point in points.dropFirst() { path.addLine(to: point) }
                    }
                    .stroke(color.opacity(0.85), style: StrokeStyle(lineWidth: 1.2, lineJoin: .round))
                }
            }
        }
    }
}

/// 卡片内容的自然高度（在填满前测量），折叠态据此把窗口贴住摘要。
private struct ContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 { value = next }
    }
}

/// 主页列（未展开详情时）的宽度：点列表项展开前记录它，展开时把主页列钉在此宽，保证「展开前区域宽度不变」。
private struct HomeWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 { value = next }
    }
}

/// 展开区可视（滚动视口）高度。
private struct ViewportHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next > 0 { value = next }
    }
}

/// 可选 tooltip：nil 时不加。
private struct OptionalHelp: ViewModifier {
    let text: String?
    func body(content: Content) -> some View {
        if let text {
            content.help(text)
        } else {
            content
        }
    }
}

private final class WindowGrabberView: NSView {
    var onWindow: ((NSWindow?) -> Void)?
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        onWindow?(window)
    }
}

private struct WindowAccessor: NSViewRepresentable {
    let callback: (NSWindow?) -> Void

    func makeNSView(context: Context) -> WindowGrabberView {
        let view = WindowGrabberView()
        view.onWindow = callback
        return view
    }

    func updateNSView(_ nsView: WindowGrabberView, context: Context) {}
}

/// 浮窗 Skill 推荐条目（内置表驱动；项目指纹/关键词命中即荐，点击开浏览器）。
struct SkillPick: Identifiable {
    let name: String
    let url: String
    let desc: String
    let keywords: [String]
    var id: String { url }
}
