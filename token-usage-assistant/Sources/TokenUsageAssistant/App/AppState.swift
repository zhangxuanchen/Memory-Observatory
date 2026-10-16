import Foundation

/// 工作区视角（悬浮窗「工作区快捷切换」）。设置后各方块只呈现该工作区。
struct WorkspaceFocus: Equatable {
    let id: String
    let name: String
}

/// 按 Agent 分组的今日工作区排行（D14：各自单位，绝不合计）。
struct AgentWorkspaceGroup: Identifiable {
    let ide: String
    var items: [WorkspaceStats]
    var id: String { ide }
}

/// 全局状态：持有接收端、数据库句柄与界面快照。所有界面可见字段在主线程更新。
final class AppState: ObservableObject {
    static let shared = AppState()

    @Published private(set) var config: AppConfig
    @Published private(set) var receiverRunning = false
    @Published private(set) var receiverMessage = "未启动"
    @Published private(set) var today = TodayStats()
    @Published private(set) var recent: [EventRow] = []

    // 悬浮窗实时态（M3）
    @Published private(set) var trend: [AgentTrend] = []
    @Published private(set) var anomalies: [Anomaly] = []
    @Published private(set) var workspaceGroups: [AgentWorkspaceGroup] = []
    @Published private(set) var focus: WorkspaceFocus?

    /// 当前选中的来源（单选，恒有值）：整窗只呈现该 Agent（D14：绝不跨 Agent 合计）。
    @Published private(set) var sourceFilter: String
    /// 可监控的全部来源（含尚未接入的），下拉据此列出。
    let knownSources: [String] = ["qoder", "workbuddy"]
    /// 近 7 天内有数据的来源（下拉里标注用）。
    @Published private(set) var availableSources: [String] = []
    /// 选中来源近 7 天是否有数据：false → 面板中部显示「一键接入」。
    @Published private(set) var selectedSourceHasData = false
    /// 面板当前是否「今日」口径（选中来源今天用过 = 今日，否则近 7 天）。
    @Published private(set) var panelShowsToday = true
    /// 接入进行中标记 + 失败/提示文案（显示在「一键接入」按钮下方）。
    @Published private(set) var connectingSource = false
    @Published private(set) var connectMessage: String? = nil
    /// 浮窗工作区详情弹层：非空 = 从右侧滑出该工作区详情（turn 统计 / 记忆 / Skill / 本区事件）；nil = 收起。
    @Published var selectedWorkspaceRowId: String?

    /// 点行：选中即滑出详情弹层；再点同一行收起。
    func selectWorkspace(_ item: WorkspaceStats) {
        selectedWorkspaceRowId = selectedWorkspaceRowId == item.id ? nil : item.id
    }

    func closeWorkspaceDetail() {
        selectedWorkspaceRowId = nil
    }
    /// WorkBuddy 大刷新进行中标记 + 进度文案（切到 WorkBuddy 全量重拉时盖进度框）。
    @Published private(set) var workBuddyImporting = false
    @Published private(set) var workBuddyImportPhase: String = ""

    /// 最近一次护航干预（ask/deny）：悬浮窗护航卡（M14）与菜单栏角标的数据源。
    @Published private(set) var lastGuardIntervention: GuardIntervention?
    /// 最近一次事前拦截（M14）：护航卡展示待改写原文 + 问题标注 + 损失估计。
    @Published private(set) var lastPrecheck: PrecheckIntervention?
    /// 护航卡「AI 一键改写」候选（多条，让用户挑而非自己写）；仅点击时才调 LLM。
    @Published private(set) var precheckRewrites: [String] = []
    @Published private(set) var precheckRewriting = false
    @Published private(set) var precheckRewriteError: String?
    /// 本次拦截卡是否已记过一次「采纳」（复制）——防关卡时重复记 reject。
    private var precheckAdopted = false
    /// 内置小 Agent 改写状态：已用轮次 / 累计 token 用量 / 待用户选的澄清分叉 / 复杂度与定稿标记。
    @Published private(set) var precheckRounds = 0
    @Published private(set) var precheckTokens = 0
    @Published private(set) var precheckClarify: AgentClarify?
    @Published private(set) var precheckComplex = false
    @Published private(set) var precheckRefined = false
    /// 当前改写会话的小 Agent（持有逐轮历史）；nil=未开始。
    private var promptAgent: PromptAgent?
    /// 规则复杂度预判（双门第一道），驱动是否自动多轮。
    private var precheckRuleComplex = false
    /// 会话级静噪（M13 evaluateGuard 仍复用）：内存态，重启即清。
    private var mutedGuardSessions: Set<String> = []
    /// 「本轮不再护航」一次性放行：置位后下一次 precheck 直接跳过校验放行（消费一次即失效），
    /// 下一轮恢复原逻辑。不再按 prompt 原文精确匹配（重提文本有差异时会漏放）。内存态，重启即清。
    private var skipNextPrecheckOnce = false

    /// 一次护航干预的展示快照。
    struct GuardIntervention {
        let ts: Int64
        let sessionId: String
        let turnId: String
        let rule: String
        let decision: String
        let reason: String
    }

    /// 一次事前拦截的展示快照（护航卡用）。
    struct PrecheckIntervention {
        let ts: Int64
        let sessionId: String?
        let ide: String?
        let workspaceId: String?   // 由拦下时的 cwd 派生；重启恢复路径从语料表回查，可能 nil
        let prompt: String
        let score: Double
        let problems: [String]
        let estimatedLoss: Double?   // 该工作区单轮积分 P95，nil=样本不足
        let secretHits: [String]     // 非空 = 敏感信息拦截（只提示命中类型，不走 AI 改写，防密钥外发）
    }

    // 主面板聚合与异常（M4 / M5）
    @Published private(set) var findings: [FindingRow] = []
    @Published private(set) var agentLatency: [AgentLatencyStats] = []
    @Published private(set) var sessions: [SessionSummary] = []
    @Published private(set) var detecting = false

    // 接入（M2）
    @Published private(set) var installedAgents: [String: Bool] = [:]
    @Published private(set) var busyAgent: String?
    @Published private(set) var installLog: [String] = []
    @Published private(set) var probeLog: [String] = []

    /// 悬浮窗根据面板高度算出的最近事件条数（至少 5，拉高多显）。
    @Published var recentLimitCache: Int = 5

    let database = Database.shared
    let floatPanel = FloatPanelController()
    /// 宣传视频录制的演示场景（env `TUA_DEMO_SCENE` 门控；未设置时完全不影响正常使用）。
    /// 取值 overview / anomalies / detail_loop / detail_bloat / detail_workspace / precheck：启动即自动展开浮窗并摆到对应画面，
    /// 供命令行 `screencapture -v` 无人值守录屏（详情 AI 四段读 `analyses` 缓存，不现场调模型）。
    let demoScene: String? = ProcessInfo.processInfo.environment["TUA_DEMO_SCENE"]
    let mainPanel = MainPanelController()
    let anomalyDetail = AnomalyDetailController()
    let modelConfigPanel = ModelConfigController()

    private let receiver: LocalReceiver
    private let installer = HookInstaller()
    private let detectionQueue = DispatchQueue(label: "tua.detection", qos: .utility)
    private var pendingDetection: DispatchWorkItem?
    private let importQueue = DispatchQueue(label: "tua.workbuddy", qos: .utility)
    private var workBuddyTimer: Timer?
    private static let sourceFilterKey = "tua.sourceFilter"
    private static let workBuddyFullKey = "tua.workbuddy.fullImported"

    private init() {
        let config = AppConfig.load()
        self.config = config
        self.receiver = LocalReceiver(port: config.port, apiKey: config.apiKey)
        self.sourceFilter = AppState.resolveInitialSource(
            known: ["qoder", "workbuddy"], db: Database.shared
        )

        self.receiver.onIngest = { events in AppState.shared.ingest(events) }
        self.receiver.onGuard = { request in AppState.shared.evaluateGuard(request) }
        self.receiver.onPrecheck = { request in AppState.shared.evaluatePrecheck(request) }
        refreshInstalled()
        analyses = Database.shared.loadAnalyses()   // 重启后复用已存分析，不重复调模型
        if config.guardEnabled || config.secretGuardEnabled, let last = Database.shared.latestPrecheckBlock() {
            // 重启后从 guard_log 恢复待改写原文（护航卡不因重启丢）。
            // 仅在任一闸开着时恢复：两闸全关后不应再有任何拦截卡（否则会把关闸前遗留的旧卡复活）。
            self.lastPrecheck = AppState.precheckIntervention(from: last)
        }
        loadSnapshots()
        runDetection(days: 7)
        handleSourceChange(sourceFilter)
        reconcileHooksIfNeeded()
        if let scene = demoScene {
            // 演示模式：非 precheck 场景先关掉历史拦截卡（避免乱入镜头）；precheck 场景保留它当主角。
            if scene != "precheck" { dismissPrecheck() }
            // 稍等布局/数据就绪后自动开面板，FloatPanelView.onAppear 依 demoScene 摆到指定画面。
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                self?.showFloatPanel()
                // 再等一帧让 onAppear 摆好状态，然后把面板钉到固定矩形（区域坐标写入 /tmp/tua_demo_rect.txt）。ball 场景不钉（要看圆盘自然尺寸）。
                if scene != "ball" {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self?.floatPanel.pinForDemo()
                    }
                }
            }
        }
    }

    /// 启动自愈：对已接入的 Agent 静默重装一次——刷新 wrapper（现转发 `--guard`）
    /// 与 env（现含 `TUA_GUARD_ENDPOINT`），并按当前护航开关同步 PreToolUse。
    /// 使「升级 App 后重启一次 IDE」即可生效，无需手动断开/重接。写盘幂等。
    private func reconcileHooksIfNeeded() {
        let targets = Ide.allCases.filter { isInstalled($0) }
        guard !targets.isEmpty else { return }
        let cfg = config
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self else { return }
            for ide in targets { _ = self.installer.install(ide: ide, config: cfg) }
            DispatchQueue.main.async { self.refreshInstalled() }
        }
    }

    /// 统一入库通道：HTTP 接收与 WorkBuddy 本地导入共用。
    /// 先切分轮次（App 侧统一口径），再入库，最后按轮汇总（幂等）。
    func ingest(_ events: [TUAEvent]) -> IngestResult {
        let prepared = TurnMaterializer.shared.assign(events)
        let result = Database.shared.insert(events: prepared)
        TurnMaterializer.shared.refreshTurns(prepared)
        DispatchQueue.main.async {
            AppState.shared.loadSnapshots()
            AppState.shared.scheduleDetection()
        }
        return result
    }

    // MARK: - 护航编排（M13 L4）

    /// 护航同步查询（接收端网络队列上调，预算 <5ms）：读 DB 拿进行中 turn 的动作事实流，
    /// 跑 `m6.r1` 规则；ask/deny 落 guard_log 留痕。总闸关 / 无 session / 无进行中轮 → allow。
    func evaluateGuard(_ request: GuardRequest) -> GuardOutcome {
        guard config.guardEnabled, let sessionId = request.sessionId, !sessionId.isEmpty else {
            return .allow
        }
        if mutedGuardSessions.contains(sessionId) { return .allow }
        guard let turnId = database.currentTurnOfSession(sessionId: sessionId) else { return .allow }
        let facts = database.eventsByTurns(turnIds: [turnId])[turnId] ?? []
        let thresholds = config.effectiveGuardThresholds
        let outcome = InFlightGuard.evaluate(facts: facts, thresholds: thresholds) { rule in
            database.guardAlreadyRaised(turnId: turnId, rule: rule)
        }
        if outcome.decision != "allow", let rule = outcome.rule {
            database.insertGuardLog(
                sessionId: sessionId, turnId: turnId,
                ide: String(turnId.split(separator: ":").first ?? "qoder"),
                toolName: request.toolName, rule: rule, decision: outcome.decision,
                reason: outcome.reason ?? "", evidence: outcome.evidence)
            let snapshot = GuardIntervention(
                ts: TUAEvent.nowMillis(), sessionId: sessionId, turnId: turnId,
                rule: rule, decision: outcome.decision, reason: outcome.reason ?? "")
            DispatchQueue.main.async {
                self.lastGuardIntervention = snapshot
                self.loadSnapshots()
            }
        }
        return outcome
    }

    /// 会话级静噪：该会话后续不再护航（M14「本轮不再护航」按钮的后台支撑）。
    func muteGuardSession(_ sessionId: String) {
        mutedGuardSessions.insert(sessionId)
    }

    // MARK: - 事前护航编排（M14 T1）

    func evaluatePrecheck(_ request: PrecheckRequest) -> PrecheckOutcome {
        let outcome = precheckDecision(request)
        // 同会话重提通过 = 护航目的已达成（改写补全 / 已移除凭据）→ 自动关掉护航卡。
        if outcome.decision == "pass", let sid = request.sessionId {
            DispatchQueue.main.async {
                guard self.lastPrecheck?.sessionId == sid else { return }
                self.lastPrecheck = nil
                self.resetPrecheckAgent()
                NSLog("TUA: 事前护航·同会话重提通过 → 自动关护航卡 \(sid.prefix(8))")
            }
        }
        return outcome
    }

    /// UserPromptSubmit 事前评估（接收端网络队列调）：两道零 LLM 检查——
    /// ① 敏感信息护航（独立闸）：疑似密钥先拦，防密钥随 prompt 外发；
    /// ② 表述清晰度拦（「把关」闸）：规则打分低于阈值即 block，surface 到护航卡可改写。
    /// 两闸全关 / 空 prompt / 会话已静噪（仅清晰度）→ pass。适配器据此 stderr+exit2 拦下重提。
    private func precheckDecision(_ request: PrecheckRequest) -> PrecheckOutcome {
        let coachOn = config.guardEnabled
        let secretOn = config.secretGuardEnabled
        guard coachOn || secretOn else { return .pass }
        let prompt = (request.prompt ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return .pass }   // 空/纯图提交跳过（无原文可评）
        // 「本轮不再护航」：本轮跳过护航校验，命中即放行并消费一次，下一轮恢复。
        if skipNextPrecheckOnce {
            skipNextPrecheckOnce = false
            return .pass
        }

        // ① 敏感信息拦截：优先于清晰度拦（密钥外发比空跑严重），也不受会话静噪影响。
        if secretOn {
            let hits = SecretRedactor.detect(prompt)
            if !hits.isEmpty {
                let reason = "TUA 护航：这条提问疑似含凭据（\(hits.joined(separator: "、"))），已拦下防密钥随 prompt 外发。请移除敏感内容后重提；确需发送可临时关掉「敏感信息护航」。"
                database.insertGuardLog(
                    sessionId: request.sessionId, turnId: nil, ide: request.ide, toolName: nil,
                    rule: "secret_leak", decision: "block", reason: reason,
                    evidence: ["secret_hits": hits],
                    stage: "precheck", prompt: prompt)   // 入库时 guard_log 自身再过 redact
                let snapshot = PrecheckIntervention(
                    ts: TUAEvent.nowMillis(), sessionId: request.sessionId, ide: request.ide,
                    workspaceId: request.cwd.map { Workspace.deriveId(root: $0) },
                    prompt: prompt, score: 0, problems: hits, estimatedLoss: nil, secretHits: hits)
                DispatchQueue.main.async { self.lastPrecheck = snapshot; self.resetPrecheckAgent(); self.loadSnapshots() }
                return PrecheckOutcome(decision: "block", reason: reason, score: nil, problems: hits)
            }
        }

        // ② 表述清晰度拦：仅「把关」闸开着才跑（关掉把关闸后只剩密钥检测）。
        guard coachOn else { return .pass }
        if let sid = request.sessionId, mutedGuardSessions.contains(sid) { return .pass }

        let thresholds = config.effectiveGuardThresholds
        let report = PromptReadiness.evaluate(prompt)
        guard report.isBelow(thresholds.promptReadinessBlockBelow) else { return .pass }

        let loss = estimatedLoss(cwd: request.cwd)
        let reason = precheckReason(report: report, loss: loss)
        let evidence: [String: Any] = [
            "clarity": report.clarity, "completeness": report.completeness,
            "solvability": report.solvability, "problems": report.problems,
            "threshold": thresholds.promptReadinessBlockBelow,
        ]
        database.insertGuardLog(
            sessionId: request.sessionId, turnId: nil, ide: request.ide, toolName: nil,
            rule: "prompt_readiness", decision: "block", reason: reason, evidence: evidence,
            stage: "precheck", prompt: prompt)
        let snapshot = PrecheckIntervention(
            ts: TUAEvent.nowMillis(), sessionId: request.sessionId, ide: request.ide,
            workspaceId: request.cwd.map { Workspace.deriveId(root: $0) },
            prompt: prompt, score: report.score, problems: report.problems, estimatedLoss: loss,
            secretHits: [])
        DispatchQueue.main.async { self.lastPrecheck = snapshot; self.resetPrecheckAgent(); self.loadSnapshots() }
        return PrecheckOutcome(decision: "block", reason: reason, score: report.score, problems: report.problems)
    }

    /// 损失估计：该工作区近 7 天已结束轮次积分 P95（D14 隔离）；样本 <5 不给数（诚实边界）。
    private func estimatedLoss(cwd: String?) -> Double? {
        guard let cwd, !cwd.isEmpty else { return nil }
        let wsId = Workspace.deriveId(root: cwd)
        let since = TUAEvent.nowMillis() - 7 * 86_400_000
        let credits = database.endedTurnCredits(workspaceId: wsId, since: since).filter { $0 > 0 }
        guard credits.count >= 5 else { return nil }
        let p95 = Percentiles.value(credits, 0.95)
        return p95 > 0 ? p95 : nil
    }

    private func precheckReason(report: ReadinessReport, loss: Double?) -> String {
        var s = "TUA 拦下了这条提问：说得不太清楚（\(Int(report.score))/15 分，低于 8 分会拦），AI 容易理解偏或白跑。"
        if let first = report.problems.first { s += "（\(first)）" }
        if let loss { s += " 该工作区单轮常烧约 \(String(format: "%.1f", loss)) 积分。" }
        s += " 打开 TUA 浮窗可一键改写，挑一条顺的再发。"
        return s
    }

    /// 从 guard_log 读回行还原护航卡快照（重启后）。
    private static func precheckIntervention(from log: PrecheckLog) -> PrecheckIntervention {
        var problems: [String] = []
        var secretHits: [String] = []
        var score = 0.0
        if let data = log.evidenceJSON.data(using: .utf8),
           let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            problems = obj["problems"] as? [String] ?? []
            secretHits = obj["secret_hits"] as? [String] ?? []
            score = (obj["clarity"] as? Double ?? 0)
                + (obj["completeness"] as? Double ?? 0)
                + (obj["solvability"] as? Double ?? 0)
        }
        return PrecheckIntervention(ts: log.ts, sessionId: log.sessionId, ide: log.ide,
                                    workspaceId: Database.shared.corpusWorkspaceId(sessionId: log.sessionId),
                                    prompt: log.prompt, score: score, problems: problems, estimatedLoss: nil,
                                    secretHits: secretHits)
    }

    /// 护航卡消费：清除当前待改写卡（可选静噪该会话）。
    func dismissPrecheck(muteSession: Bool = false) {
        // L3 真值：关卡但从未复制（且已出过候选）= 全部否定（reject-all）。
        if !precheckAdopted && !precheckRewrites.isEmpty {
            recordAdoption(chosenIndex: nil, action: "reject")
        }
        if muteSession, let sid = lastPrecheck?.sessionId { muteGuardSession(sid) }
        resetPrecheckAgent()
        DispatchQueue.main.async { self.lastPrecheck = nil }
    }

    /// L3 采纳埋点（测评体系 Phase 0）：记下拦截卡上「展示→选中/全否」的行为。
    /// 纯被动、对用户不可见——是“人到底会选哪条”的真值水龙头。仅在已出过候选时记。
    func recordAdoption(chosenIndex: Int?, action: String) {
        guard let snap = lastPrecheck, !precheckRewrites.isEmpty else { return }
        if action == "copy" { precheckAdopted = true }
        database.insertAdoptionEvent(
            target: TargetID.promptPrecheck,
            ide: snap.ide, sessionId: snap.sessionId, workspaceId: nil,
            originalPrompt: snap.prompt, shown: precheckRewrites,
            chosenIndex: chosenIndex, action: action)
    }

    /// 设置页总闸：开关护航。写盘后对已接入的 Agent 幂等重装 hook——
    /// 开→新增 PreToolUse 同步探针（并刷新 wrapper 转发 `--guard` + env guard 端点）；
    /// 关→移除护航命令。需重启 IDE 才彻底生效。
    func setGuardEnabled(_ on: Bool) {
        let wasOn = config.guardEnabled
        config.guardOn = on
        if !on { config.guardBreaker = false }
        config.save()
        reinstallHooksForGuard()
        // 从「把关」切到「关」且当前正显示被拦的提问：把原文送回 IDE 输入框（只 ⌘V、不提交），
        // 并关掉事前护航卡；随后由用户自己点提交。密钥拦截的卡不回填——防把凭据又塞回输入框。
        if wasOn && !on, let snap = lastPrecheck, snap.secretHits.isEmpty {
            let p = snap.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
            resetPrecheckAgent()
            DispatchQueue.main.async { self.lastPrecheck = nil }
            PromptAutoSend.restoreToInput(prompt: p, ide: snap.ide)
        }
    }

    /// 敏感信息护航独立闸：与「指导」总闸平行。写盘后幂等重装 hook——
    /// 两闸任一开着就保留 UserPromptSubmit --precheck 探针，全关则摘掉。
    /// 关掉时若正显示密钥拦截卡：直接清关（不回填原文，凭据不该回输入框）。
    func setSecretGuardEnabled(_ on: Bool) {
        config.secretGuardOn = on
        config.save()
        reinstallHooksForGuard()
        if !on, lastPrecheck?.secretHits.isEmpty == false {
            resetPrecheckAgent()
            DispatchQueue.main.async { self.lastPrecheck = nil }
        }
    }

    /// 重置改写小 Agent 会话态（新拦截 / 关闭卡时）。
    private func resetPrecheckAgent() {
        promptAgent = nil
        precheckRewrites = []
        precheckRewriting = false
        precheckRewriteError = nil
        precheckAdopted = false
        precheckRounds = 0
        precheckTokens = 0
        precheckClarify = nil
        precheckComplex = false
        precheckRefined = false
        precheckRuleComplex = false
    }

    /// 护航卡「AI 一键改写」：启动内置小 Agent 第 1 轮（仅此时才调 LLM，成本纪律 §22）。
    /// 密钥拦截卡不走这里——防把含凭据原文发给外部模型，那就是要拦的事本身。
    func requestPrecheckRewrite(prompt: String) {
        guard let snap = lastPrecheck, snap.secretHits.isEmpty, !precheckRewriting else { return }
        resetPrecheckAgent()
        precheckRewriting = true
        precheckRuleComplex = PromptComplexity.assess(prompt)
        let context = precheckContextString(sessionId: snap.sessionId, workspaceId: snap.workspaceId, currentPrompt: prompt)
        let agent = PromptAgent(prompt: prompt, problems: snap.problems, score: snap.score, ruleComplex: precheckRuleComplex, context: context)
        promptAgent = agent
        agent.begin(config: modelConfig) { [weak self] result in self?.handleRewriteResult(result) }
    }

    /// 拼改写的情景上下文，两段按需拼接：
    /// ①【当前会话情景】同会话最近几轮提问（时间正序）；
    /// ②【相似历史提问】语料表粗排命中（M9 消费端，§6：命中不足就不塞，宁缺毋滥）。
    /// 两段都没内容 → nil（不掺空上下文）。
    private func precheckContextString(sessionId: String?, workspaceId: String?, currentPrompt: String) -> String? {
        let cur = currentPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        var shown: [String] = [cur]
        var parts: [String] = []

        // ① 当前会话情景（原有能力，无 sessionId / 无历史则跳过）
        if let sessionId, !sessionId.isEmpty {
            let rows = database.recentSessionPrompts(sessionId: sessionId, limit: 6)
            let history = rows.compactMap { row -> String? in
                let p = row.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !p.isEmpty, !shown.contains(p) else { return nil }
                shown.append(p)
                return p
            }
            if !history.isEmpty {
                let workspace = rows.lazy.map { $0.workspace }.first { !$0.isEmpty }
                var s = "【当前会话情景】"
                if let workspace { s += "工作区：\(workspace)。" }
                s += "同一会话你之前几轮的提问（时间正序，供你推断用户真正在改的对象/目标，别照抄、别臆造）：\n"
                for (i, p) in history.suffix(5).enumerated() {
                    s += "\(i + 1). \(Self.contextOneLiner(p))\n"
                }
                parts.append(s)
            }
        }

        // ② 相似历史提问（M9 语料消费）：跨会话同类写法，只供参考不供照抄
        let sims = database.similarPrompts(text: cur, workspaceId: workspaceId, limit: 3)
            .map { $0.prompt.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !shown.contains($0) }
        if !sims.isEmpty {
            var s = "【相似历史提问】库里与你这条提问同类（表层用词/结构相似）的历史提问，参考其表达方式把候选改得更可复制，别照抄内容：\n"
            for (i, p) in sims.enumerated() {
                s += "\(i + 1). \(Self.contextOneLiner(p))\n"
            }
            parts.append(s)
        }

        guard !parts.isEmpty else { return nil }
        return parts.joined()
    }

    /// 情景上下文里的单条提问：压成一行、限长 120 字，不抖行结构。
    private static func contextOneLiner(_ p: String) -> String {
        let one = p.replacingOccurrences(of: "\n", with: " ")
        return one.count > 120 ? String(one.prefix(120)) + "…" : one
    }

    /// 手动「继续打磨」：越过自动封顶，由用户显式推进一轮。
    func continuePrecheckRewrite() {
        guard let agent = promptAgent, !precheckRewriting else { return }
        precheckRewriting = true
        precheckRewriteError = nil
        agent.refine(config: modelConfig) { [weak self] result in self?.handleRewriteResult(result) }
    }

    /// 用户点了澄清方向→小 Agent 据此细化。
    func answerPrecheckClarify(_ option: String) {
        guard let agent = promptAgent, !precheckRewriting else { return }
        precheckClarify = nil
        precheckRewriting = true
        precheckRewriteError = nil
        agent.answer(option: option, config: modelConfig) { [weak self] result in self?.handleRewriteResult(result) }
    }

    private func handleRewriteResult(_ result: Result<AgentTurn, Error>) {
        DispatchQueue.main.async {
            switch result {
            case .success(let turn): self.applyTurn(turn)
            case .failure(let error):
                self.precheckRewriting = false
                self.precheckRewriteError = error.localizedDescription
            }
        }
    }

    /// 应用一轮产出；两者结合的核心：双门（规则∧模型）判复杂、未定稿、无待澄清、未到自动封顶 → 自动再打磨一轮。
    private func applyTurn(_ turn: AgentTurn) {
        precheckRewrites = turn.candidates
        precheckComplex = turn.complex
        precheckRefined = turn.refined
        precheckClarify = turn.clarify
        precheckRounds = promptAgent?.rounds ?? precheckRounds
        precheckTokens += turn.totalTokens ?? 0
        let goDeep = precheckRuleComplex && turn.complex
        if goDeep, !turn.refined, turn.clarify == nil, precheckRounds < PromptAgent.maxRounds {
            precheckRewriting = true
            promptAgent?.refine(config: modelConfig) { [weak self] r in self?.handleRewriteResult(r) }
        } else {
            precheckRewriting = false
        }
    }

    /// 熔断开关：只影响 App 侧判定（deny 是否放行），不改 hook 拓扑，无需重装。
    func setGuardBreaker(_ on: Bool) {
        config.guardBreaker = on
        config.save()
    }

    /// 护航拓扑变更后重装 hook（仅对已接入的 Agent）；异步、不阻 UI。
    private func reinstallHooksForGuard() {
        let targets = Ide.allCases.filter { isInstalled($0) }
        guard !targets.isEmpty else {
            installLog = ["护航开关已改（尚未接入 IDE，接入时自动按新开关装 hook）"]
            return
        }
        for ide in targets { install(ide) }
    }

    // MARK: - WorkBuddy 本地导入（视图驱动）

    /// 来源切换副作用：切到 WorkBuddy → 大刷新 + 每 10s 轮询；切走 → 停轮询。
    private func handleSourceChange(_ ide: String) {
        workBuddyTimer?.invalidate()
        workBuddyTimer = nil
        if ide == "workbuddy" { startWorkBuddyPolling() }
    }

    /// 进入 WorkBuddy：先全量大刷新（带进度框），随后每 10s 增量刷新，直到切走。
    private func startWorkBuddyPolling() {
        guard config.workbuddyImportEnabled, WorkBuddyImporter.shared.isAvailable else { return }
        refreshWorkBuddy(full: true)
        let timer = Timer(timeInterval: 10, repeats: true) { [weak self] _ in self?.refreshWorkBuddy(full: false) }
        RunLoop.main.add(timer, forMode: .common)
        workBuddyTimer = timer
    }

    /// 采集 WorkBuddy 数据并走统一入库通道（未装 / 无数据即静默跳过）。
    /// full=true 拉全部历史（永久留存、切进来时的「大刷新」）并盖进度框；
    /// full=false 按窗口增量。事件入库后不删，靠 event_uid 幂等去重。
    func refreshWorkBuddy(full: Bool = false) {
        guard config.workbuddyImportEnabled, WorkBuddyImporter.shared.isAvailable else { return }
        let useFull = full || !UserDefaults.standard.bool(forKey: Self.workBuddyFullKey)
        if useFull {
            guard !workBuddyImporting else { return }   // 防重入：全量未完时不叠第二个
            workBuddyImporting = true
            workBuddyImportPhase = "正在全量刷新 WorkBuddy…"
        }
        let days = useFull ? 36_500 : config.workbuddyImportWindow
        importQueue.async { [weak self] in
            let events = WorkBuddyImporter.shared.collect(days: days)
            if useFull { UserDefaults.standard.set(true, forKey: Self.workBuddyFullKey) }
            if !events.isEmpty { _ = self?.ingest(events) }
            if useFull {
                DispatchQueue.main.async {
                    self?.workBuddyImporting = false
                    self?.loadSnapshots()
                }
            }
        }
    }

    /// 切换来源（单选，恒有值）；持久化选择并清工作区视角，避免二者语义打架。
    func setSourceFilter(_ ide: String) {
        guard ide != sourceFilter else { return }
        sourceFilter = ide
        focus = nil
        connectMessage = nil
        UserDefaults.standard.set(ide, forKey: Self.sourceFilterKey)
        loadSnapshots()
        handleSourceChange(ide)
    }

    /// 来源展示名（下拉与标题用）。
    static func sourceDisplayName(_ src: String) -> String {
        switch src {
        case "qoder": return "Qoder"
        case "workbuddy": return "WorkBuddy"
        default: return src
        }
    }

    /// 初始来源：上次选择 → 近 7 天有数据的第一个 → qoder。
    private static func resolveInitialSource(known: [String], db: Database) -> String {
        if let saved = UserDefaults.standard.string(forKey: sourceFilterKey), known.contains(saved) {
            return saved
        }
        let withData = Set(db.distinctIde(since: Database.startOfTodayMillis() - 6 * 86_400_000))
        return known.first { withData.contains($0) } ?? known.first ?? "qoder"
    }

    /// 选中来源是否已接入 / 本机可采：Qoder 看 hook，WorkBuddy 看本机数据目录。
    var selectedSourceInstalled: Bool {
        switch sourceFilter {
        case "qoder": return isInstalled(.qoder)
        case "workbuddy": return WorkBuddyImporter.shared.isAvailable
        default: return false
        }
    }

    /// 一键接入当前来源：Qoder 装 hook；WorkBuddy 触发本地全量导入。接不了就地报错。
    func connectSelectedSource() {
        connectMessage = nil
        connectingSource = true
        switch sourceFilter {
        case "qoder":
            install(.qoder)
        case "workbuddy":
            guard WorkBuddyImporter.shared.isAvailable else {
                connectMessage = "未在本机检测到 WorkBuddy（~/.workbuddy 不存在）"
                connectingSource = false
                return
            }
            UserDefaults.standard.set(false, forKey: Self.workBuddyFullKey)
            refreshWorkBuddy(full: true)
            connectingSource = false
        default:
            connectMessage = "暂不支持接入该来源"
            connectingSource = false
        }
    }

    // MARK: - 接收端

    func startReceiver() {
        do {
            try receiver.start()
            receiverRunning = true
            receiverMessage = "监听 127.0.0.1:\(config.port)"
        } catch {
            receiverRunning = false
            receiverMessage = "启动失败：\(error.localizedDescription)"
        }
    }

    // MARK: - 快照

    /// 主线程刷新界面快照。设了工作区视角时，用量 / 异常均只统计该工作区。
    func loadSnapshots(recentLimit: Int? = nil) {
        let start = Database.startOfTodayMillis()
        let sevenDaysAgo = start - 6 * 86_400_000
        let src = sourceFilter
        let sourcesToday = Set(database.distinctIde(since: start))
        let sources7d = database.distinctIde(since: sevenDaysAgo)
        availableSources = sources7d
        selectedSourceHasData = sources7d.contains(src)
        panelShowsToday = sourcesToday.contains(src)
        // 选中来源今天用过 → 今日口径；否则深挖近 7 天（否则只用过几天的来源永远看不到）。
        let window = panelShowsToday ? start : sevenDaysAgo
        let workspaceId = focus?.id
        today = database.todayStats(workspaceId: workspaceId)
        if let recentLimit { recentLimitCache = max(5, min(40, recentLimit)) }
        recent = database.recentEvents(limit: recentLimitCache, ide: src)
        trend = database.hourlyTrend(since: window, workspaceId: workspaceId)
        anomalies = AnomalyDetector.detect(
            turns: database.todayTurns(since: window, workspaceId: workspaceId)
        )
        workspaceGroups = Self.group(database.workspaceRanking(since: window))
        // 主面板：异常随窗口（今日 / 近 7 天），分位随窗口。
        findings = database.loadFindings(since: window, workspaceId: workspaceId)
        agentLatency = database.latencyPercentiles(since: window)
        sessions = database.sessionsByWorkspace(since: window)
        applySourceFilter()
    }

    /// 按 `sourceFilter` 裁剪各方块（单选，恒只呈现选中来源）。
    private func applySourceFilter() {
        let src = sourceFilter
        today.agents = today.agents.filter { $0.id == src }
        workspaceGroups = workspaceGroups.filter { $0.ide == src }
        trend = trend.filter { $0.id == src }
        findings = findings.filter { $0.ide == src }
        agentLatency = agentLatency.filter { $0.ide == src }
        anomalies = anomalies.filter { $0.turn.ide == src }
        let keptWorkspaces = Set(workspaceGroups.flatMap { $0.items.map(\.workspaceId) })
        sessions = sessions.filter { keptWorkspaces.contains($0.workspaceId) }
    }

    // MARK: - 阈值（单一真相源）

    /// 生效阈值：有覆盖用覆盖，否则默认。
    var thresholds: Thresholds { Thresholds.configured(config.thresholds) }

    /// 面板展示的异常：选中来源今天用过=今日落库；否则=该来源近 7 天（与用量窗口一致）。
    var todayFindings: [FindingRow] {
        let start = Database.startOfTodayMillis() - (panelShowsToday ? 0 : 6 * 86_400_000)
        return findings.filter { $0.startedAt >= start }
    }

    /// 写回阈值并重跑全量检出（改判据即新口径）。
    func updateThresholds(_ newThresholds: Thresholds) {
        var updated = config
        updated.thresholds = newThresholds == Thresholds.default ? nil : newThresholds
        updated.save()
        config = updated
        runDetection(days: 7)
    }

    // MARK: - 检出编排（M5）

    /// 去抖调度：入库后隔一小段再跑全量检出，避免高频上报时反复重算。
    func scheduleDetection(after seconds: Double = 2.0) {
        pendingDetection?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.runDetection(days: 7) }
        pendingDetection = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    /// 后台队列跑 6 类规则→落库→回主线程刷新异常快照。
    func runDetection(days: Int) {
        detecting = true
        let thresholds = self.thresholds
        let windowStart = TUAEvent.nowMillis() - Int64(days) * 86_400_000
        detectionQueue.async { [weak self] in
            guard let self else { return }
            let turns = self.database.todayTurns(since: windowStart, workspaceId: nil)
            let turnIds = turns.map(\.turnId)
            let eventsByTurn = self.database.eventsByTurns(turnIds: turnIds)
            let anomalies = AnomalyDetector.analyze(
                turns: turns, eventsByTurn: eventsByTurn, thresholds: thresholds
            )
            // 先清窗口内旧 findings 再写新结果：阈值调高后旧误报不会残留（upsert 只增改不删）。
            self.database.deleteFindingsSince(windowStart)
            self.database.upsertFindings(anomalies)
            DispatchQueue.main.async {
                // 检出只更新了 DB；界面统一走 loadSnapshots，按当前来源 / 工作区 / 窗口口径重算。
                // 之前这里裸写全来源 findings，会盖掉来源过滤，让悬浮窗异常数在「本来源」与「全部」
                // 之间随每次入库反复跳（尤其有实时数据流入时）。
                self.loadSnapshots()
                self.detecting = false
            }
        }
    }

    // MARK: - 主面板

    func openMainPanel() { mainPanel.show(state: self) }

    // MARK: - 异常详情弹窗

    /// 从面板异常里取该类型聚合成一组，弹详情窗（一组内逐轮翻页）。
    func showAnomalyDetail(kind: String) {
        guard let group = AnomalyGroup.groups(from: todayFindings).first(where: { $0.kind == kind }) else { return }
        anomalyDetail.show(group: group)
    }

    // MARK: - 模型配置与 AI 分析

    /// 生效的外部模型配置（未填过给默认空壳，UI 据此判断 isReady）。
    var modelConfig: ModelConfig { config.model ?? ModelConfig() }

    func openModelConfig() { modelConfigPanel.show(state: self) }

    func updateModelConfig(_ model: ModelConfig) {
        var updated = config
        updated.model = model
        updated.save()
        config = updated
        // 换 Key/模型不清空旧分析：已存结果继续展示，要重新分析由用户点「重新分析」（force 重跑并覆盖）。
    }

    /// turn_id → 已完成的 AI 分析（缓存，不重复烧钱）。
    @Published private(set) var analyses: [String: AnomalyAnalysis] = [:]
    /// 正在分析中的 turn_id。
    @Published private(set) var analyzingTurnIds: Set<String> = []
    /// 分析失败：turn_id → 错误文案。
    @Published private(set) var analysisErrors: [String: String] = [:]

    /// 对某一轮发起 AI 分析（已有结果/进行中则跳过；force = 重算）。
    func requestAnalysis(finding: FindingRow, events: [EventRow], force: Bool = false) {
        let turnId = finding.turnId
        guard force || analyses[turnId] == nil, !analyzingTurnIds.contains(turnId) else { return }
        if force { analyses[turnId] = nil }
        analysisErrors[turnId] = nil
        analyzingTurnIds.insert(turnId)
        let config = modelConfig
        AnomalyAnalyzer.analyze(finding: finding, events: events, config: config) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.analyzingTurnIds.remove(turnId)
                switch result {
                case .success(let analysis):
                    self.analyses[turnId] = analysis
                    self.database.saveAnalysis(turnId: turnId, analysis: analysis, model: config.model)
                case .failure(let error):
                    self.analysisErrors[turnId] = error.localizedDescription
                }
            }
        }
    }

    // MARK: - 工作区视角

    func focusWorkspace(_ stats: WorkspaceStats) {
        focus = WorkspaceFocus(id: stats.workspaceId, name: stats.name)
        loadSnapshots()
    }

    func focusWorkspace(id: String?, name: String?) {
        guard let id, !id.isEmpty else { return }
        let label = (name?.isEmpty == false) ? name! : "未知工作区"
        focus = WorkspaceFocus(id: id, name: label)
        loadSnapshots()
    }

    func clearFocus() {
        focus = nil
        loadSnapshots()
    }

    private static func group(_ rows: [WorkspaceStats]) -> [AgentWorkspaceGroup] {
        var order: [String] = []
        var map: [String: [WorkspaceStats]] = [:]
        for row in rows {
            if map[row.ide] == nil { order.append(row.ide) }
            map[row.ide, default: []].append(row)
        }
        return order.map { AgentWorkspaceGroup(ide: $0, items: map[$0] ?? []) }
    }

    // MARK: - 悬浮窗

    func showFloatPanel() { floatPanel.show(state: self) }
    func hideFloatPanel() { floatPanel.hide() }
    func toggleFloatPanel() { floatPanel.toggle(state: self) }

    // MARK: - 接入编排（M2）

    func isInstalled(_ ide: Ide) -> Bool { installedAgents[ide.rawValue] ?? false }

    /// 重新探测各 Agent 的接入状态（读 hooks 配置，不写盘）。
    func refreshInstalled() {
        var map: [String: Bool] = [:]
        for ide in Ide.allCases { map[ide.rawValue] = installer.isInstalled(ide) }
        installedAgents = map
    }

    func install(_ ide: Ide) {
        busyAgent = ide.rawValue
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let report = self.installer.install(ide: ide, config: self.config)
            DispatchQueue.main.async {
                self.installLog = report.summary.components(separatedBy: "\n")
                self.busyAgent = nil
                self.connectingSource = false
                if report.failed { self.connectMessage = "接入未完成，详见菜单栏日志" }
                self.refreshInstalled()
                self.loadSnapshots()
            }
        }
    }

    func uninstall(_ ide: Ide) {
        busyAgent = ide.rawValue
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let report = self.installer.uninstall(ide: ide)
            DispatchQueue.main.async {
                self.installLog = report.summary.components(separatedBy: "\n")
                self.busyAgent = nil
                self.refreshInstalled()
            }
        }
    }

    /// 干跑 + 真上报验证；结束后清理探针数据，避免污染真库。
    func probe(_ ide: Ide) {
        busyAgent = ide.rawValue
        let sessionId = "tua-probe-\(Int(Date().timeIntervalSince1970))"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            self.installer.probe(ide: ide, sessionId: sessionId) { report in
                Database.shared.purgeSession(sessionId, probeRoot: HookInstaller.probeRoot)
                self.probeLog = report.summary.components(separatedBy: "\n")
                self.busyAgent = nil
                self.loadSnapshots()
            }
        }
    }

    // MARK: - 自测

    /// 向自身接收端发一条样例事件，用于打通链路（M1 验收）。
    func sendSampleEvent(completion: @escaping (String) -> Void) {
        let now = TUAEvent.nowMillis()
        let body: [String: Any] = [
            "events": [[
                "ts": now,
                "ide": "manual",
                "model": "claude-sonnet-4.5",
                "workspace_root": NSHomeDirectory() + "/Documents/ai/Memory-Observatory",
                "session_id": "sample",
                "turn_id": "sample#\(now)",
                "event_name": "PostToolUse",
                "io_kind": "file_read",
                "tool_name": "Read",
                "action_idx": 1,
                "credits": 0.66,
                "token_source": "measured",
                "latency_ms": 1200,
                "status": "ok"
            ]]
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            completion("构造样例失败")
            return
        }
        var request = URLRequest(url: URL(string: config.endpoint)!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = payload

        URLSession.shared.dataTask(with: request) { data, _, error in
            let message: String
            if let error {
                message = "发送失败：\(error.localizedDescription)"
            } else if let data, let text = String(data: data, encoding: .utf8) {
                message = text
            } else {
                message = "无响应"
            }
            DispatchQueue.main.async { completion(message) }
        }.resume()
    }
}