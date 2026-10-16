package io.memobservatory.server.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import io.memobservatory.contract.ConfirmKind;
import io.memobservatory.contract.SemanticConfirmPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 语义过滤层统一入口。
 *
 * <p><b>单向过滤</b>：确定层（阈值 / 规则 / 正则）照常全量命中，本层只负责把假阳性剔掉。
 * 实现上刻意与「按阈值放行」相反——<b>默认保留，只有高置信判为假阳性才剔除</b>。
 * 这样任何不确定的情况（解析失败、后端抖动、超时而找不到判断）都倒向「保留告警」，
 * 不会静默丢掉真问题。
 *
 * <p><b>fail-open</b>：未启用 / 不可达 / 超时 → 原样返回全部候选。过滤层绝不能成为单点故障。
 *
 * <p><b>延迟闸门</b>：单次请求最多送检 {@code mo.semantic.top-n} 条，超出的直接保留。
 * 详情页多 0.6s 可接受，但绝不能随候选量线性增长（风险扫描单次最多 200 条候选）。
 *
 * <p><b>并行送检</b>（十万级压测后改，2026-10-12）：候选的上下文获取（stateOf，可能查库）
 * 与闸门内送检都并行发，并发度 = {@code mo.semantic.concurrency}（固定池本身就是上限，
 * 护住单进程 laya）；总耗时从 N×单条往返降到约一轮往返。kept 仍保持 hits 原序，
 * 单条失败仍 fail-open 保留。
 */
@Slf4j
@Service
public class SemanticFilterService implements SemanticConfirmPort {

    private final LayaClient client;
    private final SemanticProperties props;
    /** 对 laya 的实并发上限：IO 阻塞型固定池，池大小就是闸门外的第二道闸门
     *  （跨类目并行时共用同一个池，护住单进程推理后端）；daemon 不阻关机。 */
    private final ExecutorService llmPool;

    public SemanticFilterService(LayaClient client, SemanticProperties props) {
        this.client = client;
        this.props = props;
        int concurrency = Math.max(1, props.getConcurrency());
        this.llmPool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "mo-llm");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** 后端此刻是否可用（供 UI 展示与健康检查）。 */
    public boolean isBackendReady() {
        return props.isEnabled() && client.healthy();
    }

    /**
     * 对一批候选做语义确认并剔除假阳性。
     *
     * @param hits    确定层产出的候选（会被就地打上语义标记，但不会删除原对象）
     * @param kind    场景（决定阈值与问题措辞）
     * @param stateOf 把一条候选映射为 laya 的 state；返回 null 表示该条无法送检（将直接保留）
     * @return 保留项 + 统计
     */
    public Outcome filter(List<Map<String, Object>> hits,
                          ConfirmKind kind,
                          Function<Map<String, Object>, Map<String, Object>> stateOf) {
        if (hits == null || hits.isEmpty()) {
            return Outcome.skipped(hits);
        }
        if (!props.isEnabled()) {
            return Outcome.disabled(hits);
        }
        if (!client.healthy()) {
            log.warn("[laya] 后端不可达，本次跳过语义过滤（全部保留，fail-open）");
            return Outcome.unreachable(hits);
        }

        int limit = Math.min(props.getTopN(), hits.size());
        String qid = PromptLibrary.primaryQid(kind);
        String classifyQid = PromptLibrary.classifyQid(kind);
        Map<String, Object> questions = PromptLibrary.of(kind);
        String langHint = kind.langHint();
        double threshold = thresholdOf(kind);

        // 阶段 1（并行取上下文 + 顺序定闸门）：stateOf 可能查库（queryTurnText），负载下
        // 单条百毫秒级——串行会变成并行化后的新瓶颈。先并发把上下文取回来，再按
        // 首次出现顺序切闸门（top-n 按实际送检数计），切法与串行版逐条同位。
        List<CompletableFuture<Map<String, Object>>> stateFuts = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            stateFuts.add(CompletableFuture.supplyAsync(() -> stateOf.apply(hit), llmPool));
        }
        List<Map<String, Object>> states = new ArrayList<>(hits.size());
        int toSend = 0;
        boolean truncated = false;
        for (CompletableFuture<Map<String, Object>> f : stateFuts) {
            Map<String, Object> state = f.join();
            if (state == null || state.isEmpty()) {
                states.add(null);      // 该候选不参与语义判断 → 保留
            } else if (toSend >= limit) {
                states.add(null);      // 闸门外的 → 保留
                truncated = true;
            } else {
                states.add(state);
                toSend++;
            }
        }

        // 阶段 2：闸门内候选一次并行发 LayaClient（线程安全，JDK HttpClient）。
        // 失败/中断收敛为 null 结果，阶段 3 按 fail-open 保留——单条故障不拖整批。
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>(hits.size());
        for (Map<String, Object> state : states) {
            if (state == null) {
                futures.add(null);
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return client.predict(state, questions, langHint);
                } catch (Exception e) {
                    log.warn("[laya] 单条确认失败，保留该候选：{}", e.getMessage());
                    return null;
                }
            }, llmPool));
        }

        // 阶段 3：按 hits 原序合并判定（剔除/标记/计数与串行版同位）。
        List<Map<String, Object>> kept = new ArrayList<>(hits.size());
        int removed = 0;
        int confirmed = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();

        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> hit = hits.get(i);
            if (states.get(i) == null) {
                kept.add(hit);
                continue;
            }
            JsonNode result = futures.get(i).join();
            if (result == null) {
                kept.add(hit);                     // fail-open：送检失败保留
                continue;
            }
            double pTrue = LayaClient.noul(result, qid);
            double pFalse = 1.0 - pTrue;      // ← 唯一的取反点，勿漏
            confirmed++;

            if (pFalse >= threshold) {
                removed++;
                log.debug("[laya] 剔除疑似假阳性 kind={} pFalse={} model={}",
                        kind, round(pFalse), LayaClient.routedModel(result));
                continue;
            }

            // 分类结果：同一次 predict 里的第二个问题，不额外花推理。
            // 放在剔除判定之后——被剔除的假阳性不写分类字段，统计也只计保留项。
            String category = classifyQid == null ? "" : LayaClient.choice(result, classifyQid);
            if (!category.isBlank()) {
                hit.put("semanticCategory", category);
                hit.put("semanticCategoryConfidence",
                        round(LayaClient.confidence(result, classifyQid)));
                Integer prev = kindCounts.get(category);
                kindCounts.put(category, prev == null ? 1 : prev + 1);
            }
            hit.put("semanticPFalse", round(pFalse));
            hit.put("semanticModel", LayaClient.routedModel(result));
            kept.add(hit);
        }

        log.info("[laya] 语义过滤完成 kind={} 候选={} 送检={} 剔除={} 保留={} 阈值={}",
                kind, hits.size(), confirmed, removed, kept.size(), threshold);
        return Outcome.of(kept, removed, confirmed, hits.size(), truncated, kindCounts);
    }

    /** 契约端口实现：把 {@link Outcome} 收窄成端口约定的 {@link Result}（hits + 已按 kind 组装好的 meta）。 */
    @Override
    public Result confirm(List<Map<String, Object>> hits, ConfirmKind kind,
                          Function<Map<String, Object>, Map<String, Object>> stateOf) {
        Outcome outcome = filter(hits, kind, stateOf);
        return new Result(outcome.hits(), outcome.meta(kind));
    }

    private double thresholdOf(ConfirmKind kind) {
        return switch (kind) {
            case RISK -> props.getThresholdRisk();
            case PROBLEM -> props.getThresholdProblem();
            case LOOP -> props.getThresholdLoop();
        };
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ==================== 结果 ====================

    /**
     * 过滤结果。{@code status} 让上层（与前端）能区分「真的过滤了」和「因为故障跳过了」——
     * 后者不应该被当成「没有问题」。
     *
     * @param categoryCounts 保留命中按分类标签的分布（如 {@code {"pathological_loop": 3}}）。
     *                       未分类场景（LOOP）或后端未返回时为空表，故 {@code meta} 里
     *                       {@code classified=false} 表示「这次没有分类结果」，而不是「全是 0」。
     */
    public record Outcome(List<Map<String, Object>> hits,
                          int removed,
                          int confirmed,
                          int candidateTotal,
                          boolean truncated,
                          String status,
                          Map<String, Integer> categoryCounts) {

        public Outcome(List<Map<String, Object>> hits, int removed, int confirmed,
                       int candidateTotal, boolean truncated, String status) {
            this(hits, removed, confirmed, candidateTotal, truncated, status, Map.of());
        }

        public static Outcome skipped(List<Map<String, Object>> hits) {
            return new Outcome(hits == null ? List.of() : hits, 0, 0, 0, false, "empty");
        }

        public static Outcome disabled(List<Map<String, Object>> hits) {
            return new Outcome(hits, 0, 0, hits.size(), false, "disabled");
        }

        public static Outcome unreachable(List<Map<String, Object>> hits) {
            return new Outcome(hits, 0, 0, hits.size(), false, "unreachable");
        }

        public static Outcome of(List<Map<String, Object>> hits, int removed, int confirmed,
                                 int total, boolean truncated, Map<String, Integer> categoryCounts) {
            return new Outcome(hits, removed, confirmed, total, truncated, "ok", categoryCounts);
        }

        /** 供 REST 层直接塞进响应的元数据。 */
        public Map<String, Object> meta(ConfirmKind kind) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name().toLowerCase());
            m.put("status", status);
            m.put("candidateTotal", candidateTotal);
            m.put("confirmed", confirmed);
            m.put("removed", removed);
            m.put("kept", hits.size());
            m.put("truncated", truncated);
            m.put("classified", !categoryCounts.isEmpty());
            // 按命中数降序，前端直接顺序渲染即可
            Map<String, Integer> sorted = new LinkedHashMap<>();
            categoryCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            m.put("categories", sorted);
            return m;
        }
    }
}
