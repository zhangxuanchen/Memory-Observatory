package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * 事件列表查询参数（GET /api/v1/agents/{agentId}/events）。
 *
 * Spring MVC 自动将 query string 绑定到同名字段：
 * ?sessionId=xxx&op=READ&layer=prompt&from=...&to=...&limit=50&offset=0
 */
@Data
public class EventQueryDTO {

    private String agentId;
    private String sessionId;
    private String eventId;
    private String op;
    private String layer;
    private String from;   // ISO-8601
    private String to;     // ISO-8601
    private int limit = 20;
    private int offset = 0;
}
