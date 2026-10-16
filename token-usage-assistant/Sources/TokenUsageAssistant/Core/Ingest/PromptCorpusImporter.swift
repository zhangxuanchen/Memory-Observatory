import Foundation

/// 一条历史真人轮提问（进 `prompt_corpus`，供测评体系当 context/种子，不产 events/turns）。
struct PromptCorpusRecord {
    let srcUid: String        // transcript 记录 uuid（幂等键）
    let ts: Int64             // 记录时间（毫秒）
    let ide: String
    let sessionId: String?
    let workspaceRoot: String?
    let prompt: String        // 提问原文（入库前在 DB 层脱敏）
}

/// 历史提问语料抽取器（M9 最小版 / 测评体系 Phase 0）。
///
/// 只读 `~/.qoder-cn/projects/**/transcript/*.jsonl`（IDE 本地记录，非 hook），
/// 抽「真人轮」提问原文——判据：`type=="user"` 且 `message.content` 为**纯字符串**
/// （`tool_result` 是 content 数组，属工具回传，不开轮、不纳入）。
/// 不产 events/turns、不改实时链路；幂等靠记录 uuid（DB `src_uid` UNIQUE）。
/// 对用户封闭：App 启动后台低优先级静默跑一次，无 UI 入口、无导入按钮。
final class PromptCorpusImporter {
    static let shared = PromptCorpusImporter()

    private let home = NSHomeDirectory()
    private var projectsDir: String { home + "/.qoder-cn/projects" }
    private let fm = FileManager.default

    private init() {}

    var isAvailable: Bool {
        var isDir: ObjCBool = false
        return fm.fileExists(atPath: projectsDir, isDirectory: &isDir) && isDir.boolValue
    }

    /// 后台静默跑：收集 + 幂等 upsert，回写日志。返回本次新增行数。
    @discardableResult
    func runOnce(into db: Database) -> Int {
        guard isAvailable else { return 0 }
        let records = collect()
        let inserted = db.upsertPromptCorpus(records)
        NSLog("TUA: 历史提问语料回灌 · 扫到 \(records.count) 条真人轮，新增 \(inserted)（其余按 uuid 幂等跳过），库内共 \(db.promptCorpusCount())")
        return inserted
    }

    /// 遍历全部 transcript 文件，抽出真人轮提问。
    func collect() -> [PromptCorpusRecord] {
        guard isAvailable else { return [] }
        var out: [PromptCorpusRecord] = []
        for file in transcriptFiles() {
            guard let text = try? String(contentsOfFile: file, encoding: .utf8) else { continue }
            text.enumerateLines { line, _ in
                if let rec = Self.parseHumanPrompt(line) { out.append(rec) }
            }
        }
        return out
    }

    // MARK: - 文件枚举

    /// projects 下每个工程一个 `transcript/*.jsonl` 目录。
    private func transcriptFiles() -> [String] {
        var files: [String] = []
        guard let projects = try? fm.contentsOfDirectory(atPath: projectsDir) else { return [] }
        for proj in projects.sorted() {
            let transDir = projectsDir + "/" + proj + "/transcript"
            guard let names = try? fm.contentsOfDirectory(atPath: transDir) else { continue }
            for name in names.sorted() where name.hasSuffix(".jsonl") {
                files.append(transDir + "/" + name)
            }
        }
        return files
    }

    // MARK: - 单行解析

    /// 一行 transcript JSON → 真人轮提问（非 user / content 非字符串 / 空 / 缺 uuid → nil）。
    static func parseHumanPrompt(_ line: String) -> PromptCorpusRecord? {
        guard !line.isEmpty, let data = line.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        guard obj["type"] as? String == "user" else { return nil }
        guard let message = obj["message"] as? [String: Any],
              let content = message["content"] as? String else { return nil }   // 数组(content blocks / tool_result)在此被排除
        let prompt = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return nil }
        guard let uuid = obj["uuid"] as? String, !uuid.isEmpty else { return nil }
        let ts = parseTimestamp(obj["timestamp"] as? String) ?? TUAEvent.nowMillis()
        return PromptCorpusRecord(
            srcUid: "tx_" + uuid, ts: ts, ide: "qoder",
            sessionId: obj["sessionId"] as? String,
            workspaceRoot: obj["cwd"] as? String,
            prompt: prompt
        )
    }

    /// ISO8601（可带/不带小数秒，Z 结尾）→ 毫秒。解不出返回 nil。
    static func parseTimestamp(_ raw: String?) -> Int64? {
        guard let raw, !raw.isEmpty else { return nil }
        if let d = fractionalFormatter.date(from: raw) ?? plainFormatter.date(from: raw) {
            return Int64(d.timeIntervalSince1970 * 1000)
        }
        // 兜底：去掉小数秒段再按无小数格式解析（某些位数 ISO8601DateFormatter 不吃）。
        if let dot = raw.firstIndex(of: ".") {
            let head = String(raw[..<dot]) + "Z"
            if let d = plainFormatter.date(from: head) {
                return Int64(d.timeIntervalSince1970 * 1000)
            }
        }
        return nil
    }

    private static let fractionalFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let plainFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
}
