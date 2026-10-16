import AppKit
import SwiftUI

/// 菜单栏面板：状态 + 今日快照 + 接入信息（地址/密钥/curl）+ 快捷操作。
struct MenuBarView: View {
    @EnvironmentObject private var state: AppState

    @State private var revealKey = false
    @State private var toast: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            guidanceRow
            if let focus = state.focus { focusChip(focus) }
            statsCard
            onboardingCard
            receiverCard
            actions
        }
        .padding(16)
        .frame(width: 340)
        .background(TUA.canvas)
        .onAppear { state.refreshInstalled() }
    }

    // MARK: - 区块

    private var header: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(state.receiverRunning ? TUA.success : TUA.error)
                .frame(width: 8, height: 8)
            Text("Agent 用量助手")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(TUA.ink)
            Spacer()
            Text(state.receiverRunning ? "运行中" : "未运行")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(state.receiverRunning ? TUA.success : TUA.error)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    Capsule().fill((state.receiverRunning ? TUA.success : TUA.error).opacity(0.12))
                )
        }
    }

    /// 常驻「指导/不指导」总闸：与护航卡、设置页共用 guardEnabled 总闸。
    /// 不依赖卡片弹出——关掉后即使不再拦，也能从这里一键开回。
    private var guidanceRow: some View {
        HStack(spacing: 8) {
            Image(systemName: state.config.guardEnabled ? "shield.lefthalf.filled" : "shield.slash")
                .font(.system(size: 12))
                .foregroundStyle(state.config.guardEnabled ? TUA.success : TUA.faint)
            VStack(alignment: .leading, spacing: 1) {
                Text(state.config.guardEnabled ? "提问前帮你把关 · 没写清楚的会被拦下" : "已关 · 不再检查，全部直接发送")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(TUA.ink)
                Text("关掉后任何提问都不再检查、直接发出去，随时可开回")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
            }
            Spacer()
            Toggle("",
                   isOn: Binding(get: { state.config.guardEnabled },
                                 set: { state.setGuardEnabled($0) }))
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.small)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.card))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    /// 工作区视角提示（悬浮窗切换后，菜单栏同步展示并可一键返回）。
    private func focusChip(_ focus: WorkspaceFocus) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "scope")
                .font(.system(size: 9))
                .foregroundStyle(TUA.muted)
            Text("工作区视角：\(focus.name)")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(TUA.ink)
                .lineLimit(1)
            Spacer()
            Button("返回全部") { state.clearFocus() }
                .buttonStyle(.plain)
                .font(.system(size: 10))
                .foregroundStyle(TUA.muted)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(Color.black.opacity(0.03)))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    private var statsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("今日用量 · 按 Agent（各自单位，不合计）")
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
            if state.today.agents.isEmpty {
                Text("尚无用量 · 接入 IDE 后自动统计")
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.muted)
            } else {
                VStack(spacing: 9) {
                    ForEach(state.today.agents) { agent in agentRow(agent) }
                }
            }
            HStack(spacing: 18) {
                metric("事件", "\(state.today.events)")
                metric("工作区", "\(state.today.workspaces)")
                Spacer()
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.card))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    private func agentRow(_ agent: AgentStats) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle()
                .fill(TUA.agentColor(agent.id))
                .frame(width: 7, height: 7)
            Text(agent.label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(TUA.ink)
            Spacer()
            Text(TUA.number(agent.credits))
                .font(.system(size: 18, weight: .semibold, design: .monospaced))
                .foregroundStyle(TUA.ink)
            Text("积分")
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
            Text("· \(agent.turns) 轮")
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
        }
    }

    /// 一键接入卡片：各 Agent 独立接入 / 断开 / 验证（D14：计费口径不同，分别处理）。
    private var onboardingCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("接入 IDE · 各 Agent 独立计费")
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
            VStack(spacing: 9) {
                ForEach(Ide.allCases) { ide in agentInstallRow(ide) }
            }
            if !state.installLog.isEmpty { logView(state.installLog, tone: TUA.muted) }
            if !state.probeLog.isEmpty { logView(state.probeLog, tone: TUA.muted) }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.card))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    private func agentInstallRow(_ ide: Ide) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(TUA.agentColor(ide.rawValue))
                .frame(width: 7, height: 7)
            VStack(alignment: .leading, spacing: 1) {
                Text(ide.displayName)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(TUA.ink)
                Text(ide.metered ? "积分可计量" : "无积分真值 · 仅轮次/工具")
                    .font(.system(size: 9))
                    .foregroundStyle(ide.metered ? TUA.muted : TUA.warn)
            }
            Spacer()
            if state.busyAgent == ide.rawValue {
                Text("处理中…")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
            } else {
                installBadge(state.isInstalled(ide))
                Button(state.isInstalled(ide) ? "断开" : "接入") {
                    state.isInstalled(ide) ? state.uninstall(ide) : state.install(ide)
                }
                .buttonStyle(.plain)
                .font(.system(size: 10))
                .foregroundStyle(TUA.ink)
                if state.isInstalled(ide) {
                    Button("验证") { state.probe(ide) }
                        .buttonStyle(.plain)
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.ink)
                }
            }
        }
    }

    private func installBadge(_ installed: Bool) -> some View {
        Text(installed ? "已接入" : "未接入")
            .font(.system(size: 9, weight: .medium))
            .foregroundStyle(installed ? TUA.success : TUA.faint)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill((installed ? TUA.success : TUA.faint).opacity(0.12)))
    }

    private func logView(_ lines: [String], tone: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(tone)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var receiverCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            row("上报地址", state.config.endpoint, mono: true)
            HStack(spacing: 6) {
                Text("密钥")
                    .font(.system(size: 10))
                    .foregroundStyle(TUA.faint)
                    .frame(width: 52, alignment: .leading)
                Text(revealKey ? state.config.apiKey : String(repeating: "•", count: 12))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                iconButton(revealKey ? "eye.slash" : "eye") { revealKey.toggle() }
                iconButton("doc.on.doc") { copy(state.config.apiKey, "已复制密钥") }
            }
            HStack(spacing: 8) {
                Button("复制 curl 示例") { copy(curlSample, "已复制 curl 示例") }
                Button("发送测试事件") {
                    state.sendSampleEvent { message in
                        toast = message
                        state.loadSnapshots()
                    }
                }
                Spacer()
            }
            .font(.system(size: 11))
            .buttonStyle(.plain)
            .foregroundStyle(TUA.ink)

            if let toast {
                Text(toast)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.card))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    private var actions: some View {
        HStack(spacing: 8) {
            Button(state.floatPanel.isVisible ? "隐藏悬浮窗" : "显示悬浮窗") {
                state.toggleFloatPanel()
            }
            Button("模型设置…") { state.openModelConfig() }
            Button("刷新") { state.loadSnapshots() }
            Spacer()
            Button("退出") { NSApp.terminate(nil) }
        }
        .font(.system(size: 11))
        .buttonStyle(.plain)
        .foregroundStyle(TUA.ink)
    }

    // MARK: - 组件

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                .foregroundStyle(TUA.ink)
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
        }
    }

    private func row(_ title: String, _ value: String, mono: Bool) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(TUA.faint)
                .frame(width: 52, alignment: .leading)
            Text(value)
                .font(.system(size: 10, design: mono ? .monospaced : .default))
                .foregroundStyle(TUA.muted)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            iconButton("doc.on.doc") { copy(value, "已复制") }
        }
    }

    private func iconButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(TUA.faint)
                .frame(width: 16, height: 16)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func copy(_ text: String, _ message: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        toast = message
    }

    private var curlSample: String {
        """
        curl -s -X POST \(state.config.endpoint) \\
          -H "Authorization: Bearer \(state.config.apiKey)" \\
          -H "Content-Type: application/json" \\
          -d '{"ts":\(TUAEvent.nowMillis()),"ide":"manual","model":"claude-sonnet-4.5","workspace_root":"/Users/you/project","session_id":"s1","turn_id":"s1#1","event_name":"PostToolUse","tool_name":"Read","io_kind":"file_read","action_idx":1,"credits":0.66,"token_source":"measured","latency_ms":1200,"status":"ok"}'
        """
    }
}