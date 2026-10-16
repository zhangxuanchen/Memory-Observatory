import AppKit
import SwiftUI

/// 异常详情弹窗：聚焦一类异常，一页一轮——规则定义 + AI 结合本轮真实交互的四段式分析 + 逐条交互明细，
/// 底部 ‹ › 在这一类的各轮之间翻页（类别切换回到首页列表点选）。
struct AnomalyDetailView: View {
    @EnvironmentObject private var state: AppState
    let group: AnomalyGroup
    /// 当前轮在本类内的序号（0-based）。
    @State private var index: Int
    let onClose: () -> Void
    /// turn_id → 该轮交互明细（懒加载）。
    @State private var eventsByTurn: [String: [EventRow]] = [:]
    /// 展开看交互明细的轮次；nil = 默认只展开每页的代表轮。
    @State private var expandedTurns: [String: Bool] = [:]
    /// 二级展开：某轮「输出 · 逐条交互」明细是否展开；nil = 默认折叠。
    @State private var expandedDetails: [String: Bool] = [:]

    init(group: AnomalyGroup, onClose: @escaping () -> Void) {
        self.group = group
        self.onClose = onClose
        _index = State(initialValue: 0)
    }

    /// 本类各轮：最严重（代表轮）排首位，其余按积分降序。
    private var orderedFindings: [FindingRow] {
        group.findings.sorted {
            if $0.isHigh != $1.isHigh { return $0.isHigh && !$1.isHigh }
            return $0.credits > $1.credits
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if orderedFindings.isEmpty {
                emptyState
            } else {
                Divider().overlay(TUA.border)
                ScrollView(.vertical, showsIndicators: true) {
                    page(group)
                        .padding(16)
                        // 底部留白：滚到底时最后一轮交互明细不被 footer 切住
                        .padding(.bottom, 28)
                }
                footer
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(TUA.card)
        // 边缘与工作区详情右扩列保持一致：不画自己的外框/描边（共用卡片底 + 左侧一条分隔线即可），
        // 也不 ignoresSafeArea，免得顶/底/左边多出一圈线。内部分隔线/页脚留给内容自己控。
        .task(id: index) { loadTurns(group) }
    }

    /// 拉当前页各轮次的交互明细（已拉过的跳过）。
    private func loadTurns(_ group: AnomalyGroup) {
        for finding in group.findings where eventsByTurn[finding.turnId] == nil {
            eventsByTurn[finding.turnId] = Database.shared.eventsForTurn(turnId: finding.turnId)
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(TUA.error)
                .frame(width: 7, height: 7)
            Text("异常详情")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TUA.ink)
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(TUA.faint)
                    .frame(width: 16, height: 16)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("关闭")
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    private var emptyState: some View {
        Text("今日没有需要关注的异常")
            .font(.system(size: 12))
            .foregroundStyle(TUA.faint)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.top, 60)
    }

    @ViewBuilder
    private func page(_ group: AnomalyGroup) -> some View {
        let finding = orderedFindings[index]
        let rep = group.representative
        let tone = group.isHigh ? TUA.error : TUA.warn
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(group.kindLabel)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(tone)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(tone.opacity(0.12)))
                Text("今天共 \(group.count) 轮")
                    .font(.system(size: 11))
                    .foregroundStyle(TUA.muted)
                Spacer()
            }

            // 每个工作区一个胶囊：各自背景高亮 + 该工作区命中轮数。
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(group.workspaceCounts, id: \.name) { ws in
                        workspaceChip(ws)
                    }
                }
                .padding(.vertical, 1)
            }

            // 判定标准：说清「反复多少次 / 超多少会被筛出来」（阈值口径）。
            Text(criteriaText(kind: rep.kind, evidence: rep.evidence))
                .font(.system(size: 11))
                .foregroundStyle(TUA.muted)
                .fixedSize(horizontal: false, vertical: true)

            Text("第 \(index + 1) / \(group.count) 轮 · 出问题的交互")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TUA.ink)

            turnSection(finding, group: group, isRepresentative: finding.id == rep.id)
        }
    }

    // MARK: 顶部：工作区高亮胶囊 + 判定标准

    /// 单个工作区胶囊：按工作区名派生稳定色做背景高亮，右侧显示命中轮数。
    private func workspaceChip(_ ws: (name: String, ide: String, count: Int)) -> some View {
        let tint = Self.workspaceTint(ws.name)
        return HStack(spacing: 5) {
            Circle().fill(tint).frame(width: 5, height: 5)
            Text(ws.name)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(TUA.ink)
                .lineLimit(1)
            Text("\(ws.count)")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(tint)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(tint.opacity(0.14)))
        .overlay(Capsule().stroke(tint.opacity(0.35), lineWidth: 1))
    }

    /// 判定标准文案：按类型说清「超 / 重复多少会被筛出来」，阈值取当前生效口径。
    private func criteriaText(kind: String, evidence: [String: Any]) -> String {
        let th = state.thresholds
        func num(_ key: String) -> Int? {
            if let i = evidence[key] as? Int { return i }
            if let d = evidence[key] as? Double { return Int(d) }
            return nil
        }
        switch kind {
        case "loop_thrash":
            return "判定：单轮内同一工具重复调用 ≥ \(th.loopRepeatN) 次即算异常（本组最高一条重复 \(num("repeat") ?? th.loopRepeatN) 次）。"
        case "context_bloat":
            return "判定：单轮内工具交互总量 ≥ \(th.turnActionBloatN) 次即算异常（本组最高一条 \(num("actions") ?? th.turnActionBloatN) 次）。"
        case "frequent_compact":
            return "判定：单轮内压缩 / 整理上下文 ≥ \(th.compactFreqN) 次即算异常（本组最高一条 \(num("compact_hits") ?? th.compactFreqN) 次）。"
        case "retry_storm":
            return "判定：同一工具连续失败 ≥ \(th.retryStormN) 次即算异常（本组最高一条连败 \(num("consecutive_failures") ?? th.retryStormN) 次）。"
        case "expire_storm":
            return "判定：\(th.expireWindowMs / 1000) 秒内短期上下文集中过期 ≥ \(th.expireBurstN) 条即算异常（本组最高一窗 \(num("burst") ?? th.expireBurstN) 条）。"
        case "credit_spike":
            return "判定：单轮积分 > \(TUA.number(th.creditAbsolute)) 且高于同 Agent P95 基线即算异常。"
        case "slow_call":
            return "判定：单步耗时 > \(th.slowCallAbsoluteMs / 1000) 秒且高于同 Agent P95 基线即算异常。"
        default:
            return "判定：命中该类型的规则阈值即计入。"
        }
    }

    /// 按工作区名派生稳定色（跨启动不变），让每个工作区有各自的高亮底色。
    private static func workspaceTint(_ name: String) -> Color {
        var h = 2166136261
        for b in name.utf8 { h = (h ^ Int(b)) &* 16777619 }
        let hue = Double(abs(h) % 360) / 360.0
        return Color(hue: hue, saturation: 0.5, brightness: 0.82)
    }

    // MARK: AI 分析区

    @ViewBuilder
    private func aiSection(_ rep: FindingRow) -> some View {
        let config = state.modelConfig
        let analyzing = state.analyzingTurnIds.contains(rep.turnId)
        let analysis = state.analyses[rep.turnId]
        let error = state.analysisErrors[rep.turnId]
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("输入")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(TUA.ink)
                Text("AI 分析")
                    .font(.system(size: 9))
                    .foregroundStyle(TUA.faint)
                if config.isReady {
                    Text("· \(config.provider.label) · \(config.model)")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                }
                Spacer()
                if analysis != nil, !analyzing {
                    Button("重新分析") {
                        if let events = eventsByTurn[rep.turnId] {
                            state.requestAnalysis(finding: rep, events: events, force: true)
                        }
                    }
                    .font(.system(size: 10))
                    .buttonStyle(.plain)
                    .foregroundStyle(TUA.muted)
                }
            }
            // 本轮真正的输入（用户当时的提问原文），让「输入」名副其实。
            if let prompt = inputPrompt(for: rep) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("本轮输入")
                        .font(.system(size: 9))
                        .foregroundStyle(TUA.faint)
                    Text(prompt)
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.card))
                .overlay(
                    RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                        .stroke(TUA.border, lineWidth: 1)
                )
            } else {
                // 诚实占位：WorkBuddy 审计流只记录工具动作，不含用户提问原文；别用空白让人误以为丢了数据。
                Text(rep.ide == "workbuddy"
                     ? "本轮输入：WorkBuddy 审计流只记录工具动作，不含用户提问原文。"
                     : "本轮输入：未采集到用户提问原文。")
                    .font(.system(size: 10))
                    .foregroundStyle(TUA.faint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !config.isReady {
                HStack(spacing: 8) {
                    Text("还没配置模型，填好 Key 后一键分析这一轮的真实交互。")
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.muted)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("去配置") { state.openModelConfig() }
                        .font(.system(size: 11))
                }
            } else if analyzing {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("正在结合本轮 \(eventsByTurn[rep.turnId]?.count ?? 0) 条交互分析…")
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.muted)
                }
            } else if let analysis {
                VStack(alignment: .leading, spacing: 8) {
                    if !analysis.what.isEmpty { aiBlock("发生了什么", analysis.what, TUA.error) }
                    if !analysis.why.isEmpty { aiBlock("为什么会出现", analysis.why, TUA.muted) }
                    if !analysis.impact.isEmpty { aiBlock("有什么影响", analysis.impact, TUA.warn) }
                    if !analysis.fix.isEmpty { aiBlock("以后怎么避免", analysis.fix, TUA.success) }
                }
            } else if let error {
                HStack(spacing: 8) {
                    Text(error)
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.error)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("重试") {
                        if let events = eventsByTurn[rep.turnId] {
                            state.requestAnalysis(finding: rep, events: events, force: true)
                        }
                    }
                    .font(.system(size: 11))
                }
            } else {
                Button("分析这一轮") {
                    if let events = eventsByTurn[rep.turnId] {
                        state.requestAnalysis(finding: rep, events: events)
                    }
                }
                .font(.system(size: 11))
            }
        }
    }

    /// 本轮的输入原文：只认 UserPromptSubmit 里「提问: …」开头的真实提问；
    /// 空输入时适配器会把 detail 回退成事件名，这种一律不显示。
    private func inputPrompt(for finding: FindingRow) -> String? {
        let events = eventsByTurn[finding.turnId] ?? []
        guard let submit = events.first(where: { $0.eventName == "UserPromptSubmit" }),
              let detail = submit.detail else { return nil }
        let prefix = "提问: "
        guard detail.hasPrefix(prefix) else { return nil }
        let trimmed = String(detail.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func aiBlock(_ label: String, _ text: String, _ color: Color) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(color)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: 4).fill(color.opacity(0.10)))
                .fixedSize()
            Text(text)
                .font(.system(size: 11))
                .foregroundStyle(TUA.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
    }

    /// 单轮：标题行（时间 · 工作区 · 积分 · 耗时 + 展开箭头）+ 展开后的逐步交互明细。
    @ViewBuilder
    private func turnSection(_ finding: FindingRow, group: AnomalyGroup, isRepresentative: Bool) -> some View {
        let key = String(finding.id)
        let open = expandedTurns[key] ?? true
        let events = eventsByTurn[finding.turnId] ?? []
        // 该轮反复出问题的工具（loop/retry 类在 evidence 里带 tool），用于高亮。
        let culprit = (finding.evidence["tool"] as? String) ?? (finding.evidence["repeat_tool"] as? String)
        VStack(alignment: .leading, spacing: 0) {
            Button {
                expandedTurns[key] = !open
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: open ? "chevron.down" : "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(TUA.faint)
                    Text(TUA.time(finding.startedAt))
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(TUA.ink)
                    Text(finding.workspaceLabel)
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.muted)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 4)
                    if isRepresentative {
                        Text("最严重")
                            .font(.system(size: 9))
                            .foregroundStyle(TUA.error)
                            .padding(.horizontal, 4).padding(.vertical, 1)
                            .background(Capsule().fill(TUA.error.opacity(0.12)))
                    }
                    if finding.credits > 0 {
                        Text(TUA.number(finding.credits) + " 积分")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(TUA.faint)
                    }
                }
                .contentShape(Rectangle())
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            .buttonStyle(.plain)

            if open {
                Divider().overlay(TUA.border)
            
                // ① 输入：AI 结合本轮交互的四段式分析
                aiSection(finding)
                    .padding(12)
            
                if events.isEmpty {
                    Divider().overlay(TUA.border)
                    Text("这一轮的交互明细未采集到（可能事件已过保留期）。")
                        .font(.system(size: 11))
                        .foregroundStyle(TUA.faint)
                        .padding(12)
                } else {
                    Divider().overlay(TUA.border)
                    // ② 交互统计：按工具聚合，重复 / 失败的工具标红
                    statsSection(events, culprit: culprit)
                        .padding(12)
                    Divider().overlay(TUA.border)
                    // ③ 输出：逐条事件明细（默认折叠，点标题二级展开）
                    let detailKey = String(finding.id)
                    let detailOpen = expandedDetails[detailKey] ?? false
                    Button {
                        expandedDetails[detailKey] = !detailOpen
                    } label: {
                        HStack(spacing: 6) {
                            Text("输出")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(TUA.ink)
                            Text("逐条交互 · \(events.count) 条")
                                .font(.system(size: 9))
                                .foregroundStyle(TUA.faint)
                            Spacer()
                            Text(detailOpen ? "收起" : "展开详情")
                                .font(.system(size: 10))
                                .foregroundStyle(TUA.muted)
                            Image(systemName: detailOpen ? "chevron.up" : "chevron.down")
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(TUA.faint)
                        }
                        .contentShape(Rectangle())
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                    if detailOpen {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(events.enumerated()), id: \.element.id) { offset, ev in
                                turnEventRow(ev, index: offset + 1, culprit: culprit)
                                if offset < events.count - 1 {
                                    Divider().overlay(TUA.border.opacity(0.5)).padding(.leading, 40)
                                }
                            }
                        }
                        .padding(.bottom, 6)
                    }
                }
            }
        }
        .task(id: open ? (eventsByTurn[finding.turnId]?.count ?? -1) : -2) {
            // 展开某轮且交互明细就位后，若已配置模型则自动分析这一轮（结果按 turn 缓存）
            guard open, state.modelConfig.isReady,
                  let events = eventsByTurn[finding.turnId] else { return }
            state.requestAnalysis(finding: finding, events: events)
        }
        .background(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous).fill(TUA.canvas))
        .overlay(
            RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous)
                .stroke(TUA.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: TUA.Radius.sm, style: .continuous))
    }

    /// 一行交互：序号 · 时间 · 工具标签 · 详情（命令/内容）… 耗时 · 积分。反复出问题的工具高亮。
    private func turnEventRow(_ row: EventRow, index: Int, culprit: String?) -> some View {
        let failed = (row.status != nil && row.status != "ok") || row.eventName == "PostToolUseFailure"
        let label = row.actionLabel
        let isCulprit = culprit != nil && label == culprit
        let detail = row.detail ?? ""
        let prefix = label + ": "
        let raw = detail.hasPrefix(prefix) ? String(detail.dropFirst(prefix.count)) : detail
        // 空输入时适配器会把 detail 回退成事件名（如 UserPromptSubmit），与老数据一样当作无真实内容。
        let hasReal = !raw.isEmpty && raw != label && raw != (row.eventName ?? "")
        // 老数据（hook 更新前）没采到内容，detail 为空；用 io_kind 兜底一句语义，行不再空着。
        let secondary = hasReal ? raw : (Self.ioKindHint(row) ?? "")
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text("\(index)")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(TUA.faint)
                    .frame(width: 18, alignment: .trailing)
                Text(TUA.time(row.ts))
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(TUA.faint)
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(failed ? TUA.error : (isCulprit ? TUA.warn : TUA.ink))
                    .fixedSize()
                    .padding(.horizontal, 4)
                    .padding(.vertical, 1)
                    .background(RoundedRectangle(cornerRadius: 3).fill(
                        (failed ? TUA.error : (isCulprit ? TUA.warn : TUA.agentColor(row.ide ?? ""))).opacity(0.10)
                    ))
                Spacer(minLength: 4)
                if let latency = row.latencyMs, latency > 0 {
                    Text(TUA.duration(latency))
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(TUA.faint)
                }
                if row.credits > 0 {
                    Text(TUA.number(row.credits))
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(TUA.faint)
                }
            }
            .padding(.horizontal, 10)
            if !secondary.isEmpty {
                Text(secondary)
                    .font(.system(size: 10))
                    .foregroundStyle(TUA.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 34)
                    .padding(.trailing, 10)
            }
        }
        .padding(.vertical, 5)
    }

    // MARK: 交互统计（中部）

    /// 按工具聚合本轮交互：次数 / 积分 / 失败数，重复或失败的工具标红。
    private func statsSection(_ events: [EventRow], culprit: String?) -> some View {
        let stats = Self.toolStats(events)
        let totalLatency = events.reduce(0) { $0 + ($1.latencyMs ?? 0) }
        let totalCredits = events.reduce(0) { $0 + $1.credits }
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("交互统计")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(TUA.ink)
                Spacer()
                Text("\(events.count) 次 · \(TUA.duration(totalLatency)) · \(TUA.number(totalCredits)) 积分")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(TUA.faint)
            }
            VStack(spacing: 5) {
                ForEach(stats) { s in
                    let bad = s.failed > 0 || (culprit != nil && s.tool == culprit)
                    HStack(spacing: 6) {
                        Circle()
                            .fill(bad ? TUA.error : TUA.faint.opacity(0.5))
                            .frame(width: 5, height: 5)
                        Text(s.tool)
                            .font(.system(size: 11, weight: bad ? .semibold : .regular))
                            .foregroundStyle(bad ? TUA.error : TUA.ink)
                        if s.tool == culprit {
                            Text("反复调用")
                                .font(.system(size: 9))
                                .foregroundStyle(TUA.error)
                                .padding(.horizontal, 4).padding(.vertical, 1)
                                .background(Capsule().fill(TUA.error.opacity(0.12)))
                        }
                        Spacer(minLength: 4)
                        if s.failed > 0 {
                            Text("\(s.failed) 次失败")
                                .font(.system(size: 9))
                                .foregroundStyle(TUA.error)
                        }
                        Text("×\(s.count)")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(TUA.ink)
                        if s.credits > 0 {
                            Text(TUA.number(s.credits))
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(TUA.faint)
                        }
                    }
                }
            }
        }
    }

    /// 按工具名聚合交互，次数多者优先；同次数按积分降序。
    private static func toolStats(_ events: [EventRow]) -> [ToolStat] {
        var map: [String: ToolStat] = [:]
        for ev in events {
            let tool = ev.actionLabel
            var s = map[tool] ?? ToolStat(tool: tool, count: 0, credits: 0, failed: 0)
            s.count += 1
            s.credits += ev.credits
            if (ev.status != nil && ev.status != "ok") || ev.eventName == "PostToolUseFailure" {
                s.failed += 1
            }
            map[tool] = s
        }
        return map.values.sorted {
            $0.count != $1.count ? $0.count > $1.count : $0.credits > $1.credits
        }
    }

    /// 采不到内容时，根据 io_kind 给一句人读得到的语义（老数据兼容）。
    private static func ioKindHint(_ row: EventRow) -> String? {
        switch row.ioKind {
        case "file_read":     return "读取文件"
        case "file_write":    return "写入 / 修改文件"
        case "content_search": return "搜索代码 / 文件"
        case "memory_read":   return "上下文 / 记忆操作"
        case "thinking":      return "模型思考"
        case "tool_other":    return "执行工具调用"
        default:              return nil
        }
    }

    private var footer: some View {
        let n = max(group.count, 1)
        return HStack {
            Button {
                if index > 0 { index -= 1 }
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left").font(.system(size: 10, weight: .semibold))
                    Text("上一轮").font(.system(size: 11))
                }
                .foregroundStyle(index > 0 ? TUA.ink : TUA.faint.opacity(0.5))
            }
            .buttonStyle(.plain)
            .disabled(index <= 0)

            Spacer()
            Text("\(index + 1) / \(n)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(TUA.faint)
            Spacer()

            Button {
                if index < n - 1 { index += 1 }
            } label: {
                HStack(spacing: 2) {
                    Text("下一轮").font(.system(size: 11))
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(index < n - 1 ? TUA.ink : TUA.faint.opacity(0.5))
            }
            .buttonStyle(.plain)
            .disabled(index >= n - 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .overlay(TopBorder())
    }
}

/// 单轮内某个工具的交互聚合。
private struct ToolStat: Identifiable {
    let tool: String
    var count: Int
    var credits: Double
    var failed: Int
    var id: String { tool }
}

/// footer 顶部一条细分隔线。
private struct TopBorder: View {
    var body: some View {
        VStack {
            TUA.border.frame(height: 1)
            Spacer(minLength: 0)
        }
    }
}

/// 异常详情弹窗控制器：按需创建的独立浮层窗口，与悬浮窗同款管理。
final class AnomalyDetailController {
    private var panel: NSPanel?

    func show(group: AnomalyGroup) {
        let hosting = NSHostingView(
            rootView: AnomalyDetailView(group: group) { [weak self] in
                self?.close()
            }
            .environmentObject(AppState.shared)
        )
        if let panel {
            panel.contentView = hosting
            panel.orderFrontRegardless()
            return
        }
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 540),
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
        // 只留头部自绘的 ✕，隐藏系统红绿灯，与悬浮窗风格一致。
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true
        panel.contentView = hosting
        center(panel)
        panel.orderFrontRegardless()
        self.panel = panel
    }

    func close() {
        panel?.orderOut(nil)
    }

    private func center(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        let origin = NSPoint(
            x: visible.midX - size.width / 2,
            y: visible.midY - size.height / 2
        )
        panel.setFrameOrigin(origin)
    }
}
