import AppKit
import SwiftUI

/// 事前护航卡（M14 T1）：一条提问被 `UserPromptSubmit` 拦下后，IDE 端只能展示一行
/// stderr，富交互落在这里。两种形态：
/// ① 低就绪度（「指导」闸）：原文 + 规则标注问题 + 损失估计，「AI 一键改写」由内置小 Agent
///   多轮打磨出多条候选让用户挑；选中一条复制回 IDE 重提。
/// ② 敏感信息（独立闸）：只提示命中的凭据类型 + 本地打码原文定位，不给改写也不给复制原文——
///   凭据不该再被任何链路送出去。仅当 `state.lastPrecheck != nil` 时插入。
struct PrecheckCard: View {
    @EnvironmentObject private var state: AppState

    /// 用户选中的候选下标；nil 时默认高亮第一条。随新拦截（ts 变化）重置。
    @State private var selectedRewrite: Int?
    @State private var seededTs: Int64 = -1
    @State private var copied = false

    private var options: [String] { state.precheckRewrites }
    private var selectedIndex: Int? {
        if options.isEmpty { return nil }
        if let s = selectedRewrite, options.indices.contains(s) { return s }
        return 0
    }

    var body: some View {
        if let snap = state.lastPrecheck {
            if snap.secretHits.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    header(snap)
                    original(snap.prompt)
                    if !snap.problems.isEmpty { problems(snap.problems) }
                    lossLine(snap)
                    rewriteArea(snap)
                    footer(snap)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.canvas))
                .overlay(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
                    .stroke(TUA.warn.opacity(0.5), lineWidth: 1))
                .onAppear { seed(snap) }
                .onChange(of: snap.ts) { _ in seed(snap) }
            } else {
                secretCard(snap)
            }
        }
    }

    // MARK: - 敏感信息拦截形态（只提示命中类型，零 LLM、不提供回发通道）

    private func secretCard(_ snap: AppState.PrecheckIntervention) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(TUA.error)
                Text("敏感信息护航 · 已拦下，密钥没发出去")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(TUA.ink)
                Spacer()
                Text("有把握是密钥")
                    .font(.system(size: 9, weight: .medium, design: .monospaced))
                    .foregroundStyle(TUA.error)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(TUA.error.opacity(0.12)))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("命中类型（只报种类，没存内容）")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                problems(snap.secretHits)
            }
            // 本地打码原文：只帮用户定位该删哪段，不走剪贴板/不回填输入框
            VStack(alignment: .leading, spacing: 4) {
                Text("你的提问（本地打码，仅帮定位）")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                Text(SecretRedactor.redact(snap.prompt))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(TUA.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: TUA.Radius.sm).fill(TUA.card))
                    .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm).stroke(TUA.border, lineWidth: 1))
            }
            Text("请在 IDE 里把密钥删掉再发；确实要原样发送，临时关掉下方「保密把关」开关。")
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("关闭") { state.dismissPrecheck() }
                    .controlSize(.small)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous).fill(TUA.canvas))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.md, style: .continuous)
            .stroke(TUA.error.opacity(0.5), lineWidth: 1))
        .onAppear { seed(snap) }
        .onChange(of: snap.ts) { _ in seed(snap) }
    }

    // MARK: - 子块

    private func header(_ snap: AppState.PrecheckIntervention) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(TUA.warn)
            Text("事前护航 · 这条提问已拦下")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TUA.ink)
            Spacer()
            Text("清晰度 \(Int(snap.score))/15 · 低于 8 分会拦")
                .font(.system(size: 9, weight: .medium, design: .monospaced))
                .foregroundStyle(TUA.warn)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Capsule().fill(TUA.warn.opacity(0.12)))
        }
    }

    /// 原始提问只读展示（不再让用户在框里写）。
    private func original(_ prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("你的提问")
                .font(.system(size: 9))
                .foregroundStyle(TUA.faint)
            Text(prompt)
                .font(.system(size: 11))
                .foregroundStyle(TUA.muted)
                .fixedSize(horizontal: false, vertical: true)
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: TUA.Radius.sm).fill(TUA.card))
                .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm).stroke(TUA.border, lineWidth: 1))
        }
    }

    private func problems(_ problems: [String]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(problems, id: \.self) { p in
                HStack(alignment: .top, spacing: 5) {
                    Image(systemName: "circle.fill")
                        .font(.system(size: 4))
                        .foregroundStyle(TUA.warn)
                        .padding(.top, 5)
                    Text(p)
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func lossLine(_ snap: AppState.PrecheckIntervention) -> some View {
        Group {
            if let loss = snap.estimatedLoss {
                Text("这类没头没尾的提问，在该工作区最常一次烧掉约 \(String(format: "%.1f", loss)) 积分（近 7 天最高那一档）")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
            }
        }
    }

    // MARK: 改写区（小 Agent：选，不写）

    @ViewBuilder private func rewriteArea(_ snap: AppState.PrecheckIntervention) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if state.precheckRounds > 0 { statusLine }
            if let clarify = state.precheckClarify { clarifyBlock(clarify) }
            if !options.isEmpty {
                Text("挑一条改好的（点一下选上，再复制回 IDE 重新发）")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                ForEach(Array(options.enumerated()), id: \.offset) { idx, option in
                    optionCard(idx: idx, option: option)
                }
                if let sel = selectedIndex { actionRow(snap, sel) }
            } else if state.precheckRewriting {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(state.precheckRounds > 0 ? "小 Agent 打磨中 · 第 \(state.precheckRounds + 1) 轮…" : "AI 正在改写…")
                        .font(.system(size: 10))
                        .foregroundStyle(TUA.muted)
                }
            } else if let error = state.precheckRewriteError {
                Text("改写失败：\(error)")
                    .font(.system(size: 10))
                    .foregroundStyle(TUA.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// 轮次 + 用量状态行（诚实报已用几轮 / 多少 token，不谎称积分）。
    private var statusLine: some View {
        HStack(spacing: 6) {
            Image(systemName: "cpu").font(.system(size: 9)).foregroundStyle(TUA.faint)
            Text("小 Agent · 第 \(state.precheckRounds)/\(PromptAgent.maxRounds) 轮")
                .font(.system(size: 9, design: .monospaced)).foregroundStyle(TUA.faint)
            if state.precheckTokens > 0 {
                Text("· 已耗 \(state.precheckTokens) tokens")
                    .font(.system(size: 9, design: .monospaced)).foregroundStyle(TUA.faint)
            }
            if state.precheckRefined {
                Text("· 已定稿").font(.system(size: 9)).foregroundStyle(TUA.success)
            }
            Spacer()
        }
    }

    /// 关键歧义 → 让用户点方向（两者结合里的「用户参与」那半）。
    private func clarifyBlock(_ clarify: AgentClarify) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 5) {
                Image(systemName: "questionmark.circle.fill")
                    .font(.system(size: 11)).foregroundStyle(TUA.warn)
                Text(clarify.question)
                    .font(.system(size: 11, weight: .medium)).foregroundStyle(TUA.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("选个方向，小 Agent 据此细化：")
                .font(.system(size: 9)).foregroundStyle(TUA.faint)
            ForEach(clarify.options, id: \.self) { opt in
                Button { state.answerPrecheckClarify(opt) }
                label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.turn.down.right").font(.system(size: 9)).foregroundStyle(TUA.faint)
                        Text(opt).font(.system(size: 11)).foregroundStyle(TUA.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .padding(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: TUA.Radius.sm).fill(TUA.card))
                    .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm).stroke(TUA.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(state.precheckRewriting)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm).fill(TUA.warn.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm).stroke(TUA.warn.opacity(0.35), lineWidth: 1))
    }

    /// 候选下方动作：复制选中 / 继续打磨（无待澄清时）/ 换一批。
    private func actionRow(_ snap: AppState.PrecheckIntervention, _ sel: Int) -> some View {
        HStack(spacing: 8) {
            Button { copy(options[sel]); state.recordAdoption(chosenIndex: sel, action: "copy") } label: {
                HStack(spacing: 4) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc").font(.system(size: 10))
                    Text(copied ? "已复制，回 IDE 粘贴可再改" : "复制选中")
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            if state.precheckClarify == nil {
                Button("继续打磨") { state.continuePrecheckRewrite() }
                    .controlSize(.small)
                    .disabled(state.precheckRewriting)
            }
            Button("换一批") { state.requestPrecheckRewrite(prompt: snap.prompt) }
                .controlSize(.small)
                .disabled(state.precheckRewriting)
        }
    }

    private func optionCard(idx: Int, option: String) -> some View {
        let selected = selectedIndex == idx
        return Button {
            selectedRewrite = idx
            copied = false
        } label: {
            HStack(alignment: .top, spacing: 7) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 12))
                    .foregroundStyle(selected ? TUA.success : TUA.faint)
                    .padding(.top, 1)
                Text(option)
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: TUA.Radius.sm)
                .fill(selected ? TUA.success.opacity(0.10) : TUA.card))
            .overlay(RoundedRectangle(cornerRadius: TUA.Radius.sm)
                .stroke(selected ? TUA.success : TUA.border, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
    }

    private func footer(_ snap: AppState.PrecheckIntervention) -> some View {
        HStack(spacing: 8) {
            if options.isEmpty && !state.precheckRewriting {
                Button {
                    state.requestPrecheckRewrite(prompt: snap.prompt)
                } label: {
                    Text(state.precheckRewriteError == nil ? "AI 一键改写" : "重试改写")
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            Spacer()
        }
    }

    // MARK: - 行为

    private func seed(_ snap: AppState.PrecheckIntervention) {
        guard snap.ts != seededTs else { return }
        seededTs = snap.ts
        selectedRewrite = nil
        copied = false
    }

    private func copy(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        withAnimation { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { copied = false }
    }
}
