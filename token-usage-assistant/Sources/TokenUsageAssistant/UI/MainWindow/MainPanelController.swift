import AppKit
import SwiftUI

/// 主面板窗口（按需打开的独立窗口）：`NSWindowController` 承载 SwiftUI，与悬浮窗同款管理。
/// 保持 `.accessory` 无常驻窗口形态——只有用户点「打开主面板」时才创建并前置。
final class MainPanelController {
    private var window: NSWindow?
    private let frameKey = "tua.mainPanel.frame"

    func show(state: AppState) {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let hosting = NSHostingController(rootView: MainWindowView().environmentObject(state))
        let window = NSWindow(contentViewController: hosting)
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.title = "Agent 用量助手"
        window.contentMinSize = NSSize(width: 860, height: 560)
        window.isReleasedWhenClosed = false
        window.collectionBehavior = [.fullScreenAuxiliary]

        if !restoreFrame(window) {
            window.setContentSize(NSSize(width: 1040, height: 680))
            window.center()
        }
        NotificationCenter.default.addObserver(
            forName: NSWindow.didResizeNotification, object: window, queue: .main
        ) { [weak self] _ in self?.saveFrame() }
        NotificationCenter.default.addObserver(
            forName: NSWindow.didMoveNotification, object: window, queue: .main
        ) { [weak self] _ in self?.saveFrame() }

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.window = window
    }

    // MARK: - 位置记忆

    private func saveFrame() {
        guard let frame = window?.frame else { return }
        let value = [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height]
        UserDefaults.standard.set(value, forKey: frameKey)
    }

    private func restoreFrame(_ window: NSWindow) -> Bool {
        guard let value = UserDefaults.standard.array(forKey: frameKey) as? [Double], value.count == 4 else {
            return false
        }
        let frame = NSRect(x: value[0], y: value[1], width: value[2], height: value[3])
        guard NSScreen.screens.contains(where: { $0.visibleFrame.intersects(frame) }) else { return false }
        window.setFrame(frame, display: false)
        return true
    }
}
