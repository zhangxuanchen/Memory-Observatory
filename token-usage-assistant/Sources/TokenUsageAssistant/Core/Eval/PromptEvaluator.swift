import Foundation

// MARK: - 数据行

/// 一条待灌入的评测 case（造锚产物）。
struct EvalCaseRow {
    let srcUid: String
    let target: String
    let prompt: String
    let workspaceId: String?
    let ide: String?
    let sessionId: String?
    let problems: [String]
    let readinessBefore: Double
    let tags: [String]
    let cohort: String
    let addedTs: Int64
}

/// 读回的评测 case。
struct EvalCase: Identifiable {
    let id: Int64
    let srcUid: String
    let target: String
    let prompt: String
    let workspaceId: String?
    let ide: String?
    let problems: [String]
    let readinessBefore: Double
    let cohort: String
}

/// 候选种子提问（来自某改写目标的种子池）。
struct SeedingPrompt {
    let uid: String
    let prompt: String
    let workspaceId: String?
    let sessionId: String?
    let ide: String?
    let ts: Int64
}

// MARK: - 评测器（Phase 1：造锚，面向「改写目标」而非某一内容）

/// 从某目标的真实历史里冻结出一份「需改写」评测集，作为拟合判据的刻度起点。
enum PromptEvaluator {
    /// 灌入 `eval_cases`：从目标种子池取 `assessSeed != nil` 的真实句（“够烂”由目标自定），
    /// 按原文去重，再**按工作区 round-robin 铺开**（覆盖多来源、不被单一大户垄断），封顶 `limit`。
    /// 幂等：`src_uid = "ec_" + 来源 uid`，重跑不增行。返回本次新增。
    /// 注：框架层不预设“什么叫需要改写”——打分/筛选全下沉到 `target.assessSeed`。
    @discardableResult
    static func seedCases(target: RewriteTarget,
                          cohort: String = "v1", limit: Int = 40, db: Database = .shared) -> Int {
        let all = target.seedingInputs(db: db)

        // 1) 规范化去重 + 只留目标认作“需改写”的真实句（携带目标专属问题与分）。
        var byKey: [String: (SeedingPrompt, SeedAssessment)] = [:]
        var order: [String] = []
        var seen = Set<String>()
        for sp in all {
            let norm = sp.prompt.lowercased()
            guard !seen.contains(norm) else { continue }
            guard let a = target.assessSeed(sp) else { continue }
            seen.insert(norm)
            byKey[norm] = (sp, a)
            order.append(norm)
        }

        // 2) 按工作区分组（保出现顺序），组按容量降序，round-robin 取到 limit。
        var groups: [String: [String]] = [:]
        var groupOrder: [String] = []
        for norm in order {
            let ws = byKey[norm]!.0.workspaceId ?? "∅"
            if groups[ws] == nil { groupOrder.append(ws) }
            groups[ws, default: []].append(norm)
        }
        groupOrder.sort { (groups[$0]?.count ?? 0) > (groups[$1]?.count ?? 0) }

        var chosen: [String] = []
        var pointer = 0
        var progressed = true
        while chosen.count < limit && progressed {
            progressed = false
            for ws in groupOrder {
                guard let g = groups[ws], pointer < g.count else { continue }
                chosen.append(g[pointer])
                progressed = true
                if chosen.count >= limit { break }
            }
            pointer += 1
        }

        // 3) 落库（幂等）。
        let now = TUAEvent.nowMillis()
        let rows: [EvalCaseRow] = chosen.compactMap { norm in
            guard let (sp, a) = byKey[norm] else { return nil }
            var tags = ["seed"]
            if let ws = sp.workspaceId { tags.append(ws) }
            return EvalCaseRow(srcUid: "ec_" + sp.uid, target: target.targetID, prompt: sp.prompt,
                               workspaceId: sp.workspaceId, ide: sp.ide, sessionId: sp.sessionId,
                               problems: a.problems, readinessBefore: a.score,
                               tags: tags, cohort: cohort, addedTs: now)
        }
        NSLog("TUA eval[\(target.targetID)]: 目标认作需改写的真实句去重后 \(order.count) 条，铺开取 \(chosen.count)（\(groupOrder.count) 个工作区）")
        return db.upsertEvalCases(rows)
    }
}

// MARK: - 命令行入口（D1：主二进制隐藏入口，跑完即退，不进 UI）

enum EvalCLI {
    /// 在 `applicationDidFinishLaunching` 最前面调用；带 `--tua-eval` 时执行 dev 子命令并 exit，绝不进界面。
    static func handleIfPresent(_ args: [String]) {
        guard let i = args.firstIndex(of: "--tua-eval") else { return }
        let sub = (args.count > i + 1) ? args[i + 1] : ""
        let target = resolveTarget(from: args)
        let db = Database.shared
        switch sub {
        case "list-targets":
            for t in RewriteTargets.all { print("  \(t.targetID) :: \(t.displayName)") }
        case "seed-cases":
            let n = PromptEvaluator.seedCases(target: target, db: db)
            print("TUA eval[\(target.targetID)]: 新增 \(n)，该目标 eval_cases 共 \(db.evalCaseCount(target: target.targetID))（库内总 \(db.evalCaseCount())）")
        case "list-cases":
            for c in db.loadEvalCases(target: target.targetID) {
                print("#\(c.id) [\(c.target)] [\(String(format: "%.0f", c.readinessBefore))/15] ws=\(c.workspaceId ?? "∅") :: \(c.prompt.prefix(60))")
            }
        case "stats":
            print("TUA eval stats: corpus=\(db.promptCorpusCount()) eval_cases=\(db.evalCaseCount()) gold=\(db.goldCount()) preference=\(db.preferencePairCount()) adoption=\(db.adoptionEventCount())")
            for t in RewriteTargets.all {
                print("  [\(t.targetID)] eval_cases=\(db.evalCaseCount(target: t.targetID))")
            }
        default:
            print("用法: --tua-eval list-targets | seed-cases | list-cases | stats [--target <id>]")
        }
        exit(0)
    }

    /// 从参数里解析 `--target <id>`，缺省或未知都用注册表首个目标。
    private static func resolveTarget(from args: [String]) -> RewriteTarget {
        if let ti = args.firstIndex(of: "--target"), ti + 1 < args.count {
            return RewriteTargets.resolve(args[ti + 1]) ?? RewriteTargets.fallback
        }
        return RewriteTargets.fallback
    }
}
