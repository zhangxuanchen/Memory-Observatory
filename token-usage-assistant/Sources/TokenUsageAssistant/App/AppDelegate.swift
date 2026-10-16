import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // 开发用隐藏入口：带 --tua-eval 时执行评测子命令并退出，不进 UI（D1）。
        EvalCLI.handleIfPresent(CommandLine.arguments)

        // 菜单栏常驻应用：无 Dock 图标、无主窗口。
        NSApp.setActivationPolicy(.accessory)

        let state = AppState.shared
        state.startReceiver()
        state.showFloatPanel()

        // 历史提问语料回灌（M9 / 测评体系 Phase 0）：对用户封闭，启动后低优先级后台静默跑一次。
        // 延后几秒避开接收端/面板初始化；只读 transcript、不碰实时链路。
        let db = state.database
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 3) {
            _ = PromptCorpusImporter.shared.runOnce(into: db)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }
}