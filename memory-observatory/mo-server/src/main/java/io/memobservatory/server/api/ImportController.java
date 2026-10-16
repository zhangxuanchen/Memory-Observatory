package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.api.dto.EventQueryDTO;
import io.memobservatory.server.api.dto.SessionsQueryDTO;
import io.memobservatory.server.api.dto.SkillAnalysisQueryDTO;
import io.memobservatory.server.api.dto.TokenAnalyticsQueryDTO;
import io.memobservatory.server.api.dto.TokenHeatmapQueryDTO;
import io.memobservatory.server.api.dto.TokenStatsQueryDTO;
import io.memobservatory.server.api.dto.TraceListQueryDTO;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.storage.EventRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
public class ImportController {



    private static final ObjectMapper IMPORT_MAPPER = new ObjectMapper();

    /** 导入模板列顺序（与写侧解析行保持一致）。 */
    private static final List<String> IMPORT_HEADERS = List.of(
            "event_id", "agent_id", "session_id", "operation", "layer",
            "memory_key", "memory_summary", "token_count", "latency_ms",
            "timestamp", "trace_id", "parent_span_id", "metadata");

    /** 下载固定模板：?format=xlsx|json。 */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate(
            @RequestParam(name = "format", defaultValue = "xlsx") String format) {
        try {
            if ("json".equalsIgnoreCase(format)) return importTemplateJson();
            return importTemplateXlsx();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(("template generation failed: " + e.getMessage()).getBytes());
        }
    }

    private ResponseEntity<byte[]> importTemplateXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("memory_events");
            Row header = sheet.createRow(0);
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) header.createCell(i).setCellValue(IMPORT_HEADERS.get(i));
            Row example = sheet.createRow(1);
            Map<String, Object> ex = importExampleRow();
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) {
                Object v = ex.get(IMPORT_HEADERS.get(i));
                example.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
            }
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) sheet.setColumnWidth(i, 6800);
            buildImportGuideSheet(wb);
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"memory-import-template.xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    /** 生成 Excel 第二个 Sheet「参数说明·Harness 场景」，解释枚举值、字段与 harness 特有 metadata。 */
    private static void buildImportGuideSheet(XSSFWorkbook wb) {
        Sheet g = wb.createSheet("参数说明·Harness 场景");
        String[][] guide = {
                {"字段", "必填", "说明"},
                {"event_id", "否", "事件主键，留空自动生成16位id"},
                {"agent_id", "是", "Agent 标识，下拉框里该值会作为一条 Agent 展示"},
                {"session_id", "是", "会话标识；相同 session_id 的事件归为同一会话/时间线"},
                {"operation", "是", "枚举：READ=读取写入记忆 / WRITE=新增记忆 / UPDATE=更新记忆 / EXPIRE=过期遗忘（写侧 MemoryOp 归一化）"},
                {"layer", "是", "枚举：prompt=提示词相关 / session=会话汇总(turn) / skill=技能调用 / provider=外部工具/API"},
                {"memory_key", "否", "记忆键，形如 config/price、skill:do_pricing；同名 key 可做更新/统计"},
                {"memory_summary", "否", "摘要预览，事件详情里展示的一句话说明"},
                {"token_count", "否", "涉及 token 数，整数，用于 Token 分析与预算统计"},
                {"latency_ms", "否", "耗时毫秒（数字），用于延迟分位与慢操作分析"},
                {"timestamp", "否", "严格格式：yyyy-MM-dd HH:mm:ss（如 2026-08-22 11:25:50），按 Asia/Shanghai 本地时间解析；留空用当前时间"},
                {"trace_id", "否", "链路 Trace ID(32hex)；与 parent_span_id 一起构成调用链路，供 Skill/调用下钻"},
                {"parent_span_id", "否", "父 Span ID(16hex)；组合成父子调用树"},
                {"metadata", "否", "可选 JSON 附加字段，见下方 Harness 特有键说明"},
                {"", "", "---------------- Harness 特有场景：turn ---------------"},
                {"metadata", "否", "turn_message_id：一批事件属于同一轮用户对话的标识（用户问题+若干操作步骤）"},
                {"metadata", "否", "turn_user：本条 turn 的用户问题原文"},
                {"metadata", "否", "turn_actions：本条 turn 的操作步骤（可数组/文本），前端按同一 turn_message_id 展示完整步骤"},
                {"metadata", "否", "turn_outcome：turn 的总结产出"},
                {"", "", "---------------- Harness 特有场景：action 步骤子事件 ---------------"},
                {"metadata", "否", "action_idx：0,1,2... 表示该 turn 内的第几步。主事件(turn 汇总)不填此键"},
                {"metadata", "否", "action_full：该步骤的完整内容（如执行的命令/调用），便于下钻查看"},
                {"", "", "---------------- Harness 特有场景：skill 技能 ---------------"},
                {"metadata", "否", "skill_name：技能名，配合 layer=skill 用于技能分析回放"},
                {"metadata", "否", "skill_status：技能结果，如 success / failed / running"},
                {"metadata", "否", "span_id：当前 span 标识（16hex）；trace_id+span_id 唯一标记一次调用"},
                {"", "", "---------------- 说明 --------------------"},
                {"source", "否", "系统自动写入 source=import；手动上报(mcp/脚本)会写 mcp/realtime，仅用于溯源"},
        };
        for (int r = 0; r < guide.length; r++) {
            Row row = g.createRow(r);
            for (int c = 0; c < guide[r].length; c++) row.createCell(c).setCellValue(guide[r][c]);
        }
        g.setColumnWidth(0, 8400);
        g.setColumnWidth(1, 2600);
        g.setColumnWidth(2, 26000);
    }

    private ResponseEntity<byte[]> importTemplateJson() throws Exception {
        List<Map<String, Object>> rows = List.of(importExampleRow());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"memory-import-template.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(IMPORT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows));
    }

    private Map<String, Object> importExampleRow() {
        Map<String, Object> ex = new java.util.LinkedHashMap<>();
        ex.put("event_id", "");
        // 示例 timestamp 必须用导入支持的严格格式：yyyy-MM-dd HH:mm:ss（按 Asia/Shanghai 本地时间）
        String nowStr = LocalDateTime.ofInstant(
                Instant.now(), ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ex.put("agent_id", "my-agent");
        ex.put("session_id", "session-001");
        ex.put("operation", "WRITE");
        ex.put("layer", "skill");
        ex.put("memory_key", "skill:do_pricing");
        ex.put("memory_summary", "示例：技能执行完成，定价脚本产出结果");
        ex.put("token_count", 128);
        ex.put("latency_ms", 42.0);
        ex.put("timestamp", nowStr);
        ex.put("trace_id", "");
        ex.put("parent_span_id", "");
        // metadata 里示范 harness 特有字段：turn（一组用户问题→操作步骤）、skill（技能调用）
        ex.put("metadata", "{\"turn_message_id\":\"t-001\",\"turn_user\":\"给主套餐定价\","
                + "\"turn_actions\":\"[读取价格表, 计算折扣, 写回]\",\"turn_outcome\":\"已完成\","
                + "\"skill_name\":\"pricing\",\"skill_status\":\"success\",\"source\":\"import\"}");
        return ex;
    }

    /** 下载技能/MCP 接入包：skill.md + report_mcp_server.py + 按平台的 MCP 配置 + README（zip）。
     *  ?platform=claude|cursor|codex|windsurf|traecode|generic，缺省 generic（含全部平台说明）。 */
    @GetMapping("/import/skill-package")
    public ResponseEntity<byte[]> importSkillPackage(
            @RequestParam(name = "platform", required = false, defaultValue = "generic") String platform) {
        String p = (platform == null || platform.isBlank()) ? "generic" : platform.toLowerCase();
        String[] cfg = PLATFORM_MCP.get(p);
        if (cfg == null) cfg = PLATFORM_MCP.get("generic");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            addZipEntry(zip, "skill.md", SKILL_MD.getBytes());
            addZipEntry(zip, "report_mcp_server.py", REPORT_MCP_SERVER.getBytes());
            addZipEntry(zip, cfg[0], cfg[1].getBytes());
            addZipEntry(zip, "README.md", buildPackageReadme(p).getBytes());
            zip.finish();
            String fname = "generic".equals(p)
                    ? "mo-skill-package.zip"
                    : "mo-skill-package-" + p + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(("package generation failed: " + e.getMessage()).getBytes());
        }
    }

    /** 平台 → [配置文件名, MCP 配置内容]。common 为各平台通用配置体。 */
    private static final String MCP_CFG_COMMON = """
            {
              "mcpServers": {
                "mo-report": {
                  "command": "python3",
                  "args": ["/绝对/路径/report_mcp_server.py"],
                  "env": {
                    "MO_SERVER": "http://127.0.0.1:8080",
                    "MO_API_KEY": "服务端启用鉴权时填 MO_API_KEY，否则留空或删掉此行"
                  }
                }
              }
            }
            """;

    private static final Map<String, String[]> PLATFORM_MCP = Map.of(
            "claude", new String[]{"claude_desktop_config.json", MCP_CFG_COMMON},
            "cursor", new String[]{".cursor_mcp.json", MCP_CFG_COMMON},
            "codex", new String[]{"codex_mcp.json", MCP_CFG_COMMON},
            "windsurf", new String[]{"windsurf_config.json", MCP_CFG_COMMON},
            "traecode", new String[]{"traecode_mcp.json", MCP_CFG_COMMON},
            "generic", new String[]{"mcp.json", MCP_CFG_COMMON});

    private static final Map<String, String> PLATFORM_INSTRUCTIONS = Map.of(
            "claude", "打开 Claude Desktop：Settings → Developer → Edit Config，将 claude_desktop_config.json 的内容合并进配置后重启。",
            "cursor", "在项目根目录创建 .cursor/mcp.json 并写入本配置，或把生成的 .cursor_mcp.json 内容粘贴进去；重启 Cursor 后到 MCP 面板确认 mo-report 已连接。",
            "codex", "在项目根目录 Codex MCP 配置中登记 codex_mcp.json；或运行 codex mcp add mo-report python3 /绝对/路径/report_mcp_server.py。",
            "windsurf", "打开 Windsurf：Settings → MCP Servers，导入 windsurf_config.json 中的 mo-report 服务。",
            "traecode", "TraeCode 不支持注册自定义 stdio MCP；改用统一接入层 python3 -m mo_ingest --platform trae --mode log --send 批量导入，或 curl 直连 POST /api/v1/events 上报单条（见 README 与 ARCHITECTURE.md §4.2）。",
            "generic", "按目标 Agent 的 MCP 配置方式导入 mcp.json 中的 mo-report 服务（stdio：python3 report_mcp_server.py，环境变量 MO_SERVER=http://127.0.0.1:8080）。");

    /** 生成该平台的完整安装步骤 README。 */
    private static String buildPackageReadme(String platform) {
        String name = PLATFORM_MCP.containsKey(platform) ? platform : "generic";
        String step = PLATFORM_INSTRUCTIONS.getOrDefault(name, PLATFORM_INSTRUCTIONS.get("generic"));
        if ("traecode".equals(name)) {
            return "# mo-report 安装步骤（TraeCode）\n\n"
                    + "TraeCode 不允许注册自定义 stdio MCP，因此本包不用于 TraeCode。\n"
                    + "改用统一接入层 mo_ingest 或直接 HTTP 上报，零依赖：\n\n"
                    + "## 一键安装并启动 MemoryObservatory\n"
                    + "    ./install.sh\n\n"
                    + "## 批量导入历史会话（推荐）\n"
                    + "    python3 -m mo_ingest --platform trae --mode log --since 20260801 --send\n\n"
                    + "## 上报单条事件（curl）\n"
                    + "    curl -X POST http://127.0.0.1:8080/api/v1/events -H 'Content-Type: application/json' \\\n"
                    + "        -d '{\"agentId\":\"trae-code\",\"operation\":\"WRITE\",\"layer\":\"prompt\",\"memoryKey\":\"记忆/键\",\"memorySummary\":\"做了什么\",\"tokenCount\":60}'\n\n"
                    + "参数：operation=READ/WRITE/UPDATE/EXPIRE；layer=prompt/session/skill/provider。\n\n"
                    + "## 校验\n"
                    + "返回 status=200 即成功，约 5 秒后刷新 Dashboard 事件详情页（Agent=trae-code）可见。\n";
        }
        String cfgFile = name.equals("generic") ? "mcp.json" : PLATFORM_MCP.get(name)[0];
        return ("# mo-report 记忆上报 MCP 接入包（完整安装步骤）\n\n"
                + "让 Agent 侧自动上报记忆事件到 MemoryObservatory Dashboard。目标平台：%s\n\n"
                + "## 压缩包包含\n"
                + "- report_mcp_server.py   stdio MCP Server，暴露 report_memory_event 工具（真正上报的进程）\n"
                + "- skill.md               技能描述，交给 Agent 让它学会何时上报\n"
                + "- %s                     %s 平台的 MCP 配置\n"
                + "- README.md              本文件\n\n"
                + "## 安装步骤\n"
                + "1. 解压：把本包解压到任意固定目录（路径不要包含中文/空格）。\n"
                + "2. 改路径：用编辑器打开 %s，把 args 里的 \"report_mcp_server.py\" 改成它的绝对路径，例如 \"C:/Users/you/mo/result_mcp_server.py\" 或 \"/Users/you/mo/report_mcp_server.py\"。\n"
                + "3. 指地址：确保脚本进程有环境变量 MO_SERVER=http://127.0.0.1:8080（指向 MemoryObservatory；远程部署则改成服务器地址）。\n"
                + "4. 注册到平台：%s\n"
                + "5. 喂技能：把 skill.md 内容复制为 Agent 的技能/系统提示词，它会在每次记忆操作（读/写/更新/过期）时自动调用 report_memory_event。\n"
                + "6. 校验：重启 Agent 后让它调用一次 report_memory_event，若返回 eventId 即接入成功，约 5 秒后刷新 Dashboard 即可看到。\n\n"
                + "## report_memory_event 参数\n"
                + "| 参数 | 必填 | 说明 |\n"
                + "|---|---|---|\n"
                + "| agentId | 是 | Agent 标识（Dashboard 里据此聚合展示） |\n"
                + "| operation | 是 | read / write / update / expire |\n"
                + "| layer | 是 | prompt / session / skill / provider |\n"
                + "| memoryKey | 否 | 记忆键 |\n"
                + "| memorySummary | 否 | 摘要 |\n"
                + "| tokenCount | 否 | Token 数 |\n"
                + "| latencyMs | 否 | 耗时 ms |\n"
                + "| sessionId | 否 | 会话 ID（缺省 default-session） |\n\n"
                + "## 常见问题（Q&A）\n"
                + "- 返回 agentId is required：step2 的 args 路径没配对，或调用时没传 agentId。\n"
                + "- 上报失败/连不上：检查 MO_SERVER 是否可达、Firewall 是否放行 8080。\n"
                + "- 不想用 MCP：直接 POST /api/v1/events 上报单条，或 POST /api/v1/import?format=json 批量导入（模板可在 Dashboard 下载）。\n"
                ).formatted(name.toUpperCase(), cfgFile, name.toUpperCase(), cfgFile, step);
    }

    private static void addZipEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    // ==================== 数据导入（读侧）接入包内容 ====================

    private static final String SKILL_MD = """
            # MemoryObservatory 记忆上报技能

            让本 Agent 能在执行任务时，把「记忆相关操作」实时上报到 MemoryObservatory Dashboard，
            从而获得记忆读写、Token 消耗、延迟等可观测指标。

            ## 何时触发
            只要本次对话/任务涉及以下任一行为，就调用 `report_memory_event` 工具上报一条事件：
              - 读取了项目文件、配置、文档（operation=READ，layer=prompt）
              - 写入/修改了代码或配置（operation=WRITE，layer=prompt）
              - 调用了工具、外部 API、跑测试/构建（operation=WRITE，layer=skill 或 provider）
              - 总结/整理了一轮对话（operation=UPDATE，layer=session）

            ## 上报参数
              - operation: read | write | update | expire
              - layer:     prompt | session | skill | provider
              - memory_key / memory_summary: 简述这步做了什么
              - token_count: 估算消耗（约 字符数/4）
              - latency_ms:  可选，耗时毫秒数

            ## 要求
              1. 每完成一次重要操作就上报，不要批量堆积到结束。
              2. 上报失败不要阻塞主流程，继续执行任务即可。
            """;

    private static final String MCP_CONFIG = """
            {
              "mcpServers": {
                "mo-report": {
                  "command": "python3",
                  "args": ["report_mcp_server.py"],
                  "env": {
                    "MO_SERVER": "http://127.0.0.1:8080",
                    "MO_API_KEY": "服务端启用鉴权时填 MO_API_KEY，否则留空或删掉此行"
                  }
                }
              }
            }
            """;

    private static final String REPORT_MCP_SERVER = """
            #!/usr/bin/env python3
            # -*- coding: utf-8 -*-
            \"\"\"
            mo-report MCP 服务：向 MemoryObservatory 上报记忆事件。
            作为 stdio MCP Server，暴露 report_memory_event 工具。
            配置见 mcp-config.json（command=python3, args=[report_mcp_server.py]）。
            启动前请设置环境变量 MO_SERVER（默认 http://127.0.0.1:8080）；
            服务端启用鉴权时还需设置 MO_API_KEY（作为 Authorization: Bearer 发送）。
            \"\"\"
            import json, os, sys
            try:
                import requests
            except ImportError:
                import urllib.request

            MO_SERVER = os.environ.get("MO_SERVER", "http://127.0.0.1:8080").rstrip("/")
            MO_API_KEY = os.environ.get("MO_API_KEY", "").strip()

            def _headers():
                h = {"Content-Type": "application/json"}
                if MO_API_KEY:
                    h["Authorization"] = "Bearer " + MO_API_KEY
                return h

            def http_post(url, payload):
                try:
                    r = requests.post(url, json=payload, headers=_headers(), timeout=5)
                except NameError:
                    data = json.dumps(payload).encode("utf-8")
                    req = urllib.request.Request(url, data=data, headers=_headers())
                    with urllib.request.urlopen(req, timeout=5) as resp:
                        return resp.status, resp.read().decode("utf-8")
                return r.status_code, r.text

            def _get(params, *names):
                low = {str(k).lower(): v for k, v in (params or {}).items()}
                for n in names:
                    if n in low:
                        return low[n]
                return None

            def report_memory_event(params):
                payload = {
                    "agentId": _get(params, "agentid", "agent_id", "agent"),
                    "sessionId": _get(params, "sessionid", "session_id", "session") or "default-session",
                    "operation": _get(params, "operation") or "write",
                    "layer": _get(params, "layer") or "provider",
                    "memoryKey": _get(params, "memorykey", "memory_key"),
                    "memorySummary": _get(params, "memorysummary", "memory_summary"),
                    "tokenCount": int(_get(params, "tokencount", "token_count", "token") or 0),
                    "latencyMs": float(_get(params, "latencyms", "latency_ms") or 0),
                }
                if not payload.get("agentId"):
                    return {"isError": True, "content": [{"type": "text", "text": "agentId is required"}]}
                status, body = http_post(f"{MO_SERVER}/api/v1/events", payload)
                if status >= 400:
                    return {"isError": True, "content": [{"type": "text", "text": body or f"HTTP {status}"}]}
                return {"content": [{"type": "text", "text": f"ok: {body}"}]}

            def handle(inp):
                req = json.loads(inp)
                rid = req.get("id")
                name = (req.get("params") or {}).get("name", "")
                if req.get("method") == "initialize":
                    out = {"jsonrpc": "2.0", "id": rid, "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "mo-report", "version": "0.1.0"},
                    }}
                elif req.get("method") == "tools/list":
                    out = {"jsonrpc": "2.0", "id": rid, "result": {"tools": [{
                        "name": "report_memory_event",
                        "description": "向 MemoryObservatory 上报一条记忆事件（读/写/更新/过期）。",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "agentId": {"type": "string", "description": "Agent 标识（必填）"},
                                "sessionId": {"type": "string"},
                                "operation": {"type": "string", "enum": ["read", "write", "update", "expire"]},
                                "layer": {"type": "string", "enum": ["prompt", "session", "skill", "provider"]},
                                "memoryKey": {"type": "string", "description": "记忆 key"},
                                "memorySummary": {"type": "string", "description": "做了什么"},
                                "tokenCount": {"type": "integer"},
                                "latencyMs": {"type": "number"},
                            },
                            "required": ["agentId", "operation", "layer"],
                        },
                    }]}}
                elif req.get("method") == "tools/call":
                    args = (req.get("params") or {}).get("arguments", {}) or {}
                    out = report_memory_event(args)
                    out = {"jsonrpc": "2.0", "id": rid, "result": out}
                else:
                    out = {"jsonrpc": "2.0", "id": rid, "result": {}}
                sys.stdout.write(json.dumps(out) + "\\n")
                sys.stdout.flush()

            if __name__ == "__main__":
                for line in sys.stdin:
                    line = line.strip()
                    if line:
                        try:
                            handle(line)
                        except Exception as e:
                            err = {"jsonrpc": "2.0", "error": {"code": -32603, "message": str(e)}}
                            sys.stdout.write(json.dumps(err) + "\\n")
                            sys.stdout.flush()
            """;

    private static final String IMPORT_SKILL_README = """
            # MemoryObservatory 技能/MCP 接入包

            ## 三个文件
            - skill.md                 : 技能描述，复制给目标 Agent 即可让它学会上报
            - report_mcp_server.py     : 一个 stdio MCP Server，暴露 `report_memory_event` 工具
            - mcp-config.json          : 目标 Agent 客户端的 MCP 配置片段

            ## 快速开始
            1. 下载并解压本包到任意目录。
            2. 在目标 Agent 客户端的 MCP 配置里，把 `mcp-config.json` 中 `mcpServers.mo-report`
               片段合入（或复制为 `mcp.json`），确保 `command` / `args` 指向本目录下的
               `report_mcp_server.py`。
            3. 设置环境变量 `MO_SERVER=http://127.0.0.1:8080`（指向 MemoryObservatory 服务）。
            4. 把 `skill.md` 内容作为技能提示词交给 Agent。
            5. 之后 Agent 每次记忆操作都会实时上报，Dashboard 刷新可见。

            ## 说明
            - 若 Agent 不支持 stdio MCP，也可直接调 POST /api/v1/events 上报单条事件，
              或用 POST /api/v1/import 批量导入模板文件。
            """;

}
