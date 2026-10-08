/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · report（旁路记忆观测）
 * 【文件】ReportConfig.java（io.memobservatory.agentloop.workbench.report）
 * 【核心功能】上报配置 record + builder：endpoint / agentName / defaultLayer / enabled，
 *            由 builder 组装后传入 MemoryReportMiddleware。
 * 【核心改动】2026-08-23 从 mo-agentloop 的 AgentLoopConfig 更名迁入 mo-server。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.report;

/**
 * Agent 记忆观测上报配置。由 builder 组装后传入 {@link MemoryReportMiddleware}。
 *
 * @param endpoint     服务端地址，如 http://localhost:8080/api/v1/events（必填）
 * @param agentName    Agent 名（agentId），可被运行时 ctx 覆盖
 * @param defaultLayer 事件默认层，缺省 "skill"
 * @param enabled      上报开关，false 则全部旁路丢弃
 * @param apiKey       服务端接口鉴权密钥（对应 mo.auth.api-key）。留空则不带上鉴权头
 */
public record ReportConfig(
        String endpoint,
        String agentName,
        String defaultLayer,
        boolean enabled,
        String apiKey) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private String agentName;
        private String defaultLayer = "skill";
        private boolean enabled = true;
        private String apiKey;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder defaultLayer(String defaultLayer) {
            this.defaultLayer = defaultLayer;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** 服务端接口鉴权密钥。不配则上报不带鉴权头（服务端未启用鉴权的场景）。 */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public ReportConfig build() {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint is required");
            }
            return new ReportConfig(endpoint, agentName, defaultLayer, enabled, apiKey);
        }
    }
}