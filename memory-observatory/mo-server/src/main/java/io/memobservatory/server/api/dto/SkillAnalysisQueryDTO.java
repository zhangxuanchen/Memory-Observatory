package io.memobservatory.server.api.dto;

import lombok.Data;

/**
 * Skill 分析查询参数（GET /api/v1/agents/{agentId}/skill-analysis）。
 *
 * ?days=30
 */
@Data
public class SkillAnalysisQueryDTO {

    private String agentId;
    private int days = 30;
}
