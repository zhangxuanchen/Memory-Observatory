import AppKit
import SwiftUI

// MARK: - 模型设置界面

/// 外部大模型设置：选厂商、填 Key / 模型 / 可选自定义地址，带连通性测试。
struct ModelConfigView: View {
    @EnvironmentObject private var state: AppState
    /// 右上角 ✕ 的关闭动作（由控制器注入）。
    var onClose: () -> Void = {}

    @State private var provider: ModelConfig.Provider = .volcengine
    @State private var apiKey = ""
    @State private var model = ""
    @State private var chatURL = ""
    @State private var testMessage: String?
    @State private var testing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            // 中间内容区可滚动：再长的报错横幅也不会把底部按钮顶出窗口。
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 11) {
                    providerSelector
                    field(icon: "key.fill", label: "API Key", text: $apiKey,
                          secure: true, prompt: provider.keyHint)
                    field(icon: "cube.box.fill", label: "模型名 / 接入点", text: $model,
                          secure: false, prompt: provider.modelPlaceholder)
                    field(icon: "link", label: "自定义地址（可选）", text: $chatURL,
                          secure: false, prompt: provider.defaultChatURL)
                    if let testMessage { resultBanner(testMessage) }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            footer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(TUA.card)
        // 铺满到标题栏底下，消除顶部那条空白占位。
        .ignoresSafeArea()
        .onAppear { load() }
        .onChange(of: provider) { newProvider in
            testMessage = nil
            applyDefaultsIfNeeded(to: newProvider)
        }
    }

    // MARK: 顶部

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous).fill(accent.opacity(0.12))
                Image(systemName: "sparkles")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(accent)
            }
            .frame(width: 30, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text("模型设置")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(TUA.ink)
                Text("用于异常详情的 AI 分析 · Key 只存本机 config.json")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.muted)
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(TUA.faint)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 22)
        .padding(.bottom, 12)
    }

    // MARK: 厂商选择卡片

    private var providerSelector: some View {
        HStack(spacing: 10) {
            ForEach(ModelConfig.Provider.allCases) { p in
                providerCard(p)
            }
        }
    }

    private func providerCard(_ p: ModelConfig.Provider) -> some View {
        let selected = p == provider
        let tint = accent(for: p)
        return Button { provider = p } label: {
            HStack(spacing: 7) {
                Image(systemName: p.icon)
                    .font(.system(size: 14))
                    .foregroundStyle(selected ? tint : TUA.faint)
                Text(p.shortLabel)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(selected ? TUA.ink : TUA.muted)
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(tint)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(selected ? tint.opacity(0.08) : TUA.canvas)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(selected ? tint : TUA.border, lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: 输入行

    private func field(icon: String, label: String, text: Binding<String>, secure: Bool, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                Text(label)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(TUA.muted)
            }
            HStack(spacing: 8) {
                Group {
                    if secure {
                        SecureField(prompt, text: text)
                    } else {
                        TextField(prompt, text: text)
                    }
                }
                .textFieldStyle(.plain)
                .font(.system(size: 11))
                .foregroundStyle(TUA.ink)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous).fill(TUA.canvas)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous).stroke(TUA.border, lineWidth: 1)
            )
        }
    }

    // MARK: 测试结果横幅

    private func resultBanner(_ message: String) -> some View {
        let ok = message.hasPrefix("✓")
        let tone = ok ? TUA.success : TUA.error
        return HStack(alignment: .top, spacing: 8) {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                .font(.system(size: 13))
                .foregroundStyle(tone)
            Text(message.hasPrefix("✓") || message.hasPrefix("✗") ? String(message.dropFirst()) : message)
                .font(.system(size: 11))
                .foregroundStyle(tone)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tone.opacity(0.08))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(tone.opacity(0.25), lineWidth: 1)
        )
    }

    // MARK: 底部操作

    private var footer: some View {
        HStack(spacing: 10) {
            readinessPill
            Spacer()
            Button(testing ? "测试中…" : "测试连通") { runTest() }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(testing || !draft.isReady)
            Button("保存") { save() }
                .buttonStyle(PrimaryButtonStyle(color: accent))
                .keyboardShortcut(.defaultAction)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(TUA.canvas.opacity(0.6))
    }

    private var readinessPill: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(draft.isReady ? TUA.success : TUA.faint)
                .frame(width: 6, height: 6)
            Text(draft.isReady ? "已就绪" : "待填写")
                .font(.system(size: 10))
                .foregroundStyle(TUA.muted)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(Capsule().fill(TUA.card))
        .overlay(Capsule().stroke(TUA.border, lineWidth: 1))
    }

    // MARK: 配色

    private var accent: Color { accent(for: provider) }

    private func accent(for p: ModelConfig.Provider) -> Color {
        switch p {
        case .volcengine: return Color(hex: 0x1A56DB)
        case .aliyun: return Color(hex: 0xFF6A00)
        }
    }

    private func load() {
        let saved = state.config.model
        provider = saved?.provider ?? .volcengine
        apiKey = saved?.apiKey ?? ""
        // 模型 / 地址：未存过就按厂商接入文档预填，省得手敲。
        let savedModel = saved?.model.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        model = savedModel.isEmpty ? provider.defaultModel : savedModel
        let savedURL = saved?.chatURL.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        chatURL = savedURL.isEmpty ? provider.defaultChatURL : savedURL
    }

    /// 切厂商时：若模型 / 地址仍是某个默认值（或空），跟随刷新成新厂商默认；用户自定义的不动。
    private func applyDefaultsIfNeeded(to provider: ModelConfig.Provider) {
        let allModels = ModelConfig.Provider.allCases.map { $0.defaultModel }
        let allURLs = ModelConfig.Provider.allCases.map { $0.defaultChatURL }
        let trimmedModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedModel.isEmpty || allModels.contains(trimmedModel) {
            model = provider.defaultModel
        }
        let trimmedURL = chatURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedURL.isEmpty || allURLs.contains(trimmedURL) {
            chatURL = provider.defaultChatURL
        }
    }

    private var draft: ModelConfig {
        var config = ModelConfig()
        config.provider = provider
        config.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        config.model = model.trimmingCharacters(in: .whitespacesAndNewlines)
        config.chatURL = chatURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return config
    }

    private func save() {
        state.updateModelConfig(draft)
        testMessage = "✓ 已保存到本机配置"
    }

    private func runTest() {
        testing = true
        testMessage = nil
        let config = draft
        // 给足 token：推理型模型（如 deepseek 思考型）会先耗 reasoning，8 个会被吃光导致 content 为空。
        LLMClient.chat(config: config, system: "只回复两个字：成功", user: "ping", maxTokens: 512, timeout: 30) { result in
            DispatchQueue.main.async {
                testing = false
                switch result {
                case .success(let text):
                    testMessage = "✓ 连通：" + text.prefix(40)
                case .failure(let error):
                    testMessage = "✗ " + error.localizedDescription
                }
            }
        }
    }
}

/// 主操作按钮：强调色实心胶囊。
private struct PrimaryButtonStyle: ButtonStyle {
    let color: Color
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .background(
                Capsule().fill(color.opacity(configuration.isPressed ? 0.75 : 1))
            )
    }
}

/// 次操作按钮：描边胶囊。
private struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(TUA.ink.opacity(configuration.isPressed ? 0.5 : 1))
            .padding(.horizontal, 13)
            .padding(.vertical, 7)
            .background(
                Capsule().fill(TUA.card.opacity(configuration.isPressed ? 0.6 : 1))
            )
            .overlay(Capsule().stroke(TUA.border, lineWidth: 1))
    }
}

/// 模型设置窗口控制器：单例浮层窗口。
final class ModelConfigController {
    private var panel: NSPanel?

    func show(state: AppState) {
        let hosting = NSHostingView(
            rootView: ModelConfigView(onClose: { [weak self] in self?.close() })
                .environmentObject(state)
        )
        if let panel {
            panel.contentView = hosting
            panel.orderFrontRegardless()
            return
        }
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 384, height: 380),
            styleMask: [.titled, .closable, .fullSizeContentView, .nonactivatingPanel],
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
        panel.hasShadow = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        // 只留头部自绘的 ✕，隐藏系统红绿灯（关闭/最小化/缩放）避免两个关闭按钮重复。
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true
        panel.contentView = hosting
        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(
                x: visible.midX - panel.frame.width / 2,
                y: visible.midY - panel.frame.height / 2
            ))
        }
        panel.orderFrontRegardless()
        self.panel = panel
    }

    func close() {
        panel?.orderOut(nil)
    }
}
