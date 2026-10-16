import Foundation

/// 大模型对某一轮异常的四段式分析结果（发生了什么 / 为什么 / 影响 / 怎么改）。
struct AnomalyAnalysis: Equatable {
    var what = ""
    var why = ""
    var impact = ""
    var fix = ""
    /// 模型给出的分析（可能为空，如解析失败时保留原文）。
    var raw = ""
}

/// 把「某一轮的真实交互明细」喂给外部大模型，产出针对性的四段式分析。
/// 与模板版 `AnomalyInsight` 的区别：这里带上了每步工具调用的具体命令/内容，
/// 模型才能说清「到底卡在哪一步、为什么、怎么改」。
enum AnomalyAnalyzer {
    static func analyze(finding: FindingRow,
                        events: [EventRow],
                        config: ModelConfig,
                        completion: @escaping (Result<AnomalyAnalysis, Error>) -> Void) {
        let request = buildUserPrompt(finding: finding, events: events)
        // 推理型模型会先耗一段 reasoning 再输出 JSON，预算给宽点免得被截断。
        LLMClient.chat(config: config, system: systemPrompt, user: request, maxTokens: 4000) { result in
            completion(result.map { parse($0) })
        }
    }

    // MARK: Prompt

    private static let systemPrompt = """
    你是资深 AI 编程助手的用量与效率分析师，同时精通「Agent 上下文与提示词治理」。
    分析时请套用这套理念（参考《上下文记忆管理》）：注意力稀缺 / Lost in the Middle（关键信息被中部噪声稀释）、
    目标漂移（初始目标在多步中被逐步替换）、上下文腐烂（噪声堆积导致信噪比下降）、
    治理手段（把关键约束重申到尾部、规则写成清单、任务拆小、给验收标准、命令合并一次跑完）。

    用户会给你一轮（turn）里逐条真实交互记录（工具、命令/内容、耗时、成败），以及该轮被规则判定为异常的类型与依据。
    只依据这些记录分析，不要编造记录里没有的东西。用简体中文，面向普通用户讲人话，不要只复述工具动作、也不要堆术语。

    四段各自的要求：
    - what：一句话说清这轮客观发生了什么（重复次数 / 失败 / 耗时 / 积分等事实）。不超过 50 字。
    - why：向用户解释「为什么会出现这个问题」的根因，必须按下面的「对症下药」对应到本异常类型，
      并落到这轮记录里的具体证据，别泛泛而谈。不超过 90 字。
    - impact：对用户的影响，说人话（多花了多少积分 / 时间、产出为何低、会不会越跑越偏）。不超过 50 字。
    - fix：告诉用户「以后怎么避免这个问题」，给可直接照做的改进，必须按「对症下药」给本类型该给的解法方向。
      给建议而非只描述现象。不超过 90 字。

    对症下药（按「异常类型」选根因与解法方向，别串用）：
    - 原地打转 / 反复失败 / 太费钱 / 太慢：根因多在提示词与目标——prompt 太笼统、没交代目标与边界、
      缺验收标准、任务太大没拆分，导致注意力稀释、目标漂移、反复试错。fix 偏「提示词治理」：
      把目标·边界·验收标准一次写清楚、关键约束在长任务里重申、大任务拆小步、重复检查合并成一条命令一次跑完。
    - 上下文膨胀（context_bloat）：单轮工具交互总量过大（几十上百次），早期关键信息被大量工具返回淹没、信噪比下降，
      注意力被稀释、目标容易漂移（《上下文记忆管理》的腐烂/漂移）。这不是单一工具空转，而是整轮体量超标。
      fix 要给：把大任务拆成多个小任务分开跑、及时新开干净窗口、要求工具只回关键摘要而非大段原文、
      关键目标与约束在长任务里定期重申到尾部。
    - 反复压缩（frequent_compact）：这是上下文规模问题，不是 prompt 措辞问题。根因是单轮/单会话塞进太多内容
      （读太多文件、贴太长上下文、任务跨度太长），上下文逼近上限，系统被迫反复压缩摘要，越压越丢细节、信噪比崩塌。
      fix 要给操作性手段：新开一个干净窗口、把长会话按阶段拆成多个新会话、别一次性读/贴太多、及时清理无关上下文。
    - 突然失忆（expire_storm）：根因是短时间内大量记忆/上下文条目过期或被淘汰——会话太长、上下文窗口触顶，
      前面的约定和决策被挤掉，助手「忘了」之前说好的事。fix 要给：新开窗口重开会话、
      把关键约束与已定决策重新贴到新会话开头、用外部文件（需求/进度 md）固化上下文而非依赖会话记忆、控制单轮上下文规模。

    严格只输出一个 JSON 对象，不要 markdown 代码块，格式：
    {"what":"发生了什么","why":"为什么会出现（根因）","impact":"有什么影响","fix":"以后怎么避免（提示词与用法改进）"}
    """

    private static func buildUserPrompt(finding: FindingRow, events: [EventRow]) -> String {
        var lines: [String] = []
        lines.append("异常类型：\(AnomalyDetector.kindLabel(finding.kind))")
        lines.append("工作区：\(finding.workspaceLabel)｜Agent：\(finding.ide)")
        lines.append("本轮合计：\(String(format: "%.2f", finding.credits)) 积分，耗时 \(String(format: "%.1f", Double(finding.latencyMs) / 1000)) 秒")
        // 把用户本轮的原始提问单独拎出来，便于分析「是不是 prompt 太笼统导致的」。
        if let prompt = userPrompt(in: events) {
            lines.append("本轮用户输入（prompt）：\(prompt)")
        }
        if !finding.evidence.isEmpty,
           let data = try? JSONSerialization.data(withJSONObject: finding.evidence),
           let text = String(data: data, encoding: .utf8) {
            lines.append("判定依据：\(text)")
        }
        lines.append("本轮逐条交互（按时间正序）：")
        if events.isEmpty {
            lines.append("（无交互明细）")
        } else {
            for (index, event) in events.enumerated() {
                var line = "\(index + 1). \(event.actionLabel)"
                if let detail = event.detail, !detail.isEmpty {
                    let prefix = event.actionLabel + ": "
                    let body = detail.hasPrefix(prefix) ? String(detail.dropFirst(prefix.count)) : detail
                    // 过滤掉适配器回退成事件名的脏值（如 UserPromptSubmit），不把它当真实内容喂模型。
                    if !body.isEmpty, body != event.actionLabel, body != (event.eventName ?? "") {
                        line += " · " + String(body.prefix(120))
                    }
                }
                if let latency = event.latencyMs, latency > 0 {
                    line += " · \(String(format: "%.1f", Double(latency) / 1000))s"
                }
                if let status = event.status, status != "ok" { line += " · \(status)" }
                lines.append(line)
            }
        }
        return lines.joined(separator: "\n")
    }

    /// 从交互里取出本轮用户的真实提问（UserPromptSubmit 的「提问: …」）。
    /// 空输入时适配器会把 detail 回退成事件名，这种不当作提问（返回 nil）。
    private static func userPrompt(in events: [EventRow]) -> String? {
        guard let submit = events.first(where: { $0.eventName == "UserPromptSubmit" }),
              let detail = submit.detail else { return nil }
        let prefix = "提问: "
        guard detail.hasPrefix(prefix) else { return nil }
        let trimmed = String(detail.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(300))
    }

    // MARK: Parse

    /// 宽松解析：先按完整 JSON 取四字段；若 JSON 被截断 / 不合法，
    /// 再逐字段从文本里抠出已产出的内容，避免把裸 JSON 堆到「发生了什么」里。
    private static func parse(_ text: String) -> AnomalyAnalysis {
        var result = AnomalyAnalysis(raw: text)
        if let object = jsonObject(in: text), let dict = object as? [String: Any] {
            result.what = str(dict["what"]) ?? ""
            result.why = str(dict["why"]) ?? ""
            result.impact = str(dict["impact"]) ?? ""
            result.fix = str(dict["fix"]) ?? ""
        } else {
            result.what = scalarField("what", in: text) ?? ""
            result.why = scalarField("why", in: text) ?? ""
            result.impact = scalarField("impact", in: text) ?? ""
            result.fix = scalarField("fix", in: text) ?? ""
        }
        // 一个字段都没抠出来（不是我们要的 JSON），就整体当原文兑底。
        if result.what.isEmpty, result.why.isEmpty, result.impact.isEmpty, result.fix.isEmpty {
            result.what = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return result
    }
    
    /// 从（可能被截断的）JSON 文本里取 "key":"…" 的字符串值，处理转义；值未闭合也能取到已输出部分。
    private static func scalarField(_ key: String, in text: String) -> String? {
        guard let keyRange = text.range(of: "\"\(key)\"\\s*:\\s*\"", options: .regularExpression) else { return nil }
        var value = ""
        var escaped = false
        for ch in text[keyRange.upperBound...] {
            if escaped {
                switch ch {
                case "n": value.append("\n")
                case "r": value.append("\r")
                case "t": value.append("\t")
                case "\"": value.append("\"")
                case "\\": value.append("\\")
                case "/": value.append("/")
                default: value.append(ch)
                }
                escaped = false
                continue
            }
            if ch == "\\" { escaped = true; continue }
            if ch == "\"" { break }   // 值正常结束
            value.append(ch)
        }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func str(_ value: Any?) -> String? {
        guard let s = value as? String else { return nil }
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func jsonObject(in text: String) -> Any? {
        var candidate = text
        if let start = candidate.firstIndex(of: "{"), let end = candidate.lastIndex(of: "}") {
            candidate = String(candidate[start...end])
        }
        guard let data = candidate.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }
}
