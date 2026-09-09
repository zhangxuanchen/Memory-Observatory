package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * Token 热力图查询参数（GET /api/v1/agents/{agentId}/token-heatmap）。
 *
 * ?window=1d
 */
@Data
public class TokenHeatmapQueryDTO {

    private String agentId;
    private String window = "1d";
}
