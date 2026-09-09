/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】WebSearchTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】联网搜索：以 Tavily Search API 为例，把结果转为纯文本供 LLM 阅读。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网络搜索工具：以 Tavily Search API 为例（任何 HTTP 搜索 API 同理）。
 */
public class WebSearchTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "web_search",
            description = "搜索互联网上的最新信息。当问题涉及最新事件、版本号、"
                    + "文档细节等训练数据可能过时的内容时使用。")
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词，聚焦一个问题")
            String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return "ERROR: 未配置搜索服务 API Key（TAVILY_API_KEY），web_search 不可用。";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("max_results", 5);
            payload.put("search_depth", "basic");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "ERROR: 搜索服务返回 " + resp.statusCode();
            }

            StringBuilder sb = new StringBuilder();
            JsonNode results = JSON.readTree(resp.body()).path("results");
            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                sb.append(i + 1).append(". ").append(r.path("title").asText())
                        .append("\n   URL: ").append(r.path("url").asText())
                        .append("\n   摘要: ").append(r.path("content").asText())
                        .append("\n\n");
            }
            return sb.isEmpty() ? "无搜索结果" : sb.toString();

        } catch (Exception e) {
            return "ERROR: 搜索失败: " + e.getMessage();
        }
    }
}