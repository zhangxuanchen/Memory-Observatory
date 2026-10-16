import AppKit
import SwiftUI

/// 设置：接入（IDE 卡片，复用 M2 编排）+ 阈值可配（写回单一真相源并重跑检出）
/// + 接收端信息 + LLM 区「未启用深度解释」空态（M8 待落地，不谎报）。
struct SettingsPage: View {
    @EnvironmentObject private var state: AppState

    @State private var draft: Thresholds = .default
    @State private var revealKey = false
    @State private var saved = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PanelUI.gridGutter) {
                onboardingCard
                guardCard
                thresholdsCard
                receiverCard
                llmCard
            }
            .padding(PanelUI.pagePadding)
            .frame(maxWidth: 720, alignment: .leading)
        }
        .onAppear { draft = state.thresholds }
    }

    // MARK: 接入

    private var onboardingCard: some View {
        TUACard {
            SectionHeader(text: "接入 IDE · 各 Agent 独立计费（D14）")
            ForEach(Ide.allCases) { ide in installRow(ide) }
            if !state.installLog.isEmpty { logLines(state.installLog) }
            if !state.probeLog.isEmpty { logLines(state.probeLog) }
        }
    }

    private func installRow(_ ide: Ide) -> some View {
        HStack(spacing: 8) {
            Circle().fill(TUA.agentColor(ide.rawValue)).frame(width: 7, height: 7)
            VStack(alignment: .leading, spacing: 1) {
                Text(ide.displayName).font(.system(size: 12, weight: .medium)).foregroundStyle(TUA.ink)
                Text(ide.metered ? "积分可计量" : "无积分真值 · 仅轮次/工具")
                    .font(.system(size: 9)).foregroundStyle(ide.metered ? TUA.muted : TUA.warn)
            }
            Spacer()
            if state.busyAgent == ide.rawValue {
                Text("处理中…").font(.system(size: 10)).foregroundStyle(TUA.faint)
            } else {
                badge(state.isInstalled(ide))
                Button(state.isInstalled(ide) ? "断开" : "接入") {
                    state.isInstalled(ide) ? state.uninstall(ide) : state.install(ide)
                }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(TUA.ink)
                if state.isInstalled(ide) {
                    Button("验证") { state.probe(ide) }
                        .buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(TUA.ink)
                }
            }
        }
    }

    private func badge(_ installed: Bool) -> some View {
        Text(installed ? "已接入" : "未接入")
            .font(.system(size: 9, weight: .medium))
            .foregroundStyle(installed ? TUA.success : TUA.faint)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Capsule().fill((installed ? TUA.success : TUA.faint).opacity(0.12)))
    }

    // MARK: 护航（L4 事中干预）

    private var guardCard: some View {
        TUACard {
            SectionHeader(text: "实时护航",
                          trailing: AnyView(Text("口径 \(GuardThresholds.version)")
                              .font(.system(size: 9, design: .monospaced)).foregroundStyle(TUA.faint)))
            Text("AI 干活前先看一眼：发现它原地打转、上下文越滚越大、或同一件事反复失败时，弹窗问你要不要继续。助手没在跑或响应超时就直接放行，不拖累 IDE。")
                .font(.system(size: 10)).foregroundStyle(TUA.muted)
            Toggle(isOn: guardToggle) {
                Text("开启护航（发现苗头弹窗问你）").font(.system(size: 12)).foregroundStyle(TUA.ink)
            }
            .toggleStyle(.switch)
            .disabled(!anyInstalled)
            Toggle(isOn: breakerToggle) {
                Text("直接叫停（提醒好几次还刹不住就硬拦下来，慎用）").font(.system(size: 12)).foregroundStyle(TUA.ink)
            }
            .toggleStyle(.switch)
            .disabled(!state.config.guardEnabled || !anyInstalled)
            HStack(spacing: 8) {
                Text(guardHint).font(.system(size: 10)).foregroundStyle(TUA.faint)
                Spacer()
            }
        }
    }

    /// 是否有已接入的 IDE（护航 hook 依附于已接入的 wrapper/env）。
    private var anyInstalled: Bool { Ide.allCases.contains { state.isInstalled($0) } }

    private var guardHint: String {
        if !anyInstalled { return "需先接入 IDE；接入后开护航会自动装好检查钩子。" }
        if state.busyAgent != nil { return "正在重装 hook…" }
        return state.config.guardEnabled ? "已开启，重启 IDE 后生效。" : "开启会重装 hook，需重启 IDE 生效。"
    }

    private var guardToggle: Binding<Bool> {
        Binding(get: { state.config.guardEnabled },
                set: { state.setGuardEnabled($0) })
    }
    private var breakerToggle: Binding<Bool> {
        Binding(get: { state.config.guardBreakerActive },
                set: { state.setGuardBreaker($0) })
    }

    // MARK: 阈值

    private var thresholdsCard: some View {
        TUACard {
            SectionHeader(text: "异常检出阈值",
                          trailing: AnyView(Text("口径 \(Thresholds.version)").font(.system(size: 9, design: .monospaced)).foregroundStyle(TUA.faint)))
            Text("判据改动即视为新口径版本；改后自动重跑近 7 天检出。")
                .font(.system(size: 10)).foregroundStyle(TUA.muted)
            stepperRow("高耗积分下限", value: $draft.creditAbsolute, in: 0...1000, step: 0.5)
            stepperRow("高延迟下限(秒)", value: secondsBinding, in: 1...600, step: 5)
            stepperRow("循环重复次数", value: $draft.loopRepeatN, in: 2...200, step: 1)
            stepperRow("压缩频次上限", value: $draft.compactFreqN, in: 1...100, step: 1)
            stepperRow("连续失败次数", value: $draft.retryStormN, in: 2...100, step: 1)
            stepperRow("遗忘瞬时量", value: $draft.expireBurstN, in: 2...1000, step: 5)
            HStack(spacing: 10) {
                Button("应用") {
                    state.updateThresholds(draft)
                    flashSaved()
                }
                Button("恢复默认") {
                    draft = .default
                    state.updateThresholds(.default)
                    flashSaved()
                }
                if saved { Text("已保存并重跑").font(.system(size: 10)).foregroundStyle(TUA.success) }
                Spacer()
            }
            .font(.system(size: 12)).buttonStyle(.plain).foregroundStyle(TUA.ink)
        }
    }

    /// 秒 ↔ 毫秒（`slowCallAbsoluteMs` 以毫秒存储，UI 以秒呈现）。
    private var secondsBinding: Binding<Double> {
        Binding(
            get: { Double(draft.slowCallAbsoluteMs) / 1000 },
            set: { draft.slowCallAbsoluteMs = Int($0 * 1000) }
        )
    }

    private func stepperRow(_ title: String, value: Binding<Double>,
                            in range: ClosedRange<Double>, step: Double) -> some View {
        stepperShell(title) {
            Stepper(value: value, in: range, step: step) {
                Text(fmt(value.wrappedValue))
                    .font(.system(size: 12, design: .monospaced)).foregroundStyle(TUA.ink)
                    .frame(minWidth: 48, alignment: .trailing)
            }
        }
    }

    private func stepperRow(_ title: String, value: Binding<Int>,
                            in range: ClosedRange<Int>, step: Int) -> some View {
        stepperShell(title) {
            Stepper(value: value, in: range, step: step) {
                Text("\(value.wrappedValue)")
                    .font(.system(size: 12, design: .monospaced)).foregroundStyle(TUA.ink)
                    .frame(minWidth: 48, alignment: .trailing)
            }
        }
    }

    private func stepperShell<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        HStack {
            Text(title).font(.system(size: 11)).foregroundStyle(TUA.muted)
            Spacer()
            content()
        }
    }

    private func fmt(_ value: Double) -> String {
        value == value.rounded() ? "\(Int(value))" : String(format: "%.1f", value)
    }

    private func flashSaved() {
        saved = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { saved = false }
    }

    // MARK: 接收端

    private var receiverCard: some View {
        TUACard {
            SectionHeader(text: "接收端")
            infoRow("状态", state.receiverMessage, tone: state.receiverRunning ? TUA.success : TUA.error)
            infoRow("上报地址", state.config.endpoint, mono: true)
            HStack(spacing: 6) {
                Text("密钥").font(.system(size: 11)).foregroundStyle(TUA.muted).frame(width: 64, alignment: .leading)
                Text(revealKey ? state.config.apiKey : String(repeating: "•", count: 16))
                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(TUA.muted)
                    .lineLimit(1).truncationMode(.middle)
                Spacer()
                Button(revealKey ? "隐藏" : "显示") { revealKey.toggle() }
                    .buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(TUA.ink)
                Button("复制") { copy(state.config.apiKey) }
                    .buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(TUA.ink)
            }
            Button("复制 curl 示例") { copy(curlSample) }
                .buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(TUA.ink)
        }
    }

    // MARK: LLM（M8 空态）

    private var llmCard: some View {
        TUACard {
            SectionHeader(text: "深度解释（LLM）")
            EmptyState(text: "未启用深度解释 · 属 M8，本轮未落地。\n规则检出与只读建议不受影响。",
                       systemImage: "cpu")
                .padding(.vertical, 16)
        }
    }

    // MARK: 组件

    private func infoRow(_ title: String, _ value: String, mono: Bool = false, tone: Color = TUA.muted) -> some View {
        HStack(spacing: 6) {
            Text(title).font(.system(size: 11)).foregroundStyle(TUA.muted).frame(width: 64, alignment: .leading)
            Text(value)
                .font(.system(size: 11, design: mono ? .monospaced : .default))
                .foregroundStyle(tone).lineLimit(1).truncationMode(.middle)
            Spacer()
        }
    }

    private func logLines(_ lines: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Divider().overlay(TUA.border.opacity(0.6))
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(.system(size: 10, design: .monospaced)).foregroundStyle(TUA.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func copy(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }

    private var curlSample: String {
        """
        curl -s -X POST \(state.config.endpoint) \\
          -H "Authorization: Bearer \(state.config.apiKey)" \\
          -H "Content-Type: application/json" \\
          -d '{"ts":\(TUAEvent.nowMillis()),"ide":"manual","model":"claude-sonnet-4.5","workspace_root":"/Users/you/project","session_id":"s1","event_name":"PostToolUse","tool_name":"Read","io_kind":"file_read","action_idx":1,"credits":0.66,"token_source":"measured","latency_ms":1200,"status":"ok"}'
        """
    }
}
