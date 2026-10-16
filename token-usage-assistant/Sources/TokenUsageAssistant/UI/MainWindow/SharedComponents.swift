import SwiftUI

/// 主面板与悬浮窗共用的展示组件（延续白卡 + 细边 + 软阴影的极简语言）。
enum PanelUI {
    static let pagePadding: CGFloat = 20
    static let gridGutter: CGFloat = 14
}

/// 迷你趋势线（积分逐小时，按 Agent 分色）。从悬浮窗抽出供主面板复用。
struct SparklineView: View {
    let values: [Double]
    let color: Color
    var filled: Bool = true

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
                    if filled {
                        Path { path in
                            path.move(to: CGPoint(x: 0, y: proxy.size.height))
                            for point in points { path.addLine(to: point) }
                            path.addLine(to: CGPoint(x: proxy.size.width, y: proxy.size.height))
                            path.closeSubpath()
                        }
                        .fill(color.opacity(0.12))
                    }
                    Path { path in
                        path.move(to: points[0])
                        for point in points.dropFirst() { path.addLine(to: point) }
                    }
                    .stroke(color.opacity(0.85), style: StrokeStyle(lineWidth: 1.4, lineJoin: .round))
                }
            }
        }
    }
}

/// 卡片容器：白底 + 细边 + 圆角。
struct TUACard<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.card))
            .overlay(
                RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                    .stroke(TUA.border, lineWidth: 1)
            )
    }
}

/// 单个 KPI 指标。
struct StatMetric: View {
    let title: String
    let value: String
    var unit: String?
    var tone: Color = TUA.ink

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(size: 24, weight: .semibold, design: .monospaced))
                    .foregroundStyle(tone)
                if let unit {
                    Text(unit)
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.faint)
                }
            }
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(TUA.muted)
        }
    }
}

/// 区块小标题。
struct SectionHeader: View {
    let text: String
    var trailing: AnyView? = nil

    var body: some View {
        HStack(spacing: 6) {
            Text(text)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(TUA.ink)
            Spacer()
            if let trailing { trailing }
        }
    }
}

/// 空态占位（诚实标注，不谎报）。
struct EmptyState: View {
    let text: String
    var systemImage: String = "tray"

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 22))
                .foregroundStyle(TUA.faint.opacity(0.7))
            Text(text)
                .font(.system(size: 12))
                .foregroundStyle(TUA.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// 胶囊筛选按钮。
struct FilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(selected ? TUA.card : TUA.muted)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(selected ? TUA.ink : Color.black.opacity(0.05)))
        }
        .buttonStyle(.plain)
    }
}
