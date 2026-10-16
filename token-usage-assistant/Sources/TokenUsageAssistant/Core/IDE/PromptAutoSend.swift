import AppKit
import ApplicationServices

/// 「指导 → 不指导」切换时，把当前被拦的原文送回 IDE 会话输入框：只投一个 ⌘V 粘贴、不回车。
/// 护航卡/浮窗是非激活面板（.nonactivatingPanel），拨开关不抢走 IDE 的键盘焦点，Qoder CN 仍是前台键窗口，
/// 故 ⌘V 会落到仍聚焦的输入框——把原文“重新复制回会话框”，随后由用户自己点提交（不自动提交、不切前台）。
/// 无「辅助功能」授权时粘贴事件被系统静默丢弃：此时原文已进剪贴板，用户手动 ⌘V 即可。
enum PromptAutoSend {

    static func restoreToInput(prompt raw: String, ide: String?) {
        let prompt = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        putOnPasteboard(prompt)   // 先入剪贴板（兼做无授权时的手动 ⌘V 兜底）
        guard isTrustedForPosting() else { return }
        // 不 activate、不回车：焦点本就在 IDE 输入框，稍候让切换落定后只投 ⌘V。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            paste()
        }
    }

    private static func putOnPasteboard(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
    }

    /// 模拟键需要辅助功能授权；未授权时弹一次系统引导（用户去设置里勾选后无需重启 App）。
    private static func isTrustedForPosting() -> Bool {
        if AXIsProcessTrusted() { return true }
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
        return false
    }

    /// 只投一次 ⌘V（kVK_ANSI_V=9 + Command）到当前键窗口（IDE 输入框），走 HID 事件通道；不回车、不激活任何 App。
    private static func paste() {
        let src = CGEventSource(stateID: .combinedSessionState)
        let vDown = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: true)
        vDown?.flags = .maskCommand
        let vUp = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: false)
        vUp?.flags = .maskCommand
        vDown?.post(tap: .cghidEventTap)
        vUp?.post(tap: .cghidEventTap)
    }
}
