import Foundation

/// 外部大模型配置（异常详情「AI 深度分析」用）。火山方舟 / 阿里云百炼都是 OpenAI 兼容接口，
/// 因此共用一套请求格式，仅默认地址与模型名不同。落 `config.json`。
struct ModelConfig: Codable, Equatable {
    enum Provider: String, Codable, CaseIterable, Identifiable {
        case volcengine
        case aliyun

        var id: String { rawValue }
        var label: String {
            switch self {
            case .volcengine: return "火山引擎 · 方舟"
            case .aliyun: return "阿里云 · 百炼(DashScope)"
            }
        }
        /// OpenAI 兼容的 chat/completions 完整地址（未自定义时）。
        var defaultChatURL: String {
            switch self {
            case .volcengine: return "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
            case .aliyun: return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
            }
        }
        /// 默认模型名（接入文档推荐值，供设置界面预填）。
        var defaultModel: String {
            switch self {
            case .volcengine: return "doubao-seed-2-1-pro-260915"
            case .aliyun: return "qwen-plus"
            }
        }
        var modelPlaceholder: String {
            switch self {
            case .volcengine: return "doubao-seed-2-1-pro-260915 或接入点 ep-xxxx"
            case .aliyun: return "qwen-plus / qwen-max"
            }
        }
        var keyHint: String {
            switch self {
            case .volcengine: return "方舟 API Key（ARK_API_KEY）"
            case .aliyun: return "DashScope API Key（sk-xxxx）"
            }
        }
        /// 卡片式选择器用的短名。
        var shortLabel: String {
            switch self {
            case .volcengine: return "火山方舟"
            case .aliyun: return "阿里云百炼"
            }
        }
        /// 厂商图标（SF Symbol）。
        var icon: String {
            switch self {
            case .volcengine: return "bolt.horizontal.circle.fill"
            case .aliyun: return "cloud.circle.fill"
            }
        }
    }

    var provider: Provider = .volcengine
    var apiKey: String = ""
    var model: String = ""
    /// 自定义 chat 接口完整地址；留空用 provider 默认。
    var chatURL: String = ""

    /// 填了 Key 与模型才可用。
    var isReady: Bool {
        !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var effectiveChatURL: String {
        let custom = chatURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return custom.isEmpty ? provider.defaultChatURL : custom
    }
}
