import Foundation
import Network

/// 本机回环 HTTP 接收端：`127.0.0.1:<port>`，Bearer 鉴权，单次连接即关。
/// 只绑 loopback（DESIGN D4：数据不出本机）。
final class LocalReceiver {
    typealias IngestHandler = ([TUAEvent]) -> IngestResult

    var onIngest: IngestHandler?

    /// 护航决策回调（M13）：传入 PreToolUse 摘要，返回 allow/ask/deny。未设→一律 allow（fail-open）。
    var onGuard: ((GuardRequest) -> GuardOutcome)?

    /// 事前护航回调（M14）：传入 UserPromptSubmit 摘要，返回 pass/block。未设→一律 pass（fail-open）。
    var onPrecheck: ((PrecheckRequest) -> PrecheckOutcome)?

    private let port: UInt16
    private let apiKey: String
    private let maxBodyBytes = 4 * 1024 * 1024
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "tua.receiver")

    init(port: UInt16, apiKey: String) {
        self.port = port
        self.apiKey = apiKey
    }

    func start() throws {
        stop()
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw ReceiverError.invalidPort }

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: nwPort)

        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.stateUpdateHandler = { state in
            if case let .failed(error) = state { NSLog("TUA: 接收端失败 \(error)") }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - 连接处理

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: [])
    }

    private func receive(_ connection: NWConnection, buffer: [UInt8]) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 128 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(contentsOf: data) }

            if buffer.count > self.maxBodyBytes {
                self.send(connection, HTTPResponse(status: 413, json: ["ok": false, "error": "payload too large"]))
                return
            }
            if let request = HTTPRequest.parse(buffer) {
                self.send(connection, self.route(request))
            } else if isComplete || error != nil {
                connection.cancel()
            } else {
                self.receive(connection, buffer: buffer)
            }
        }
    }

    private func send(_ connection: NWConnection, _ response: HTTPResponse) {
        connection.send(content: response.serialize(), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    // MARK: - 路由

    private func route(_ request: HTTPRequest) -> HTTPResponse {
        switch (request.method, request.path) {
        case ("GET", "/api/v1/health"):
            return HTTPResponse(status: 200, json: [
                "ok": true,
                "service": "token-usage-assistant",
                "port": Int(port)
            ])
        case ("POST", "/api/v1/events"):
            return handleEvents(request)
        case ("POST", "/api/v1/guard"):
            return handleGuard(request)
        case ("POST", "/api/v1/precheck"):
            return handlePrecheck(request)
        default:
            return HTTPResponse(status: 404, json: ["ok": false, "error": "not found"])
        }
    }

    private func handleEvents(_ request: HTTPRequest) -> HTTPResponse {
        guard request.headers["authorization"] == "Bearer \(apiKey)" else {
            return HTTPResponse(status: 401, json: ["ok": false, "error": "unauthorized"])
        }
        guard let root = (try? JSONSerialization.jsonObject(with: request.body)) as? [String: Any] else {
            return HTTPResponse(status: 400, json: ["ok": false, "error": "invalid json"])
        }

        let dicts: [[String: Any]]
        if let batch = root["events"] as? [[String: Any]] {
            dicts = batch
        } else if root["event_name"] != nil || root["turn_id"] != nil || root["ts"] != nil {
            dicts = [root]
        } else {
            dicts = []
        }

        let events = dicts.compactMap { TUAEvent.from($0) }
        guard !events.isEmpty else {
            return HTTPResponse(status: 400, json: ["ok": false, "error": "no valid events"])
        }

        let result = onIngest?(events) ?? IngestResult()
        return HTTPResponse(status: 200, json: [
            "ok": true,
            "received": events.count,
            "inserted": result.inserted,
            "deduped": result.deduped
        ])
    }

    /// 护航同步查询：Bearer 同权；任何解析失败 / 未设回调 → allow（fail-open，绝不拖住 IDE）。
    private func handleGuard(_ request: HTTPRequest) -> HTTPResponse {
        guard request.headers["authorization"] == "Bearer \(apiKey)" else {
            return HTTPResponse(status: 401, json: ["ok": false, "error": "unauthorized", "decision": "allow"])
        }
        guard let root = (try? JSONSerialization.jsonObject(with: request.body)) as? [String: Any] else {
            return HTTPResponse(status: 200, json: ["ok": true, "decision": "allow"])
        }
        let outcome = onGuard?(GuardRequest.from(root)) ?? .allow
        var json: [String: Any] = ["ok": true, "decision": outcome.decision]
        if let rule = outcome.rule { json["rule"] = rule }
        if let reason = outcome.reason { json["reason"] = reason }
        return HTTPResponse(status: 200, json: json)
    }

    /// 事前护航同步查询：Bearer 同权；任何解析失败 / 未设回调 → pass（fail-open，绝不拖住 IDE）。
    private func handlePrecheck(_ request: HTTPRequest) -> HTTPResponse {
        guard request.headers["authorization"] == "Bearer \(apiKey)" else {
            return HTTPResponse(status: 401, json: ["ok": false, "error": "unauthorized", "decision": "pass"])
        }
        guard let root = (try? JSONSerialization.jsonObject(with: request.body)) as? [String: Any] else {
            return HTTPResponse(status: 200, json: ["ok": true, "decision": "pass"])
        }
        let outcome = onPrecheck?(PrecheckRequest.from(root)) ?? .pass
        var json: [String: Any] = ["ok": true, "decision": outcome.decision]
        if let reason = outcome.reason { json["reason"] = reason }
        if let score = outcome.score { json["score"] = score }
        if !outcome.problems.isEmpty { json["problems"] = outcome.problems }
        return HTTPResponse(status: 200, json: json)
    }
}

enum ReceiverError: LocalizedError {
    case invalidPort

    var errorDescription: String? {
        switch self {
        case .invalidPort: return "无效端口"
        }
    }
}