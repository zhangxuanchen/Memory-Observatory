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
public class FlowController {

@Autowired
    private io.memobservatory.server.flow.FlowAnalysisService flowService;


    /** 流程模式列表：trace 变体归并，按频次排序。 */
    @GetMapping("/agents/{agentId}/flows")
    public Map<String, Object> flows(@PathVariable String agentId,
                                     @RequestParam(defaultValue = "30") int days,
                                     @RequestParam(required = false) String sessionId,
                                     @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> r = flowService.patterns(agentId, days, sessionId, limit);
        r.put("days", days);
        return r;
    }


    /** 转移图：节点（含健康信号 S1/S2/S3/S7）+ 边（转移频次）。 */
    @GetMapping("/agents/{agentId}/flow-graph")
    public Map<String, Object> flowGraph(@PathVariable String agentId,
                                         @RequestParam(defaultValue = "30") int days,
                                         @RequestParam(required = false) String sessionId) {
        Map<String, Object> r = flowService.graph(agentId, days, sessionId);
        r.put("days", days);
        return r;
    }


    /** 问题节点卡片：节点 + 信号 + 证据（可回放 trace）+ 建议。 */
    @GetMapping("/agents/{agentId}/flow-issues")
    public Map<String, Object> flowIssues(@PathVariable String agentId,
                                          @RequestParam(defaultValue = "30") int days,
                                          @RequestParam(required = false) String sessionId) {
        Map<String, Object> r = flowService.issues(agentId, days, sessionId);
        r.put("days", days);
        return r;
    }


    /** 问题节点详情：定位到具体 turn（哪几个 turn、什么问题、操作上下文），供滑块展示。 */
    @GetMapping("/agents/{agentId}/flow-issue-detail")
    public Map<String, Object> flowIssueDetail(@PathVariable String agentId,
                                               @RequestParam(defaultValue = "30") int days,
                                               @RequestParam(required = false) String sessionId,
                                               @RequestParam String node,
                                               @RequestParam(required = false) Integer span) {
        Map<String, Object> r = flowService.issueDetail(agentId, days, sessionId, node, span);
        r.put("days", days);
        return r;
    }


    /** Session（trace）切换列表：供工具栏下拉。 */
    @GetMapping("/agents/{agentId}/flow-sessions")
    public Map<String, Object> flowSessions(@PathVariable String agentId,
                                            @RequestParam(defaultValue = "30") int days) {
        return flowService.sessions(agentId, days);
    }
}
