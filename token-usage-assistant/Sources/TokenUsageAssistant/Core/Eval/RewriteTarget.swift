import Foundation

/// 全局改写目标 ID 常量（落库 / 埋点用）。命名规范 `<domain>.<verb>`。
enum TargetID {
    static let promptPrecheck = "prompt.precheck"
    // 预留：anomalyExplain = "anomaly.explain"；weeklyReport = "report.weekly"；……
}

/// 目标专属的「这条种子是否收进评测集」评估；nil = 跳过。
struct SeedAssessment {
    let problems: [String]
    /// 目标专属分；precheck 下为 `PromptReadiness` 0-15。字段名为历史沿续，含义由目标自定。
    let score: Double
}

/// 一个「可测评的改写目标」= 输入 → 若干候选输出 的一段转换，是测评体系的一等公民。
///
/// 设计意图：整套闭环（造锚 / 指标 / 盲评 / 拟合判据 / 版本回归门）**不为某个具体改写定制**，
/// 而是面向「目标」这一抽象——prompt 拦截改写只是当前唯一落地实例，异常解释、周报生成、
/// 任何"给一段输入产出候选文本"的转换都可复用同一套管道。接入新目标只需实现本协议并登记。
protocol RewriteTarget: AnyObject {
    /// 稳定 ID，落库用（见 `TargetID`）。
    var targetID: String { get }
    /// 人类可读名，CLI / 界面展示用。
    var displayName: String { get }

    /// 该目标的种子池来源（不同目标来源不同：precheck = 语料 + guard_log；explain = findings + 事件）。
    /// 约定：返回项的 `uid` 需自带命名空间前缀（如 tx_/gl_），以免与其它目标在 `eval_cases.src_uid` 撞 UNIQUE。
    func seedingInputs(db: Database) -> [SeedingPrompt]

    /// 目标自己判断“这条输入够不够烂值得进考卷”，并附一份目标专属的问题清单与分数。
    /// 框架层不预设“什么叫需要改写”——向目标下钻，避免把 PromptReadiness 等 precheck 专属判据漏进通用骨架。
    func assessSeed(_ sp: SeedingPrompt) -> SeedAssessment?

    /// 对单条输入生成候选。评测跑子（temp=0）与盲评导出都走这里；`version` 预留给 Phase 2 版本化。
    func generateCandidates(for input: String, context: String?, version: String,
                            config: ModelConfig) async throws -> [String]
}

/// 注册表：接入新目标只需往 `all` 里加一个实例，其余闭环自动对它生效。
enum RewriteTargets {
    static let all: [RewriteTarget] = [
        PromptPrecheckTarget()
        // 未来：AnomalyExplainTarget(), WeeklyReportTarget(), ...
    ]
    static func resolve(_ id: String) -> RewriteTarget? { all.first { $0.targetID == id } }
    static var fallback: RewriteTarget { all[0] }
}

/// 「事前拦截 · prompt 改写」——Phase 0/1 唯一落地目标；把现有 `PromptAgent` 包进通用协议。
/// 目标专属的语义（读 prompt_corpus、用 PromptReadiness 打分、走 PromptAgent）都封在这里，
/// 不外泄进测评骨架，从而保证骨架是目标无关的。
final class PromptPrecheckTarget: RewriteTarget {
    let targetID = TargetID.promptPrecheck
    let displayName = "事前拦截 · prompt 改写"
    private let blockBelow: Double

    init(blockBelow: Double = GuardThresholds.default.promptReadinessBlockBelow) {
        self.blockBelow = blockBelow
    }

    func seedingInputs(db: Database) -> [SeedingPrompt] { db.seedingPrompts() }

    func assessSeed(_ sp: SeedingPrompt) -> SeedAssessment? {
        let r = PromptReadiness.evaluate(sp.prompt)
        guard r.isBelow(blockBelow) else { return nil }
        return SeedAssessment(problems: r.problems, score: r.score)
    }

    func generateCandidates(for input: String, context: String?, version: String,
                            config: ModelConfig) async throws -> [String] {
        let report = PromptReadiness.evaluate(input)
        let agent = PromptAgent(prompt: input, problems: report.problems, score: report.score,
                                ruleComplex: false, context: context)
        return try await withCheckedThrowingContinuation { cont in
            agent.begin(config: config) { result in
                switch result {
                case .success(let turn): cont.resume(returning: turn.candidates)
                case .failure(let error): cont.resume(throwing: error)
                }
            }
        }
    }
}
