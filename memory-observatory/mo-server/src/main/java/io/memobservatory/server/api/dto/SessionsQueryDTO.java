package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * 会话列表查询参数（GET /api/v1/agents/{agentId}/sessions）。
 *
 * ?limit=50
 */
@Data
public class SessionsQueryDTO {

    private String agentId;
    private int limit = 50;
}
