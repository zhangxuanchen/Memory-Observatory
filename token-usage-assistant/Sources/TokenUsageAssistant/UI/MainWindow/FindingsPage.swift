import SwiftUI

/// 异常：findings 列表（按类型 / 严重度筛选）+ 每条展开 `evidence`（命中阈值 / 实测 / 分位）。
/// 显式标注口径版本（改了判据即新口径，承「不拿两把尺子量同一批数据」纪律）。
struct FindingsPage: View {
    @EnvironmentObject private var state: AppState

    @State private var severityFilter: String? = nil
    @State private var kindFilter: String? = nil
    @State private var expanded: Set<Int64> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Divider().overlay(TUA.border)
            if filtered.isEmpty {
                EmptyState(text: "该口径下暂无检出 · 接入 IDE 并积累轮次后自动出现",
                           systemImage: "checkmark.seal")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                list
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear { state.loadSnapshots() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text("异常检出").font(.system(size: 15, weight: .semibold)).foregroundStyle(TUA.ink)
                Text("口径 \(Thresholds.version)")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Capsule().fill(Color.black.opacity(0.05)))
                Spacer()
                if state.detecting {
                    Text("检出中…").font(.system(size: 10)).foregroundStyle(TUA.faint)
                }
            }
            HStack(spacing: 8) {
                FilterChip(title: "全部", selected: severityFilter == nil) { severityFilter = nil }
                FilterChip(title: "高危", selected: severityFilter == "high") { severityFilter = "high" }
                FilterChip(title: "警告", selected: severityFilter == "warn") { severityFilter = "warn" }
            }
            if !availableKinds.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterChip(title: "所有类型", selected: kindFilter == nil) { kindFilter = nil }
                        ForEach(availableKinds, id: \.self) { kind in
                            FilterChip(title: AnomalyDetector.kindLabel(kind), selected: kindFilter == kind) {
                                kindFilter = kind
                            }
                        }
                    }
                }
            }
        }
        .padding(PanelUI.pagePadding)
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(filtered) { finding in card(finding) }
            }
            .padding(PanelUI.pagePadding)
        }
    }

    private func card(_ finding: FindingRow) -> some View {
        let tone = finding.isHigh ? TUA.error : TUA.warn
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(finding.kindLabel)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(tone)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Capsule().fill(tone.opacity(0.12)))
                Circle().fill(TUA.agentColor(finding.ide)).frame(width: 6, height: 6)
                Text(finding.ide.isEmpty ? "未知" : finding.ide)
                    .font(.system(size: 11)).foregroundStyle(TUA.muted)
                Text(finding.workspaceLabel)
                    .font(.system(size: 12)).foregroundStyle(TUA.ink).lineLimit(1)
                Spacer()
                Text(TUA.number(finding.credits))
                    .font(.system(size: 12, weight: .medium, design: .monospaced)).foregroundStyle(TUA.ink)
                Text("· \(TUA.duration(finding.latencyMs))")
                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(TUA.faint)
                Button {
                    toggle(finding.id)
                } label: {
                    Image(systemName: expanded.contains(finding.id) ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10)).foregroundStyle(TUA.faint)
                }
                .buttonStyle(.plain)
            }
            if expanded.contains(finding.id) {
                let insight = AnomalyInsight.explain(
                    kind: finding.kind, evidence: finding.evidence,
                    credits: finding.credits, latencyMs: finding.latencyMs)
                VStack(alignment: .leading, spacing: 6) {
                    Divider().overlay(TUA.border.opacity(0.6))
                    insightLine("现象", insight.symptom, tone)
                    insightLine("原因", insight.cause, TUA.muted)
                    insightLine("影响", insight.impact, TUA.error)
                    insightLine("怎么改", insight.fix, TUA.success)
                    Text("依据指标").font(.system(size: 10)).foregroundStyle(TUA.faint)
                        .padding(.top, 2)
                    ForEach(Array(finding.evidence.keys.sorted()), id: \.self) { key in
                        HStack(spacing: 6) {
                            Text(key).font(.system(size: 10, design: .monospaced)).foregroundStyle(TUA.muted)
                            Spacer()
                            Text(evidenceValue(finding.evidence[key]))
                                .font(.system(size: 10, design: .monospaced)).foregroundStyle(TUA.ink)
                        }
                    }
                    Text("轮次：\(finding.turnId)")
                        .font(.system(size: 9, design: .monospaced)).foregroundStyle(TUA.faint)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.card))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).stroke(TUA.border, lineWidth: 1))
    }

    /// 四段式中的一行：彩色小标签 + 正文。
    private func insightLine(_ label: String, _ text: String, _ color: Color) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(color)
                .padding(.horizontal, 4)
                .padding(.vertical, 1)
                .background(RoundedRectangle(cornerRadius: 3).fill(color.opacity(0.12)))
            Text(text)
                .font(.system(size: 11))
                .foregroundStyle(TUA.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var filtered: [FindingRow] {
        state.findings.filter { finding in
            (severityFilter == nil || finding.severity == severityFilter)
                && (kindFilter == nil || finding.kind == kindFilter)
        }
    }

    private var availableKinds: [String] {
        Array(Set(state.findings.map(\.kind))).sorted()
    }

    private func evidenceValue(_ value: Any?) -> String {
        if let number = value as? Double {
            return number == number.rounded() ? "\(Int(number))" : TUA.number(number)
        }
        if let number = value as? Int { return "\(number)" }
        if let number = value as? Int64 { return "\(number)" }
        if let text = value as? String { return text }
        return "-"
    }

    private func toggle(_ id: Int64) {
        if expanded.contains(id) { expanded.remove(id) } else { expanded.insert(id) }
    }
}
