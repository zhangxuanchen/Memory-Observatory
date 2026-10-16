package io.memobservatory.server.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警推送配置（mo.notify.*）。
 *
 * <p>默认关、默认无 URL：没配 webhook 的部署启动后不会报错，也不会推任何东西。
 * 要启用至少需要同时设 {@code enabled=true} 与 {@code webhook-url=<非空>}。
 */
@Data
@ConfigurationProperties(prefix = "mo.notify")
public class NotifyProperties {

    /** 总开关。默认关——推送是有副作用的动作，必须显式打开。 */
    private boolean enabled = false;

    /** webhook 地址。留空即停用（与 enabled 是「且」的关系）。 */
    private String webhookUrl = "";

    /** 轮询间隔（毫秒）。默认 5 分钟——问题判定本身是分钟级现象，不需要更密。 */
    private long pollIntervalMs = 300000;

    /** 每次评估的时间窗口（天）。 */
    private int windowDays = 1;

    /**
     * 阈值与风险两类源是否走语义过滤。
     *
     * <p>默认开：告警恰好是假阳性代价最高的地方，先推未过滤的候选，
     * 结果通常是很快被人关掉通知。代价是每轮多花 laya 调用，用既有的 {@code mo.semantic.top-n}
     * 闸门兜住。实测收益有限时（见 {@code mo.semantic.threshold-risk} 的说明）可以关掉。
     */
    private boolean semantic = true;

    /** 同一去重键的冷却窗口（分钟）。窗口内不重复推送，只刷新 last_seen_at。 */
    private long cooldownMinutes = 360;

    /** 每轮最多推几条。按严重度优先，这本身就是告警风暴的上限。 */
    private int maxPerRound = 20;

    /** 载荷里最多带几条命中明细。 */
    private int topHits = 5;

    /** 低于此级别不推。取值 critical / high / medium。未知值按 medium 处理。 */
    private String minSeverity = "medium";

    /** 静默时段，如 {@code "22-8"}（22 点到次日 8 点）；留空表示不静默。只支持整点区间。 */
    private String quietHours = "";

    /** 单轮最多评估几个 agent。语义过滤开启时，耗时随该值增长。 */
    private int agentsLimit = 200;

    /** 单次 webhook 请求超时（毫秒）。 */
    private int timeoutMs = 5000;

    /** webhook 连接超时（毫秒）。 */
    private int connectTimeoutMs = 2000;

    /**
     * 恢复通知开关，首版默认关。
     *
     * <p>状态机已经会记录 recovered，但默认不发送——首版先不制造通知噪音。
     */
    private boolean pushRecovery = false;
}