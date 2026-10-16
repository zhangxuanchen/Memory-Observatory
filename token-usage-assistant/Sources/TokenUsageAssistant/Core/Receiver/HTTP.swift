import Foundation

/// 极简 HTTP/1.1 请求解析（够用即止：首行 + 头 + Content-Length 体）。
struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]
    let body: Data

    /// 数据不足时返回 nil，由调用方继续收包。
    static func parse(_ bytes: [UInt8]) -> HTTPRequest? {
        guard let separator = indexOfHeaderTerminator(bytes) else { return nil }
        guard let head = String(bytes: bytes[0..<separator], encoding: .utf8) else { return nil }
        var lines = head.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { return nil }
        let requestLine = lines.removeFirst().split(separator: " ")
        guard requestLine.count >= 2 else { return nil }

        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }

        let contentLength = Int(headers["content-length"] ?? "0") ?? 0
        let bodyStart = separator + 4
        guard bytes.count >= bodyStart + contentLength else { return nil }
        let body = Data(bytes[bodyStart..<(bodyStart + contentLength)])

        return HTTPRequest(
            method: String(requestLine[0]),
            path: String(requestLine[1]),
            headers: headers,
            body: body
        )
    }

    private static func indexOfHeaderTerminator(_ bytes: [UInt8]) -> Int? {
        guard bytes.count >= 4 else { return nil }
        var index = 0
        while index <= bytes.count - 4 {
            if bytes[index] == 13, bytes[index + 1] == 10, bytes[index + 2] == 13, bytes[index + 3] == 10 {
                return index
            }
            index += 1
        }
        return nil
    }
}

struct HTTPResponse {
    var status: Int
    var json: [String: Any]

    func serialize() -> Data {
        let body = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("{}".utf8)
        var head = "HTTP/1.1 \(status) \(HTTPResponse.reason(for: status))\r\n"
        head += "Content-Type: application/json; charset=utf-8\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Connection: close\r\n\r\n"
        var out = Data(head.utf8)
        out.append(body)
        return out
    }

    static func reason(for status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 413: return "Payload Too Large"
        default: return "Internal Server Error"
        }
    }
}