import Foundation

/// 本机配置：接收端口 + 上报密钥。落 `~/Library/Application Support/TokenUsageAssistant/config.json`（600）。
struct AppConfig: Codable {
    var port: UInt16
    var apiKey: String
    /// 异常检出阈值覆盖（nil = 用 `Thresholds.default`）。整体读写，单一真相源。
    var thresholds: Thresholds?
    /// 外部大模型配置（异常详情 AI 分析）；nil = 未配置。
    var model: ModelConfig?
    /// WorkBuddy 本地导入开关；nil = 开（装了 WorkBuddy 才实际生效）。
    var workbuddyImport: Bool?
    /// WorkBuddy 导入回溯天数；nil = 14。
    var workbuddyImportDays: Int?
    /// 护航总闸（M13 T2 询问档）；nil = 关。开启后 hook 侧才会装 PreToolUse 同步探针。
    var guardOn: Bool?
    /// 敏感信息护航（独立于「指导」总闸）：nil = 开。开着时即使不指导也拦含凭据的提问，
    /// 防密钥随 prompt 外发；关 → 只影响拦不拦，不影响旁路上报。
    var secretGuardOn: Bool?
    /// 熔断档（T3 deny）；nil = 关（误拦代价远大于漏拦）。
    var guardBreaker: Bool?
    /// 护航阈值覆盖（nil = 用 `GuardThresholds.default`，口径 `m6.r1`）。
    var guardThresholds: GuardThresholds?

    /// 生效的护航决策：开总闸且熔断配置一并生效才允许 deny；询问档只需总闸。
    var guardEnabled: Bool { guardOn ?? false }
    /// 生效的敏感信息护航开关（默认开——防护优先，用户可显式关掉）。
    var secretGuardEnabled: Bool { secretGuardOn ?? true }
    /// 生效的熔断开关（总闸开着才算数）。
    var guardBreakerActive: Bool { guardEnabled && (guardBreaker ?? false) }
    /// 生效的护航阈值。
    var effectiveGuardThresholds: GuardThresholds {
        var t = GuardThresholds.configured(guardThresholds)
        t.breakerEnabled = guardBreakerActive
        return t
    }

    /// 生效的 WorkBuddy 导入开关（默认开）。
    var workbuddyImportEnabled: Bool { workbuddyImport ?? true }
    /// 生效的 WorkBuddy 导入回溯天数（默认 14）。
    var workbuddyImportWindow: Int { max(1, workbuddyImportDays ?? 14) }

    static var supportDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("TokenUsageAssistant", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    static var storeURL: URL { supportDirectory.appendingPathComponent("config.json") }

    /// 默认端口：高位随机段，避开常见开发端口。
    static let defaultPort: UInt16 = 48732

    static func load() -> AppConfig {
        if let data = try? Data(contentsOf: storeURL),
           let config = try? JSONDecoder().decode(AppConfig.self, from: data) {
            return config
        }
        let config = AppConfig(port: defaultPort, apiKey: generateKey())
        config.save()
        return config
    }

    func save() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(self) else { return }
        try? data.write(to: Self.storeURL, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Self.storeURL.path)
    }

    var endpoint: String { "http://127.0.0.1:\(port)/api/v1/events" }
    /// 护航同步查询端点（PreToolUse → guard，M13 事中，已停用装 hook，仅保留代码）。
    var guardEndpoint: String { "http://127.0.0.1:\(port)/api/v1/guard" }
    /// 事前护航同步查询端点（UserPromptSubmit → precheck，M14 主形态）。
    var precheckEndpoint: String { "http://127.0.0.1:\(port)/api/v1/precheck" }

    static func generateKey() -> String {
        "tua_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }
}