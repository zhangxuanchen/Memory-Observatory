import Foundation

/// 入库脱敏：把流经 IDE / WorkBuddy 的原始命令、payload、用户提问里可能夹带的**凭据值**抹掉，
/// 只替换疑似密钥/令牌的取值，保留命令主体与意图（如 `Bash: git commit …`、`--save-token "<redacted>"`）。
/// 这是本地隐私防护的单一收口点——所有落库字符串（events.detail / events.payload / turns.user_prompt
/// / analyses.raw）都先过这里。规则保守：优先命中带明确前缀的令牌与 `key=值`/`--flag 值` 这类赋值形态，
/// 避免把中文散文里的“token/密码”等词后紧跟的正常文字误伤。
enum SecretRedactor {

    private static let marker = "<redacted>"

    /// (正则, 替换模板)；模板用捕获组 $1/$2 保留「键名 + 分隔符 + 起始引号」等非密部分，只覆盖值。
    private static let rules: [(pattern: String, template: String)] = [
        // Authorization: Bearer <值> / Basic <值>
 (#"(?i)(authorization\s*[:=]\s*(?:bearer|basic)\s*)([a-z0-9._+\-/]{10,})"#, "$1" + marker),
        // 带明确前缀的令牌：sk-/sk_、tk_、ghp_ 等——前缀后认下划线或连字符（OpenAI 真键是 sk-proj-… 连字符态）。
        (#"\b(?:tk|sk|pk|rk|ctx|ssd)[_-][A-Za-z0-9_\-]{12,}\b"#, marker),
        (#"\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\b"#, marker),
        (#"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b"#, marker),
        (#"\bgithub_pat_[A-Za-z0-9_]{20,}\b"#, marker),
        (#"\bAIza[0-9A-Za-z_\-]{30,}\b"#, marker),
        (#"\bxox[baprs]-[A-Za-z0-9\-]{10,}\b"#, marker),
        (#"\bya29\.[A-Za-z0-9_\-]{20,}\b"#, marker),
        // JWT：eyJ…​.eyJ…​.签名
        (#"\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\b"#, marker),
        // 赋值形态：*api_key / *token / *secret / *password … = : 值（值≥6，无空格）。
        // 键名需含密钥语义词、且紧跟 =/: —— 散文里 “token 数量” 这类无 =/: 的不会被命中。
        (#"(?i)([a-z0-9_\-]*(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|secret[_-]?key|client[_-]?secret|secret|passwd|password|pwd|token)[a-z0-9_\-]*["']?\s*[=:]\s*)(["']?)([a-z0-9_\-./+]{6,})"#,
         "$1$2" + marker),
        // 命令行 flag 形态：--xxx-token/key/secret/password <值>（需 `--` 前缀，避开中文散文）。
        (#"(?i)(--[a-z0-9\-]*(?:token|key|secret|password|passwd|pwd)[a-z0-9\-]*[ =])(["']?)([a-z0-9_\-./+]{6,})"#,
         "$1$2" + marker),
        // URL 内嵌口令：https://user:pass@host
        (#"(?i)(https?://[^\s/@:]+:)([^\s@/]{4,})(@)"#, "$1" + marker + "$3"),
        // PEM 私钥块
        (#"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"#, "<redacted-private-key>"),
    ]

    private static let matchers: [(re: NSRegularExpression, template: String)] = {
        rules.compactMap { r in
            guard let re = try? NSRegularExpression(pattern: r.pattern) else { return nil }
            return (re, r.template)
        }
    }()

    static func redactOptional(_ input: String?) -> String? {
        guard let input, !input.isEmpty else { return input }
        return redact(input)
    }

    /// 依次套用规则。返回脱敏后的串；无命中时返回原串。
    static func redact(_ input: String) -> String {
        var s = input
        for (re, tpl) in matchers {
            let range = NSRange(s.startIndex..., in: s)
            s = re.stringByReplacingMatches(in: s, range: range, withTemplate: tpl)
        }
        return s
    }

    // MARK: - 高置信凭据检测（事前护航用）

    /// 只收明确前缀/结构形态的高置信子集（不含 `token=值`、`--flag 值` 这类赋值形态）——
    /// 拦截是打扰用户的场景，宁可漏不宁误；入库脱敏仍走上面的全量 `rules`。
    private static let detectRules: [(label: String, pattern: String)] = [
        ("带前缀密钥(sk-/sk_…)", #"\b(?:tk|sk|pk|rk|ctx|ssd)[_-][A-Za-z0-9_\-]{12,}\b"#),
        ("AWS 访问密钥", #"\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\b"#),
        ("GitHub 令牌", #"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b"#),
        ("GitHub 令牌", #"\bgithub_pat_[A-Za-z0-9_]{20,}\b"#),
        ("Google API Key", #"\bAIza[0-9A-Za-z_\-]{30,}\b"#),
        ("Slack 令牌", #"\bxox[baprs]-[A-Za-z0-9\-]{10,}\b"#),
        ("Google OAuth 令牌", #"\bya29\.[A-Za-z0-9_\-]{20,}\b"#),
        ("JWT 令牌", #"\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\b"#),
        ("Authorization 头", #"(?i)authorization\s*[:=]\s*(?:bearer|basic)\s*[a-z0-9._+\-/]{10,}"#),
        ("URL 内嵌口令", #"(?i)https?://[^\s/@:]+:[^\s@/]{4,}@"#),
        ("PEM 私钥", #"-----BEGIN [A-Z ]*PRIVATE KEY-----"#),
    ]

    private static let detectMatchers: [(label: String, re: NSRegularExpression)] = {
        detectRules.compactMap { r in
            guard let re = try? NSRegularExpression(pattern: r.pattern) else { return nil }
            return (r.label, re)
        }
    }()

    /// 这条文本里是否疑似夹带凭据。返回命中的类型标签（去重、按规则序）；空数组 = 未见。
    static func detect(_ input: String) -> [String] {
        guard !input.isEmpty else { return [] }
        var hits: [String] = []
        let range = NSRange(input.startIndex..., in: input)
        for (label, re) in detectMatchers where re.firstMatch(in: input, range: range) != nil {
            if !hits.contains(label) { hits.append(label) }
        }
        return hits
    }
}
