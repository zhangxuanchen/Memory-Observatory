import Foundation

/// 出站大模型调用失败的原因（如实透出，不假装成功）。
enum LLMError: LocalizedError {
    case notConfigured
    case badURL
    case http(Int, String)
    case empty(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "尚未配置模型：点右上角 ⚙ 填入 API Key 与模型名"
        case .badURL:
            return "接口地址无效，请在设置里检查"
        case .http(let code, let body):
            return "接口返回 \(code)：\(body.prefix(200))"
        case .empty(let body):
            return body.isEmpty ? "模型没有返回内容" : "模型没有返回可解析内容：\(body.prefix(200))"
        }
    }
}

/// 极简 OpenAI 兼容 chat 客户端（零依赖，只用 URLSession）。
enum LLMClient {
    /// 一次调用的返回：正文 + 可选 token 用量（部分服务不回 usage 则为 nil，不猜）。
    struct Reply {
        let content: String
        let totalTokens: Int?
    }

    /// 单发（system + 一条 user）：保持既有调用方（AnomalyAnalyzer）签名不变。
    static func chat(config: ModelConfig,
                     system: String,
                     user: String,
                     maxTokens: Int = 800,
                     timeout: TimeInterval = 40,
                     completion: @escaping (Result<String, Error>) -> Void) {
        let messages: [[String: String]] = [
            ["role": "system", "content": system],
            ["role": "user", "content": user],
        ]
        send(config: config, messages: messages, maxTokens: maxTokens,
             temperature: 0.3, timeout: timeout) { result in
            completion(result.map { $0.content })
        }
    }

    /// 多轮：直接传完整 messages 历史（内置小 Agent 逐轮推进用），回带 token 用量。
    static func chatMessages(config: ModelConfig,
                             messages: [[String: String]],
                             maxTokens: Int = 1400,
                             temperature: Double = 0.4,
                             timeout: TimeInterval = 40,
                             completion: @escaping (Result<Reply, Error>) -> Void) {
        send(config: config, messages: messages, maxTokens: maxTokens,
             temperature: temperature, timeout: timeout, completion: completion)
    }

    private static func send(config: ModelConfig,
                             messages: [[String: String]],
                             maxTokens: Int,
                             temperature: Double,
                             timeout: TimeInterval,
                             completion: @escaping (Result<Reply, Error>) -> Void) {
        guard config.isReady else {
            completion(.failure(LLMError.notConfigured)); return
        }
        guard let url = URL(string: config.effectiveChatURL) else {
            completion(.failure(LLMError.badURL)); return
        }
        let body: [String: Any] = [
            "model": config.model,
            "messages": messages,
            "max_tokens": maxTokens,
            "temperature": temperature,
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            completion(.failure(LLMError.empty(""))); return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = payload

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(error)); return
            }
            guard let http = response as? HTTPURLResponse, let data else {
                completion(.failure(LLMError.empty(""))); return
            }
            let raw = String(data: data, encoding: .utf8) ?? ""
            guard http.statusCode == 200 else {
                completion(.failure(LLMError.http(http.statusCode, raw)))
                return
            }
            guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(.failure(LLMError.empty(raw))); return
            }
            // 部分服务 200 里塞 error（如模型名 / 接入点不对），如实透出别冒充「无内容」
            if let err = object["error"] as? [String: Any],
               let message = (err["message"] as? String) ?? (err["code"] as? String) {
                completion(.failure(LLMError.http(http.statusCode, message)))
                return
            }
            // OpenAI 兼容：choices[0].message.content；usage.total_tokens 可选
            guard let choices = object["choices"] as? [[String: Any]],
                  let message = choices.first?["message"] as? [String: Any],
                  let content = message["content"] as? String,
                  !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                completion(.failure(LLMError.empty(raw))); return
            }
            let usage = object["usage"] as? [String: Any]
            let total = (usage?["total_tokens"] as? Int) ?? (usage?["total_tokens"] as? Double).map { Int($0) }
            completion(.success(Reply(content: content, totalTokens: total)))
        }.resume()
    }
}
