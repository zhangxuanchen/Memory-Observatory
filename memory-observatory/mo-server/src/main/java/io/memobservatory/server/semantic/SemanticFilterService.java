package io.memobservatory.server.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Slf4j
@Service
public class SemanticFilterService {

    private final LayaClient client;
    private final SemanticProperties props;

    public SemanticFilterService(LayaClient client, SemanticProperties props) {
        this.client = client;
        this.props = props;
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

        List<Map<String, Object>> kept = new ArrayList<>(hits.size());
        int removed = 0;
        int confirmed = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();

        for (Map<String, Object> hit : hits) {
            Map<String, Object> state = stateOf.apply(hit);
            if (state == null || state.isEmpty()) {
                // 该候选不参与语义判断（例如强格式规则命中、或缺可判断的内容）→ 保留
                kept.add(hit);
                continue;
            }
            if (confirmed >= limit) {
                // 延迟闸门按「实际送检数」计，避免前面不可送检的候选吃掉额度
                kept.add(hit);
                continue;
            }
            try {
                JsonNode result = client.predict(state, questions, langHint);
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
            } catch (Exception e) {
                log.warn("[laya] 单条确认失败，保留该候选：{}", e.getMessage());
                kept.add(hit);                     // 单条失败不影响整批
            }
        }

        log.info("[laya] 语义过滤完成 kind={} 候选={} 送检={} 剔除={} 保留={} 阈值={}",
                kind, hits.size(), confirmed, removed, kept.size(), threshold);
        return Outcome.of(kept, removed, confirmed, hits.size(), confirmed >= limit, kindCounts);
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
