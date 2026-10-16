import AppKit
import SwiftUI

/// 悬浮窗（NSPanel）：无边框、不抢焦点、常驻置顶、位置记忆。
final class FloatPanelController {
    private var panel: NSPanel?
    private let frameKey = "tua.floatPanel.frame"

    var isVisible: Bool { panel?.isVisible ?? false }

    func show(state: AppState) {
        if let panel {
            panel.orderFrontRegardless()
            return
        }

        let hosting = NSHostingView(rootView: FloatPanelView().environmentObject(state))
        // 不让 hosting 用内容的固有尺寸反过来约束窗口（默认行为会在拉宽度时抢尺寸、导致抖且不铺满）；
        // 宽度完全交给窗口/用户，内容 maxWidth 铺满；高度仍由 updatePanelSize 按内容驱动。
        hosting.sizingOptions = []
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 640, height: 460),
            styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView, .resizable],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.hidesOnDeactivate = false
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.backgroundColor = .clear
        panel.isOpaque = false
        // 深度阴影交给系统窗口阴影（按不透明内容形状生成），不在窗口内画 SwiftUI 阴影，避免灰带
        panel.hasShadow = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        panel.contentView = hosting
        panel.contentMinSize = NSSize(width: 500, height: 200)
        // 隐藏标题栏三个红黄绿按钮（无边框悬浮窗不需要）
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true

        if !restorePosition(panel) {
            positionTopRight(panel)
        }

        NotificationCenter.default.addObserver(
            forName: NSWindow.didMoveNotification, object: panel, queue: .main
        ) { [weak self] _ in
            self?.savePosition()
        }
        // 拉伸后记忆宽度（高度仍由内容驱动，恢复时只取宽度）
        NotificationCenter.default.addObserver(
            forName: NSWindow.didResizeNotification, object: panel, queue: .main
        ) { [weak self] _ in
            self?.savePosition()
        }

        panel.orderFrontRegardless()
        self.panel = panel
    }

    func hide() {
        savePosition()
        panel?.orderOut(nil)
    }

    /// 演示模式：把面板钉到主屏顶部一块固定矩形，并把 `screencapture -R` 用的区域坐标（点、左上原点）
    /// 写进 /tmp/tua_demo_rect.txt，供命令行精确只录面板、逐场景区域一致。
    func pinForDemo() {
        guard let panel, let screen = NSScreen.main else { return }
        let w: CGFloat = 960, h: CGFloat = 560
        let v = screen.visibleFrame
        let origin = NSPoint(x: v.minX + 40, y: v.maxY - h - 8)
        panel.setFrame(NSRect(origin: origin, size: NSSize(width: w, height: h)), display: true)
        // screencapture -R 用「左上原点、点」：x 同 AppKit minX；y = 主屏高 - 面板顶(maxY)。
        let fullH = screen.frame.height
        let rectX = origin.x.rounded(), rectY = (fullH - (origin.y + h)).rounded()
        let line = "\(Int(rectX)),\(Int(rectY)),\(Int(w)),\(Int(h))"
        try? line.write(toFile: "/tmp/tua_demo_rect.txt", atomically: true, encoding: .utf8)
    }

    func toggle(state: AppState) {
        if isVisible { hide() } else { show(state: state) }
    }

    // MARK: - 位置记忆

    private func savePosition() {
        // 演示模式不持久化临时钉定的矩形，否则下次正常启动会恢复到这个录屏专用位置。
        guard ProcessInfo.processInfo.environment["TUA_DEMO_SCENE"] == nil else { return }
        guard let frame = panel?.frame else { return }
        // 收起为浮球时（~60pt 小窗）不记尺寸，否则下次启动会把面板恢复成小球大小。
        guard frame.width >= 100, frame.height >= 100 else { return }
        // 宽高都记：高度已由用户边缘拉伸自由控制，不再由内容锁死
        let value = [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height]
        UserDefaults.standard.set(value, forKey: frameKey)
    }

    private func restorePosition(_ panel: NSPanel) -> Bool {
        guard let value = UserDefaults.standard.array(forKey: frameKey) as? [Double], value.count == 4 else {
            return false
        }
        // 恢复位置与宽高（高度无效时给默认 460）。
        let height = value[3] > 200 ? value[3] : 460
        // 宽度优先用「未展开基准宽」（与 FloatPanelView.homeWidthKey 共享），而不是 frameKey 里的实时宽：
        // 后者可能是详情向右扩后的宽，直接拿来当基准会跨启动越扩越宽。无基准宽时回到默认 640（而非不可信的 frameKey 宽）。
        let storedHome = UserDefaults.standard.double(forKey: "tua.floatPanel.homeWidth")
        var width = storedHome > 100 ? storedHome : 640
        // 安全网：恢复宽不得超过屏幕可视宽（留 80pt 边距），避免历史脏数据把窗撑到屏外。
        let screenW = NSScreen.screens.first(where: { $0.visibleFrame.intersects(NSRect(x: value[0], y: value[1], width: 1, height: 1)) })?.visibleFrame.width
            ?? NSScreen.main?.visibleFrame.width ?? 1440
        width = min(width, max(560, screenW - 80))
        let frame = NSRect(x: value[0], y: value[1], width: width, height: height)
        guard NSScreen.screens.contains(where: { $0.visibleFrame.intersects(frame) }) else { return false }
        panel.setFrame(frame, display: false)
        return true
    }

    private func positionTopRight(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        let origin = NSPoint(x: visible.maxX - size.width - 24, y: visible.maxY - size.height - 24)
        panel.setFrameOrigin(origin)
    }
}