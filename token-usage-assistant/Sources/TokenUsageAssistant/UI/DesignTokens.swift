import SwiftUI

/// 极简设计 token（对齐 memory-observatory，DESIGN §7.1）。
enum TUA {
    static let canvas = Color(hex: 0xFAFAFA)
    static let card = Color.white
    static let ink = Color(hex: 0x1A1A1A)
    static let muted = Color(hex: 0x6B6B6B)
    static let faint = Color(hex: 0x9A9A9A)
    static let border = Color.black.opacity(0.12)
    static let success = Color(hex: 0x2E7D32)
    static let warn = Color(hex: 0xB26A00)
    static let error = Color(hex: 0xC62828)

    enum Radius {
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
    }

    static func number(_ value: Double) -> String {
        if value == 0 { return "0" }
        if abs(value) < 1 { return String(format: "%.3f", value) }
        return String(format: "%.2f", value)
    }

    static func time(_ millis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// 耗时展示：<60s 用秒，否则 m:ss。
    static func duration(_ millis: Int) -> String {
        guard millis > 0 else { return "0s" }
        let seconds = millis / 1000
        if seconds < 60 { return "\(seconds)s" }
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    /// 按 Agent 分色（D14：各 Agent 独立展示，颜色帮助区分而非表示可比）。
    /// WorkBuddy 与 Qoder 同族（都是本地 IDE Agent），标签渲染口径保持一致。
    static func agentColor(_ ide: String) -> Color {
        switch ide.lowercased() {
        case "qoder", "workbuddy": return Color(hex: 0x1A56DB)
        case "cursor": return Color(hex: 0x6D28D9)
        case "claude": return Color(hex: 0xB26A00)
        default: return Color(hex: 0x6B6B6B)
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}