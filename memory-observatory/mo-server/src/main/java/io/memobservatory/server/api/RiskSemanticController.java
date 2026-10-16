package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.api.dto.EventQueryDTO;
import io.memobservatory.server.api.dto.SessionsQueryDTO;
import io.memobservatory.server.api.dto.SkillAnalysisQueryDTO;
import io.memobservatory.server.api.dto.TokenAnalyticsQueryDTO;
import io.memobservatory.server.api.dto.TokenHeatmapQueryDTO;
import io.memobservatory.server.api.dto.TokenStatsQueryDTO;
import io.memobservatory.server.api.dto.TraceListQueryDTO;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.storage.EventRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
public class RiskSemanticController {

@Autowired
    private EventRepository repo;

@Autowired
    private io.memobservatory.server.flow.FlowAnalysisService flowService;

@Autowired
    private io.memobservatory.server.risk.RiskScanService riskService;

@Autowired
    private io.memobservatory.server.semantic.SemanticFilterService semanticFilter;

@Autowired
    private io.memobservatory.server.semantic.LayaBackendManager layaBackendManager;


    /**
     * 内容风险监测。
     *
     * <p>语义过滤的实现在 {@link io.memobservatory.server.risk.RiskScanService#scan(String, int, boolean)}：
     * 页面与告警推送必须共用同一份实现，否则「看到的」与「推出去的」会分叉。
     *
     * <p>{@code semantic=true} 时对弱格式规则的命中做语义确认，剔除「示例 / 占位符 / 文档 /
     * 测试夹具」这类假阳性；强格式规则的命中原样保留。后端不可达时自动跳过过滤并原样返回
     * （fail-open），响应里的 {@code semantic.status} 会说明是「真的过滤了」还是「因故障跳过了」。
     *
     * <p><b>为什么默认关</b>：实测（见 LAYA-INTEGRATION.md 附·实测记录 C）在当前保守阈值
     * {@code threshold-risk=0.95} 下，送检候选一条都剔不掉——假阳性的 pFalse 只有 0.806，
     * 达不到 0.95。也就是说默认开会白付最多 20 次推理（3~7s 延迟）而收益为 0。
     * 而本场景漏报不可逆，不能为了收益贸然下调阈值。
     *
     * <p>开之前应先做两件事：① 给 {@code RiskScanService} 加占位符正则（确定区，
     * 零误报解决「文档占位符」类）；② 用人工标注数据标定阈值。这也是设计文档 §3.1
     * 「加开关参数，默认关」的原意。
     */
    @GetMapping("/agents/{agentId}/risk-scan")
    public Map<String, Object> riskScan(@PathVariable String agentId,
                                        @RequestParam(defaultValue = "30") int days,
                                        @RequestParam(defaultValue = "false") boolean semantic) {
        return riskService.scan(agentId, days, semantic);
    }


    /**
     * 语义层状态：前端可据此显示「语义过滤已就绪 / 未就绪」，排障时先看这里。
     *
     * <p>{@code multilingualReady=false} 时必须警觉：本项目 Agent 内容以中文为主，
     * 中文会被路由到 multilingual checkpoint。该值 false 意味着过滤实际未生效，
     * 而非「没有假阳性」。
     *
     * <p>字段含义与排查顺序：
     * <ul>
     *   <li>{@code backendReady}——此刻 {@code /health} 可达</li>
     *   <li>{@code backendUsable}——中文自检通过，即后端与权重都真的可用</li>
     *   <li>{@code multilingualReady}——中文链路可用（最关键的一项）</li>
     *   <li>{@code backendOwned}——后端由本进程拉起的（本机形态）；容器形态为 false</li>
     * </ul>
     * 后端由容器编排管理时，启动后可能有一段「已在跑但仍在补权重」的窗口，
     * 此时 {@code backendReady=false} 属正常，语义层处于 fail-open。
     */
    @GetMapping("/semantic/status")
    public Map<String, Object> semanticStatus() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", semanticFilter.isEnabled());
        m.put("backendReady", semanticFilter.isBackendReady());
        m.put("backendUsable", layaBackendManager.isBackendUsable());
        m.put("multilingualReady", layaBackendManager.isMultilingualReady());
        m.put("backendOwned", layaBackendManager.isOwned());
        return m;
    }


    /**
     * 问题计数（供流程分析 / 内容风险监测页的 Agent 下拉展示每个 Agent 的问题量）。
     * flowIssues = 流程分析问题节点数；riskHits = 敏感信息命中数。
     */
    @GetMapping("/problem-counts")
    public Map<String, Object> problemCounts(@RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> counts = new ArrayList<>();
        for (Map<String, Object> a : repo.queryAgents(200)) {
            String id = String.valueOf(a.get("agentId"));
            Map<String, Object> row = new HashMap<>();
            row.put("agentId", id);
            try {
                row.put("flowIssues", flowService.issues(id, days, null).get("issues"));
            } catch (Exception e) {
                row.put("flowIssues", java.util.List.of());
            }
            try {
                Object total = riskService.scan(id, days).get("total");
                row.put("riskHits", total instanceof Number ? ((Number) total).intValue() : 0);
            } catch (Exception e) {
                row.put("riskHits", 0);
            }
            counts.add(row);
        }
        return Map.of("counts", counts);
    }
}
