package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * Token 统计查询参数（GET /api/v1/agents/{agentId}/token-stats）。
 *
 * ?from=2026-08-01T00:00:00Z&to=...&window=1h
 */
@Data
public class TokenStatsQueryDTO {

    private String agentId;
    private String from;   // ISO-8601
    private String to;     // ISO-8601
    private String window = "1h";
}
