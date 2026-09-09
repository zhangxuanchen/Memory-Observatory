package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * Trace 列表查询参数（GET /api/v1/agents/{agentId}/traces）。
 *
 * ?session_id=xxx&limit=20&offset=0
 */
@Data
public class TraceListQueryDTO {

    private String agentId;
    private String sessionId;
    private int limit = 20;
    private int offset = 0;
}
