import Foundation

/// 规则复杂度预判（双门的第一道门，纯规则零 LLM）：判这条提问是否「复杂到值得多轮打磨」。
/// 信号：子任务多（多个句子/问句/列表项）、篇幅长、多目标连接词密集。简单但笼统的提问判 false
/// （它需要的是补三要素，不是多轮）。
enum PromptComplexity {
    static func assess(_ prompt: String) -> Bool {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.count > 180 { return true }
        // 子任务/句子单元数（按中英文句读与换行切）。
        let units = text.split(whereSeparator: { "。？！?!；;\n".contains($0) })
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count >= 4 }
        if units.count >= 3 { return true }
        // 列表项（1. / - / •）≥2。
        let listItems = text.split(separator: "\n").filter { line in
            line.range(of: "^\\s*(?:[0-9]+[.)、]|[-•*])\\s*", options: .regularExpression) != nil
        }
        if listItems.count >= 2 { return true }
        // 多目标连接词密集（并且/然后/同时/以及/还有/另外）。
        let connectors = ["并且", "然后", "同时", "以及", "还有", "另外", "还要", "不仅", "顺带"]
        var hits = 0
        for c in connectors { hits += text.components(separatedBy: c).count - 1 }
        if hits >= 2 { return true }
        // 多个问句（用户在纠结方向）。
        let questionMarks = text.filter { "？?".contains($0) }.count
        if questionMarks >= 2 { return true }
        return false
    }
}

/// 一次澄清分叉：会实质改变改写方向的关键歧义 → 一个聚焦问题 + 互斥选项，让用户点。
struct AgentClarify {
    let question: String
    let options: [String]
}

/// 小 Agent 一轮的产出。
struct AgentTurn {
    let candidates: [String]
    let complex: Bool      // 模型自评：是否复杂到值得再来一轮
    let refined: Bool      // 是否已足够好、可停
    let clarify: AgentClarify?
    let totalTokens: Int?
}

/// 内置「提问改写」小 Agent：单 Agent、逐轮自我打磨；遇关键歧义抛方向选择让用户点、点了再细化。
/// 纯 Swift、零依赖，走 `LLMClient.chatMessages` 逐轮 threads 历史。封顶由调用方（AppState）把关：
/// 自动打磨最多 `maxRounds` 轮，到顶后用户手动「继续打磨」可越过。
final class PromptAgent {
    static let maxRounds = 3
    private(set) var rounds = 0
    private var messages: [[String: String]] = []
    private let brief: String

    init(prompt: String, problems: [String], score: Double, ruleComplex: Bool, context: String?) {
        var b = ""
        if let context, !context.isEmpty {
            b += context + "\n\n"
        }
        b += "就绪度 \(Int(score))/15（偏低）。规则标注问题：\n"
        if problems.isEmpty { b += "- （整体偏笼统）\n" }
        else { for p in problems { b += "- \(p)\n" } }
        b += "规则复杂度预判：\(ruleComplex ? "偏复杂（含多个子任务/较长）" : "偏简单")。\n"
        b += "\n原始提问：\n" + prompt
        brief = b
    }

    func begin(config: ModelConfig, completion: @escaping (Result<AgentTurn, Error>) -> Void) {
        messages = [
            ["role": "system", "content": Self.systemPrompt],
            ["role": "user", "content": brief + "\n\n这是第 1 轮：评估复杂度、给 1-3 条候选；若有会实质改变方向的关键歧义就设 clarify。"],
        ]
        rounds = 1
        run(config: config, completion: completion)
    }

    /// 自我批判并再打磨一轮（内部自迭代）。
    func refine(config: ModelConfig, completion: @escaping (Result<AgentTurn, Error>) -> Void) {
        messages.append(["role": "user", "content": "继续打磨：先自我批判上一轮候选是否真补齐了目标/边界/验收、有没有臆造需求，再给更好的候选。若已足够好，refined 设 true。"])
        rounds += 1
        run(config: config, completion: completion)
    }

    /// 用户点了澄清方向 → 据此更新候选。
    func answer(option: String, config: ModelConfig, completion: @escaping (Result<AgentTurn, Error>) -> Void) {
        messages.append(["role": "user", "content": "我的选择/补充：\(option)。据此更新候选（如仍有实质歧义可再设 clarify，否则给最终候选并把 refined 设 true）。"])
        rounds += 1
        run(config: config, completion: completion)
    }

    private func run(config: ModelConfig, completion: @escaping (Result<AgentTurn, Error>) -> Void) {
        LLMClient.chatMessages(config: config, messages: messages, maxTokens: 1400) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let reply):
                self.messages.append(["role": "assistant", "content": reply.content])
                completion(.success(Self.parse(reply.content, tokens: reply.totalTokens)))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    // MARK: Prompt

    private static let systemPrompt = """
    你是内置在「Agent 用量助手」里的提问改写小 Agent。目标：把一条就绪度偏低、容易让编程助手空跑多烧积分的提问，通过最多几轮自我打磨，改写成清晰可解的高就绪度提问——明确目标、边界（做什么/不做什么）、验收标准，必要时把大任务拆成小步。

    每轮严格只输出一个 JSON 对象（不要 markdown 围栏、不要任何额外解释），字段：
    {
      "complex": true/false,
      "refined": true/false,
      "clarify": { "question": "…", "options": ["…","…","…"] } 或 null,
      "candidates": ["…","…","…"]
    }
    - complex：这条是否真的复杂/含糊到值得再来一轮（多子任务、目标不清、缺关键上下文）。
    - refined：当前候选是否已足够好、无需再打磨。
    - clarify：仅当存在一个会实质改变改写方向的关键歧义时给出（一个聚焦问题 + 2-4 个互斥选项）；没有就给 null。
    - candidates：始终给 1-3 条可直接复制回 IDE 的改写提问。

    规则：保留用户原本想做的事，绝不臆造新需求、不加用户没提的技术栈/语言；含糊处无法推断时用「（如未指定，默认 X）」点明，别瞎猜；候选用简体中文、口语自然、单条不超过 150 字；若给了 clarify，也要同时给出基于最佳猜测的候选，别只问不答。
    
    关于「会话情景上下文」（若输入里给了同一会话之前几轮的提问）：用它推断用户这次真正在改的对象/文件/模块/目标，把候选改得更贴合当前情景（比如直接点名其在改的东西、延续上一轮的语境），降低用户的选择成本；但仍不得把上下文里没有的新需求塞进去，也不得照抄历史提问当作候选。
    """

    // MARK: 解析

    private static func parse(_ text: String, tokens: Int?) -> AgentTurn {
        if let obj = firstJSONObject(text) {
            let candidates = ((obj["candidates"] as? [Any]) ?? [])
                .compactMap { ($0 as? String).map { strip($0) } }
                .filter { !$0.isEmpty }
            let complex = (obj["complex"] as? Bool) ?? false
            let refined = (obj["refined"] as? Bool) ?? false
            var clarify: AgentClarify?
            if let c = obj["clarify"] as? [String: Any],
               let q = c["question"] as? String, !strip(q).isEmpty {
                let opts = ((c["options"] as? [Any]) ?? [])
                    .compactMap { ($0 as? String).map { strip($0) } }
                    .filter { !$0.isEmpty }
                if !opts.isEmpty { clarify = AgentClarify(question: strip(q), options: Array(opts.prefix(4))) }
            }
            if !candidates.isEmpty || clarify != nil {
                return AgentTurn(candidates: Array(candidates.prefix(3)), complex: complex,
                                 refined: refined, clarify: clarify, totalTokens: tokens)
            }
        }
        // 兜底：模型没按 JSON 来 → 整段当一条候选（至少让用户有东西可复制），不再自动打磨。
        let one = strip(text)
        return AgentTurn(candidates: one.isEmpty ? [] : [one], complex: false, refined: true,
                         clarify: nil, totalTokens: tokens)
    }

    private static func firstJSONObject(_ raw: String) -> [String: Any]? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("```") {
            if let nl = s.firstIndex(of: "\n") { s = String(s[s.index(after: nl)...]) }
            if let fence = s.range(of: "```", options: .backwards) { s = String(s[..<fence.lowerBound]) }
            s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let start = s.firstIndex(of: "{"), let end = s.lastIndex(of: "}") else { return nil }
        guard let data = String(s[start...end]).data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func strip(_ raw: String) -> String {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if (t.hasPrefix("\"") && t.hasSuffix("\"")) || (t.hasPrefix("“") && t.hasSuffix("”")) {
            t = String(t.dropFirst().dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return t
    }
}
