package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * Token 多维度分析查询参数（GET /api/v1/agents/{agentId}/token-analytics）。
 *
 * ?window=30d
 */
@Data
public class TokenAnalyticsQueryDTO {

    private String agentId;
    private String window = "30d";
    /** 可选。传入后 keyAbnormal（memory_key 膨胀检测）按该会话过滤，仅选 Session 时展示。 */
    private String sessionId;
}
