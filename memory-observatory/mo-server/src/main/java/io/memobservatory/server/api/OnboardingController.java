package io.memobservatory.server.api;

import io.memobservatory.server.onboarding.QoderHookService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「一键接入」向导后端：按平台暴露扫描 / 安装 / 自检端点。
 *
 * <p>第一版只做 Qoder（含 CN 版）实时 Hook。其它平台以 {@code supported=false} 如实列出，
 * 前端据此把按钮置灰，不假装能一键。
 */
@RestController
@RequestMapping("/api/v1")
public class OnboardingController {

    private final QoderHookService qoder;

    public OnboardingController(QoderHookService qoder) {
        this.qoder = qoder;
    }

    /** 平台清单：id / 名称 / 传输方式 / 是否已支持一键。 */
    @GetMapping("/onboarding/platforms")
    public Map<String, Object> platforms() {
        List<Map<String, Object>> items = List.of(
                entry("qoder", "Qoder CN / Qoder IDE", "hook", true),
                entry("trae", "Trae", "hook", false),
                entry("claude", "Claude / Cursor / Codex / Windsurf（MCP）", "mcp", false),
                entry("generic", "通用 HTTP 上报", "http", false));
        return Map.of("platforms", items);
    }

    private static Map<String, Object> entry(String id, String name, String transport, boolean supported) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("transport", transport);
        m.put("supported", supported);
        return m;
    }

    /** Qoder 接入状态扫描（只读）：window = 自检统计近 N 分钟入库事件，默认 30。 */
    @GetMapping("/onboarding/qoder/status")
    public Map<String, Object> qoderStatus(@RequestParam(name = "window", defaultValue = "30") int window) {
        return qoder.status(Math.max(1, Math.min(window, 1440)));
    }

    /** Qoder 一键安装（幂等）。失败如实回 500 + 原因，不静默。 */
    @PostMapping("/onboarding/qoder/install")
    public Map<String, Object> qoderInstall() throws IOException {
        return qoder.install();
    }

    /** Qoder 实时事件流：最近 N 条（默认 10），时间升序，供前端“监听屏”滚动展示。
     *  可选 scope=project|workspace|task + value 按层级过滤。 */
    @GetMapping("/onboarding/qoder/feed")
    public Map<String, Object> qoderFeed(
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "value", required = false) String value) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("events", qoder.feed(Math.max(1, Math.min(limit, 50)), scope, value));
        resp.put("total", qoder.total());
        return resp;
    }

    /** 层级清单：近 window 分钟内出现过的 project / workspace，供实时流过滤下拉。 */
    @GetMapping("/onboarding/qoder/scopes")
    public Map<String, Object> qoderScopes(@RequestParam(name = "window", defaultValue = "120") int window) {
        return qoder.scopes(Math.max(1, Math.min(window, 1440)));
    }
}
