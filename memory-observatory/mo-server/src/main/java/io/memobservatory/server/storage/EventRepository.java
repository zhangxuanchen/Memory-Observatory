package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.contract.EventQueryPort;
import io.memobservatory.contract.EventStreamPort;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemorySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 事件存储与查询门面（JdbcTemplate）。
 * 保留 {@code @Repository} Bean 身份与全部公开方法签名，内部按域委托给协作类。
 * 调用点（IngestQueue / WriteController / ApiController / AnalyticsProblemService /
 * FlowAnalysisService）零改动。
 */
@Repository
public class EventRepository implements EventQueryPort, EventStreamPort {

    private final EventWriteStore writeStore;
    private final EventReadStore readStore;
    private final TurnStore turnStore;
    private final TokenAnalyticsStore tokenStore;
    private final AnalyticsStore analyticsStore;
    private final ProblemStore problemStore;
    private final TraceStore traceStore;

    public EventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.writeStore = new EventWriteStore(jdbc, mapper);
        this.readStore = new EventReadStore(jdbc, mapper);
        this.turnStore = new TurnStore(jdbc, mapper);
        this.tokenStore = new TokenAnalyticsStore(jdbc, mapper);
        this.analyticsStore = new AnalyticsStore(jdbc, mapper);
        this.problemStore = new ProblemStore(jdbc, mapper);
        this.traceStore = new TraceStore(jdbc, mapper);
    }

    public Map<String, Object> findImportedLogByMd5(String md5) {
        return writeStore.findImportedLogByMd5(md5);
    }

    public void upsertImportedLog(String md5, String fileName, boolean llm, int total, int inserted, int skipped, int errorCount) {
        writeStore.upsertImportedLog(md5, fileName, llm, total, inserted, skipped, errorCount);
    }

    public void batchInsertEvents(List<MemoryEvent> events) {
        writeStore.batchInsertEvents(events);
    }

    public void batchInsertSnapshots(List<MemorySnapshot> snaps) {
        writeStore.batchInsertSnapshots(snaps);
    }

    public List<MemoryEvent> queryEvents(String agentId, String sessionId, String eventId, String op, String layer, Instant from, Instant to, int limit, int offset) {
        return readStore.queryEvents(agentId, sessionId, eventId, op, layer, from, to, limit, offset);
    }

    public long countEvents(String agentId, String sessionId, String eventId, String op, String layer, Instant from, Instant to) {
        return readStore.countEvents(agentId, sessionId, eventId, op, layer, from, to);
    }

    public Map<String, Object> eventStats(String agentId, String sessionId, String eventId, String op, String layer, Instant from, Instant to) {
        return readStore.eventStats(agentId, sessionId, eventId, op, layer, from, to);
    }

    public Map<String, List<MemoryEvent>> queryTimeline(String sessionId) {
        return readStore.queryTimeline(sessionId);
    }

    public Map<String, Long> tokenByLayer(String agentId, Instant from, Instant to) {
        return readStore.tokenByLayer(agentId, from, to);
    }

    public Map<String, Long> tokenByOp(String agentId, Instant from, Instant to) {
        return readStore.tokenByOp(agentId, from, to);
    }

    public List<Map<String, Object>> tokenTrend(String agentId, Instant from, Instant to, String pgUnit) {
        return readStore.tokenTrend(agentId, from, to, pgUnit);
    }

    public double memoryZonePct(String agentId, Instant from, Instant to) {
        return readStore.memoryZonePct(agentId, from, to);
    }

    public Map<String, Object> zoneBudget(String agentId, Instant from, Instant to) {
        return readStore.zoneBudget(agentId, from, to);
    }

    public List<Map<String, Object>> querySessions(String agentId, int limit) {
        return readStore.querySessions(agentId, limit);
    }

    @Override
    public List<Map<String, Object>> queryAgents(int limit) {
        return readStore.queryAgents(limit);
    }

    public List<Map<String, Object>> queryTopSessions(String agentId, Instant from, Instant to, int limit) {
        return tokenStore.queryTopSessions(agentId, from, to, limit);
    }

    public List<Map<String, Object>> queryByHour(String agentId, Instant from, Instant to) {
        return tokenStore.queryByHour(agentId, from, to);
    }

    public List<Map<String, Object>> queryOpLayerMatrix(String agentId, Instant from, Instant to) {
        return tokenStore.queryOpLayerMatrix(agentId, from, to);
    }

    @Override
    public List<Map<String, Object>> queryAgentClassify(List<String> agentIds, Instant from, Instant to) {
        return tokenStore.queryAgentClassify(agentIds, from, to);
    }

    public Map<String, Object> queryLatencyStats(String agentId, Instant from, Instant to, int topN) {
        return tokenStore.queryLatencyStats(agentId, from, to, topN);
    }

    public List<Map<String, Object>> queryTopKeys(String agentId, Instant from, Instant to, int limit) {
        return tokenStore.queryTopKeys(agentId, from, to, limit);
    }

    public Map<String, Object> queryRatios(String agentId, Instant from, Instant to) {
        return tokenStore.queryRatios(agentId, from, to);
    }

    public List<Map<String, Object>> queryHistogram(String agentId, Instant from, Instant to) {
        return tokenStore.queryHistogram(agentId, from, to);
    }

    public List<Map<String, Object>> queryDailyTrend(String agentId, Instant from, Instant to) {
        return tokenStore.queryDailyTrend(agentId, from, to);
    }

    public List<Map<String, Object>> queryLayerTimeBuckets(String agentId, Instant from, Instant to, long bucketSec) {
        return tokenStore.queryLayerTimeBuckets(agentId, from, to, bucketSec);
    }

    public Map<String, Object> querySessionTokenDistribution(String agentId, Instant from, Instant to) {
        return tokenStore.querySessionTokenDistribution(agentId, from, to);
    }

    public Map<String, Object> queryBurnRate(String agentId, Instant from, Instant to) {
        return tokenStore.queryBurnRate(agentId, from, to);
    }

    public Map<String, Object> queryKeyTokenAbnormal(String agentId, String sessionId, Instant from, Instant to, int topN) {
        return tokenStore.queryKeyTokenAbnormal(agentId, sessionId, from, to, topN);
    }

    public Map<String, Object> getEventById(String eventId) {
        return readStore.getEventById(eventId);
    }

    public List<Map<String, Object>> getTurnEvents(String agentId, String turnMessageId, String excludeEventId) {
        return turnStore.getTurnEvents(agentId, turnMessageId, excludeEventId);
    }

    public List<Map<String, Object>> listTurns(String agentId, String sessionId) {
        return turnStore.listTurns(agentId, sessionId);
    }

    public List<Map<String, Object>> listTurns(String agentId, String sessionId, String keyword) {
        return turnStore.listTurns(agentId, sessionId, keyword);
    }

    public Map<String, Object> turnHeatmap(String agentId, String sessionId, int buckets) {
        return turnStore.turnHeatmap(agentId, sessionId, buckets);
    }

    public Map<String, Object> turnStats(String agentId, String sessionId) {
        return turnStore.turnStats(agentId, sessionId);
    }

    public List<Map<String, Object>> queryAgentComparison() {
        return analyticsStore.queryAgentComparison();
    }

    public List<Map<String, Object>> queryAgentSessionComparison(String agentId) {
        return analyticsStore.queryAgentSessionComparison(agentId);
    }

    public List<Map<String, Object>> queryAgentTokenTrend(List<String> agentIds, int days) {
        return analyticsStore.queryAgentTokenTrend(agentIds, days);
    }

    public List<Map<String, Object>> querySkillTopKeys(String agentId, int limit) {
        return analyticsStore.querySkillTopKeys(agentId, limit);
    }

    public List<Map<String, Object>> querySkillOpDistribution(String agentId) {
        return analyticsStore.querySkillOpDistribution(agentId);
    }

    public List<Map<String, Object>> querySkillDailyTrend(String agentId, int days) {
        return analyticsStore.querySkillDailyTrend(agentId, days);
    }

    public List<Map<String, Object>> querySkillCrossAgent() {
        return analyticsStore.querySkillCrossAgent();
    }

    public List<Map<String, Object>> querySkillGlobalTopKeys(int limit) {
        return analyticsStore.querySkillGlobalTopKeys(limit);
    }

    public List<Map<String, Object>> querySlowSkillByAgent(Instant from, Instant to, String agentId, long minLatencyMs) {
        return analyticsStore.querySlowSkillByAgent(from, to, agentId, minLatencyMs);
    }

    public List<Map<String, Object>> querySkillCountByAgent(Instant from, Instant to, String agentId, long minCount) {
        return analyticsStore.querySkillCountByAgent(from, to, agentId, minCount);
    }

    public List<Map<String, Object>> querySkillRepeat(Instant from, Instant to, String agentId, long minCount) {
        return analyticsStore.querySkillRepeat(from, to, agentId, minCount);
    }

    public List<Map<String, Object>> querySkillErrors(Instant from, Instant to, String agentId, long minCount) {
        return analyticsStore.querySkillErrors(from, to, agentId, minCount);
    }

    public List<Map<String, Object>> querySkillMetrics(Instant from, Instant to, String agentId) {
        return analyticsStore.querySkillMetrics(from, to, agentId);
    }

    public List<Map<String, Object>> queryFlowSessions(Instant from, Instant to, String agentId) {
        return problemStore.queryFlowSessions(from, to, agentId);
    }

    public List<Map<String, Object>> queryFlowEvents(Instant from, Instant to, String agentId, String sessionId) {
        return problemStore.queryFlowEvents(from, to, agentId, sessionId);
    }

    public List<Map<String, Object>> queryToolTokenByAgent(Instant from, Instant to, String agentId, long minTokens) {
        return problemStore.queryToolTokenByAgent(from, to, agentId, minTokens);
    }

    public List<Map<String, Object>> queryTurnMetrics(Instant from, Instant to, String agentId) {
        return problemStore.queryTurnMetrics(from, to, agentId);
    }

    public List<Map<String, Object>> queryMemoryPressure(Instant from, Instant to, String agentId, long minCompressions, double minFreeRatio) {
        return problemStore.queryMemoryPressure(from, to, agentId, minCompressions, minFreeRatio);
    }

    public Map<String, String> queryTurnText(String agentId, String sessionId, String turnId) {
        return turnStore.queryTurnText(agentId, sessionId, turnId);
    }

    public List<Map<String, Object>> queryModelSlowByAgent(Instant from, Instant to, String agentId, long minAvgMs) {
        return problemStore.queryModelSlowByAgent(from, to, agentId, minAvgMs);
    }

    public List<Map<String, Object>> querySessionTokenByAgent(Instant from, Instant to, String agentId, long minTokens) {
        return problemStore.querySessionTokenByAgent(from, to, agentId, minTokens);
    }

    public List<Map<String, Object>> queryPromptSharePressure(Instant from, Instant to, String agentId, double minRatio) {
        return problemStore.queryPromptSharePressure(from, to, agentId, minRatio);
    }

    public List<Map<String, Object>> queryMemoryKeyChurn(Instant from, Instant to, String agentId, long minWrites) {
        return problemStore.queryMemoryKeyChurn(from, to, agentId, minWrites);
    }

    @Override
    public void streamEventsForExport(Instant from, Instant to, String agentId, String sessionId, String traceId, int limit, EventStreamPort.RowSink sink) {
        readStore.streamEventsForExport(from, to, agentId, sessionId, traceId, limit, sink);
    }

    public List<Map<String, Object>> queryTraceSpans(String traceId) {
        return traceStore.queryTraceSpans(traceId);
    }

    public List<Map<String, Object>> queryAgentTraces(String agentId, String sessionId, int limit, int offset) {
        return traceStore.queryAgentTraces(agentId, sessionId, limit, offset);
    }

    public String findTraceIdByEventId(String eventId) {
        return traceStore.findTraceIdByEventId(eventId);
    }

}
