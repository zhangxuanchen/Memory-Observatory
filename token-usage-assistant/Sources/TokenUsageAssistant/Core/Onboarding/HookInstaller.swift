import Foundation

/// 可接入的 IDE Agent。**每个 Agent 计费口径不同（D14）**，接入参数各自独立。
enum Ide: String, CaseIterable, Identifiable {
    case qoder

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .qoder: return "Qoder"
        }
    }

    /// 该 Agent 是否具备计量能力（真值 credits）——接入卡片如实标注（D12/D14）。
    var metered: Bool {
        switch self {
        case .qoder: return true
        }
    }

    /// hook 脚本与适配器落地的目录。
    var hookDir: String {
        switch self {
        case .qoder: return ".qoder/hooks"
        }
    }

    /// 需要幂等合并的配置文件（相对 HOME）。Qoder 国际版 / CN 版读不同文件，两份都写。
    var settingsFiles: [String] {
        switch self {
        case .qoder: return [".qoder/settings.json", ".qoder-cn/settings.json"]
        }
    }

    /// 该 Agent 支持的 hook 事件与 matcher（PostToolUse 用 "*"）。
    var hookEvents: [(name: String, matcher: String?)] {
        switch self {
        case .qoder:
            return [("UserPromptSubmit", nil), ("PostToolUse", "*"),
                    ("PostToolUseFailure", "*"), ("Stop", nil)]
        }
    }
}

/// 一步接入的执行结果（**不谎报成功**：失败如实记录原因）。
struct InstallStep {
    let message: String
    let ok: Bool
}

struct InstallReport {
    var steps: [InstallStep] = []
    var failed: Bool { steps.contains { !$0.ok } }
    var summary: String {
        steps.map { ($0.ok ? "✓ " : "✗ ") + $0.message }.joined(separator: "\n")
    }
}

/// 探针结果：干跑 + 真上报。
struct ProbeReport {
    var dryRunOK = false
    var dryRunDetail = ""
    var realReportOK = false
    var realDetail = ""
    var summary: String {
        (dryRunOK ? "✓ " : "✗ ") + "干跑：" + dryRunDetail + "\n"
            + (realReportOK ? "✓ " : "✗ ") + "真上报：" + realDetail
    }
}

/// 一键接入编排（DESIGN §4.2）：复制适配器 → 生成包装脚本 → 写密钥 → 幂等合并 hooks。
/// 幂等：重复执行不重复注册、不覆盖既有配置；断开时只移除本产品写入的块。
final class HookInstaller {
    /// 探针伪工作区（自测后由 App 侧清理，不入真库）。
    static let probeRoot = "/tmp/tua-probe"

    private let fileManager = FileManager.default

    private var home: String { NSHomeDirectory() }

    /// 包装脚本名（写入 hook 命令的那一行）。
    private func wrapperPath(_ ide: Ide) -> String { "\(home)/\(ide.hookDir)/tua-report.sh" }

    /// 写进 hooks 配置的命令（用 `~/` 前缀，与 IDE 约定一致）。
    private func commandToken(_ ide: Ide) -> String { "~/\(ide.hookDir)/tua-report.sh" }

    /// 护航命令（同一 wrapper + `--guard`，PreToolUse 事中模式；M14 已转事前，仅留作历史清理）。
    private func guardCommandToken(_ ide: Ide) -> String { commandToken(ide) + " --guard" }

    /// 事前护航命令（同一 wrapper + `--precheck`，UserPromptSubmit 专用，M14 主形态）。
    private func precheckCommandToken(_ ide: Ide) -> String { commandToken(ide) + " --precheck" }

    /// 本产品写入过的全部命令 token（卸载/检测时都要认，含历史 --guard）。
    private func commandTokens(_ ide: Ide) -> [String] {
        [commandToken(ide), guardCommandToken(ide), precheckCommandToken(ide)]
    }

    // MARK: - 接入

    func install(ide: Ide, config: AppConfig) -> InstallReport {
        var report = InstallReport()
        let dir = "\(home)/\(ide.hookDir)"

        // 0) 前置自检：python3 可用。
        let python = pythonPath()
        report.steps.append(InstallStep(
            message: python == nil ? "未找到 python3（适配器需要它）" : "python3 就绪：\(python!)",
            ok: python != nil
        ))
        guard python != nil else { return report }

        // 1) 目录
        do {
            try fileManager.createDirectory(atPath: dir, withIntermediateDirectories: true)
            report.steps.append(InstallStep(message: "目录就绪 \(dir)", ok: true))
        } catch {
            report.steps.append(InstallStep(message: "创建目录失败：\(error.localizedDescription)", ok: false))
            return report
        }

        // 2) 复制适配器
        do {
            try copyAdapter(to: dir)
            report.steps.append(InstallStep(message: "适配器已写入 tua_report.py", ok: true))
        } catch {
            report.steps.append(InstallStep(message: "复制适配器失败：\(error.localizedDescription)", ok: false))
            return report
        }

        // 3) 包装脚本
        do {
            try writeWrapper(ide: ide)
            report.steps.append(InstallStep(message: "包装脚本已生成 tua-report.sh", ok: true))
        } catch {
            report.steps.append(InstallStep(message: "写包装脚本失败：\(error.localizedDescription)", ok: false))
        }

        // 4) 密钥（600）
        do {
            try writeEnv(ide: ide, config: config)
            report.steps.append(InstallStep(message: "密钥已写入 .tua_env（权限 600）", ok: true))
        } catch {
            report.steps.append(InstallStep(message: "写 .tua_env 失败：\(error.localizedDescription)", ok: false))
        }

        // 5) 幂等合并 hooks 配置
        for file in ide.settingsFiles {
            let path = "\(home)/\(file)"
            let parent = (path as NSString).deletingLastPathComponent
            guard fileManager.fileExists(atPath: parent) else {
                report.steps.append(InstallStep(message: "跳过（未安装）：\(file)", ok: true))
                continue
            }
            do {
                let merged = try mergeHooks(at: path, ide: ide, config: config)
                report.steps.append(InstallStep(
                    message: merged ? "已合并 hooks 配置：\(file)" : "已是最新（未改动）：\(file)",
                    ok: true
                ))
            } catch {
                report.steps.append(InstallStep(message: "合并 \(file) 失败：\(error.localizedDescription)", ok: false))
            }
        }

        report.steps.append(InstallStep(message: "重启 \(ide.displayName) 后生效", ok: true))
        return report
    }

    // MARK: - 断开

    func uninstall(ide: Ide) -> InstallReport {
        var report = InstallReport()

        for file in ide.settingsFiles {
            let path = "\(home)/\(file)"
            guard fileManager.fileExists(atPath: path) else { continue }
            do {
                let changed = try removeHooks(at: path)
                report.steps.append(InstallStep(
                    message: changed ? "已移除 hooks 配置：\(file)" : "无本产品配置：\(file)",
                    ok: true
                ))
            } catch {
                report.steps.append(InstallStep(message: "移除 \(file) 失败：\(error.localizedDescription)", ok: false))
            }
        }

        for name in ["tua-report.sh", ".tua_env", "tua_report.py"] {
            let target = "\(home)/\(ide.hookDir)/\(name)"
            try? fileManager.removeItem(atPath: target)
        }
        report.steps.append(InstallStep(message: "已移除脚本与密钥（数据保留）", ok: true))
        report.steps.append(InstallStep(message: "重启 \(ide.displayName) 后彻底断开", ok: true))
        return report
    }

    /// 是否已接入（hooks 配置里存在本产品的命令）。
    func isInstalled(_ ide: Ide) -> Bool {
        let tokens = commandTokens(ide)
        for file in ide.settingsFiles {
            let path = "\(home)/\(file)"
            guard let data = fileManager.contents(atPath: path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let hooks = root["hooks"] as? [String: Any] else { continue }
            for (_, value) in hooks {
                guard let groups = value as? [[String: Any]] else { continue }
                for group in groups {
                    guard let items = group["hooks"] as? [[String: Any]] else { continue }
                    if items.contains(where: { (($0["command"] as? String).map { tokens.contains($0) }) ?? false }) { return true }
                }
            }
        }
        return false
    }

    // MARK: - 探针

    /// 干跑 + 真上报。干跑只验证适配器解析；真上报后由调用方查库确认入库。
    func probe(ide: Ide, sessionId: String, completion: @escaping (ProbeReport) -> Void) {
        var report = ProbeReport()
        let probeEvent = """
        {"hook_event_name":"UserPromptSubmit","session_id":"\(sessionId)",\
        "cwd":"\(Self.probeRoot)","model":"probe-model","prompt":"TUA 探针：写个快排"}
        """

        // 1) 干跑：只解析、不上报
        let dry = runWrapper(ide: ide, stdin: probeEvent, dryRun: true)
        report.dryRunOK = dry.stdout.trimmingCharacters(in: .whitespacesAndNewlines) == "{}"
        report.dryRunDetail = report.dryRunOK
            ? "适配器解析正常（stdout={}）"
            : "stdout 非 {}：\(dry.stdout.prefix(120))"

        // 2) 真上报：带密钥 POST
        let real = runWrapper(ide: ide, stdin: probeEvent, dryRun: false)
        let reported = real.stderr.contains("OK status=200")
        report.realReportOK = reported
        report.realDetail = reported
            ? "接收端回执 200"
            : "未收到 200：\(real.stderr.suffix(160).trimmingCharacters(in: .whitespacesAndNewlines))"
        if real.stdout.trimmingCharacters(in: .whitespacesAndNewlines) != "{}" {
            report.realReportOK = false
            report.realDetail += "（stdout 非 {}，违反旁路纪律）"
        }

        DispatchQueue.main.async { completion(report) }
    }

    // MARK: - 文件写入

    private func pythonPath() -> String? {
        ["/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3"]
            .first { fileManager.isExecutableFile(atPath: $0) }
    }

    /// 适配器源文件：优先取 App 资源包，其次回退到源码树（开发态）。
    private func adapterSource() -> URL? {
        if let url = Bundle.module.url(forResource: "tua_report", withExtension: "py", subdirectory: "adapters") {
            return url
        }
        // 资源包未打包时（如某些运行方式），回退到编译产物旁或源码路径。
        let candidates = [
            "Sources/TokenUsageAssistant/Resources/adapters/tua_report.py",
            "\(home)/Documents/ai/Memory-Observatory/token-usage-assistant/Sources/TokenUsageAssistant/Resources/adapters/tua_report.py"
        ]
        for candidate in candidates where fileManager.fileExists(atPath: candidate) {
            return URL(fileURLWithPath: candidate)
        }
        return nil
    }

    private func copyAdapter(to dir: String) throws {
        guard let source = adapterSource() else {
            throw NSError(domain: "TUA", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "找不到适配器源文件 tua_report.py"])
        }
        let target = "\(dir)/tua_report.py"
        if fileManager.fileExists(atPath: target) { try? fileManager.removeItem(atPath: target) }
        try fileManager.copyItem(at: source, to: URL(fileURLWithPath: target))
    }

    private func writeWrapper(ide: Ide) throws {
        let python = pythonPath() ?? "/usr/bin/python3"
        let script = """
        #!/bin/bash
        # Token 用量助手 · \(ide.displayName) hook 包装器（自动生成，勿手改）
        HOOK_DIR="$HOME/\(ide.hookDir)"
        # 密钥从 .tua_env 读（600、不在任何 git 仓库内）。set -a 保证 export 给 python。
        if [ -f "$HOOK_DIR/.tua_env" ]; then
          set -a
          . "$HOOK_DIR/.tua_env"
          set +a
        fi
        exec \(python) "$HOOK_DIR/tua_report.py" --platform \(ide.rawValue) "$@"
        """
        try script.write(toFile: wrapperPath(ide), atomically: true, encoding: .utf8)
        try fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: wrapperPath(ide))
    }

    private func writeEnv(ide: Ide, config: AppConfig) throws {
        let env = """
        # 由 Token 用量助手生成；含密钥，勿提交到任何 git 仓库。
        export TUA_ENDPOINT="\(config.endpoint)"
        export TUA_GUARD_ENDPOINT="\(config.guardEndpoint)"
        export TUA_PRECHECK_ENDPOINT="\(config.precheckEndpoint)"
        export TUA_API_KEY="\(config.apiKey)"
        """
        let path = "\(home)/\(ide.hookDir)/.tua_env"
        try env.write(toFile: path, atomically: true, encoding: .utf8)
        try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    }

    // MARK: - hooks 合并 / 移除

    /// 返回是否发生改动。`config.guardEnabled` 时额外幂等装 PreToolUse 护航 hook。
    private func mergeHooks(at path: String, ide: Ide, config: AppConfig) throws -> Bool {
        var root: [String: Any] = [:]
        if let data = fileManager.contents(atPath: path),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            root = parsed
        }
        var hooks = root["hooks"] as? [String: Any] ?? [:]
        let token = commandToken(ide)
        var changed = false

        var events: [(event: String, command: String, matcher: String?)] =
            ide.hookEvents.map { (event: $0.name, command: token, matcher: $0.matcher) }
        if config.guardEnabled || config.secretGuardEnabled {
            // 事前护航（M14）：UserPromptSubmit 同步探询，与旁路上报同事件、靠 `--precheck` 区分。
            // 「指导」与「敏感信息护航」任一开着都保留探针：关教练闸后密钥检测仍需拦（App 侧按闸分发）。
            events.append(("UserPromptSubmit", precheckCommandToken(ide), nil))
        } else if pruneGuardHook(event: "UserPromptSubmit", command: precheckCommandToken(ide), from: &hooks) {
            // 两闸全关：抹掉历史装过的事前探针（否则每条 prompt 都会同步问 App）。
            changed = true
        }
        // 护航已从事中（PreToolUse --guard）转事前：无论开关，都清理历史装过的事中硬拦命令。
        if pruneGuardHook(event: "PreToolUse", command: guardCommandToken(ide), from: &hooks) {
            changed = true
        }

        for (event, command, matcher) in events {
            var groups = hooks[event] as? [[String: Any]] ?? []
            let exists = groups.contains { group in
                (group["hooks"] as? [[String: Any]])?.contains { ($0["command"] as? String) == command } ?? false
            }
            if !exists {
                let item: [String: Any] = ["type": "command", "command": command, "timeout": 10]
                var group: [String: Any] = ["hooks": [item]]
                if let matcher { group["matcher"] = matcher }
                groups.append(group)
                hooks[event] = groups
                changed = true
            }
        }
        root["hooks"] = hooks
        if changed { try writeJSON(root, to: path) }
        return changed
    }

    private func removeHooks(at path: String) throws -> Bool {
        guard let data = fileManager.contents(atPath: path),
              var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return false
        }
        let tokens = commandTokens(.qoder)
        guard var hooks = root["hooks"] as? [String: Any] else { return false }
        var changed = false

        for (event, value) in hooks {
            guard let groups = value as? [[String: Any]] else { continue }
            var kept: [[String: Any]] = []
            for group in groups {
                guard var items = group["hooks"] as? [[String: Any]] else { kept.append(group); continue }
                let before = items.count
                items.removeAll { item in
                    guard let command = item["command"] as? String else { return false }
                    return tokens.contains(command)
                }
                if items.count != before {
                    changed = true
                    if !items.isEmpty {
                        var updated = group
                        updated["hooks"] = items
                        kept.append(updated)
                    }
                } else {
                    kept.append(group)
                }
            }
            hooks[event] = kept
        }
        root["hooks"] = hooks
        if changed { try writeJSON(root, to: path) }
        return changed
    }

    /// 从 hooks 字典里移除本产品装过的某护航命令（仅当关闸时调）。返回是否发生改动。
    /// 只删自己写的 `--guard` 命令，保留同事件下其它 hook；组空则整组摘掉。
    private func pruneGuardHook(event: String, command: String, from hooks: inout [String: Any]) -> Bool {
        guard let groups = hooks[event] as? [[String: Any]] else { return false }
        var changed = false
        var kept: [[String: Any]] = []
        for group in groups {
            guard var items = group["hooks"] as? [[String: Any]] else { kept.append(group); continue }
            let before = items.count
            items.removeAll { ($0["command"] as? String) == command }
            if items.count != before {
                changed = true
                if !items.isEmpty {
                    var updated = group
                    updated["hooks"] = items
                    kept.append(updated)
                }
            } else {
                kept.append(group)
            }
        }
        if changed {
            if kept.isEmpty { hooks.removeValue(forKey: event) }
            else { hooks[event] = kept }
        }
        return changed
    }

    private func writeJSON(_ object: [String: Any], to path: String) throws {
        let data = try JSONSerialization.data(withJSONObject: object,
                                              options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try data.write(to: URL(fileURLWithPath: path), options: .atomic)
    }

    // MARK: - 进程执行

    private struct ProcessResult {
        var stdout: String
        var stderr: String
        var status: Int32
    }

    private func runWrapper(ide: Ide, stdin: String, dryRun: Bool) -> ProcessResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/bash")
        process.arguments = [wrapperPath(ide)]
        var environment = ProcessInfo.processInfo.environment
        if dryRun { environment["TUA_DRY_RUN"] = "1" }
        process.environment = environment

        let input = Pipe(), output = Pipe(), error = Pipe()
        process.standardInput = input
        process.standardOutput = output
        process.standardError = error

        do {
            try process.run()
        } catch {
            return ProcessResult(stdout: "", stderr: error.localizedDescription, status: -1)
        }
        input.fileHandleForWriting.write(Data(stdin.utf8))
        try? input.fileHandleForWriting.close()

        let outData = output.fileHandleForReading.readDataToEndOfFile()
        let errData = error.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return ProcessResult(
            stdout: String(data: outData, encoding: .utf8) ?? "",
            stderr: String(data: errData, encoding: .utf8) ?? "",
            status: process.terminationStatus
        )
    }
}