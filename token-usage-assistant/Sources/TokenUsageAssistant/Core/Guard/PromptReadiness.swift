import Foundation

/// prompt 就绪度评分（策划 §3.3 `prompt_readiness`，借《上下文记忆管理》KP 9.2.2）。
/// **纯规则、零 LLM**：三维各 0–5、合计 0–15，低于阈值即「事前拦下、去护航卡改写」。
///
/// 只读 prompt 文本本身给信号，不猜意图；认不出的词一律不扣分（宁漏不误伤）。
struct ReadinessReport {
    let score: Double          // 0–15 合计
    let clarity: Double        // 清晰度 0–5
    let completeness: Double   // 完整性 0–5
    let solvability: Double    // 可解性 0–5
    let problems: [String]     // 人类可读的「缺什么」标注（供护航卡展示）

    /// 低于该阈值即判为「就绪度不足」。
    func isBelow(_ threshold: Double) -> Bool { score < threshold }
}

enum PromptReadiness {
    // 中文为主 + 常见英文动词；子串匹配（lowercased）。认不出即不加分，不乱扣。
    private static let actionVerbs = [
        "实现", "修复", "重构", "添加", "新增", "删除", "移除", "解释", "写", "改",
        "生成", "优化", "检查", "运行", "跑", "创建", "重命名", "迁移", "测试",
        "总结", "对比", "梳理", "排查", "定位", "补充", "接入", "封装",
        "implement", "fix", "refactor", "add", "remove", "write", "create",
        "explain", "generate", "optimize", "check", "run", "test", "migrate",
    ]
    private static let objectHints = [
        "函数", "方法", "文件", "类", "接口", "组件", "页面", "模块", "脚本", "字段",
        "按钮", "测试", "文档", "报告", "方案", "配置", "数据库", "表", "路由", "接口",
        "报错", "异常", "bug", "日志", "性能", "注释", "变量", "参数", "返回值",
        "function", "class", "file", "method", "component", "api", "endpoint", "hook",
    ]
    // 指代含糊的词：过多说明「改哪儿/做什么」没说清（只轻扣，别误伤正常口语）。
    private static let vaguePronouns = [
        "这个", "那个", "这些", "那些", "这块", "那块", "上面", "刚才", "刚刚", "它",
    ]
    // 具体锚点信号：路径 / 扩展名 / 反引号 / 引号 / 数字——有则更「可解」。
    private static let anchorMarkers = ["/", "~", ".swift", ".py", ".js", ".ts", ".json", ".md", "`", "“", "”", "\""]

    static func evaluate(_ raw: String) -> ReadinessReport {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return ReadinessReport(score: 0, clarity: 0, completeness: 0, solvability: 0,
                                   problems: ["空提问：没有可评估的内容"])
        }
        let lower = text.lowercased()
        let len = text.count
        let hasVerb = actionVerbs.contains { lower.contains($0) }
        let hasObject = objectHints.contains { lower.contains($0) }
        let vagueCount = vaguePronouns.reduce(0) { $0 + (lower.contains($1) ? 1 : 0) }
        let hasAnchor = anchorMarkers.contains { text.contains($0) }
            || lower.rangeOfCharacter(from: .digits) != nil
            || hasCodeIdentifier(text)
        let asksQuestion = text.contains("？") || text.contains("?")

        // —— 清晰度（0–5）：有明确动作/诉求 + 少含糊指代 + 不太短。
        var clarity = 5.0
        if len < 8 { clarity -= 2 }
        clarity -= Double(min(vagueCount, 3))          // 每处含糊指代扣 1，最多扣 3
        if !hasVerb && !asksQuestion { clarity -= 1 }
        clarity = clamp(clarity)

        // —— 完整性（0–5）：动作 + 对象 + 产出/上下文量。
        var completeness = 0.0
        if hasVerb { completeness += 2 }
        if hasObject || hasAnchor { completeness += 1 }
        if hasObject && hasVerb { completeness += 1 }   // 动词+对象齐 = 说清了「对什么做什么」
        if len >= 20 { completeness += 1 }
        completeness = clamp(completeness)

        // —— 可解性（0–5）：有具体锚点（路径/符号/数字/引用）→ Agent 能直接下手。
        var solvability = 0.0
        if hasAnchor { solvability += 2 }
        if hasObject { solvability += 1 }
        if vagueCount == 0 { solvability += 1 }
        if len >= 12 { solvability += 1 }
        solvability = clamp(solvability)

        let score = clarity + completeness + solvability

        var problems: [String] = []
        if clarity < 3 { problems.append("没说要做什么：要么缺动作，要么全是「这个/那个」看不出指的是什么") }
        if completeness < 3 { problems.append("信息不全：没说改哪个文件/功能，也没说要拿到什么结果") }
        if solvability < 3 { problems.append("没给具体位置：缺路径、文件名或代码里的名字，AI 不知道从哪下手") }
        if problems.isEmpty { problems.append("整体说得比较含糊") }
        
        return ReadinessReport(score: score, clarity: clarity, completeness: completeness,
                               solvability: solvability, problems: problems)
    }

    private static func clamp(_ v: Double) -> Double { min(5, max(0, v)) }

    /// 粗略识别代码标识符：含 `_` 的 snake_case、或大小写混排的 camelCase、或点号路径段。
    private static func hasCodeIdentifier(_ text: String) -> Bool {
        if text.contains("_") { return true }
        // camelCase：小写后跟大写（如 readTranscript）。
        let chars = Array(text)
        for i in 1..<chars.count {
            if chars[i - 1].isLowercase && chars[i].isUppercase { return true }
        }
        return false
    }
}

private extension CharacterSet {
    static let digits = CharacterSet.decimalDigits
}
