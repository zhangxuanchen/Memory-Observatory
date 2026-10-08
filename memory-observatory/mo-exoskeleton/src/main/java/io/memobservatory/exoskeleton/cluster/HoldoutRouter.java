package io.memobservatory.exoskeleton.cluster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * holdout 路由器（§2.5 / §4.6）：决定一个 turn 进不进对照组。
 *
 * 三条设计约束：
 * 1. <b>确定性</b>：同一 (agentId, turnId) 永远得到同一分组。用随机数会让「对照组」每次查询都换人，
 *    对照就不成立；用哈希才能既抽查又不重复计算。分片基数固定 10000，比例配多少就切多少。
 * 2. <b>与 turn 内容无关</b>：只按 id 哈希，不看任务难度、不看向量。若按内容分配，对照组会系统性偏向
 *    某类任务，对照组本身就带上了选择偏差（这正是 §2.5 要避免的）。
 * 3. <b>优先于一切</b>：{@code isHoldout} 为真时永不注入规则，连影子记录也只标 {@code would}（§4.6 第 1 条）。
 *
 * 已知局限（诚实记于设计文档 B3.3）：真实环境里的 holdout 不是随机化——命中簇与未命中簇的任务难度
 * 本就不同。本类是「可复现的抽样」，不是「随机对照试验」，两者不能混为一谈。
 */
@Component
public class HoldoutRouter {

    /** 分片基数：比例按此粒度切，避免浮点累计误差。 */
    private static final int SLICES = 10_000;

    private final double ratio;
    private final int threshold;

    public HoldoutRouter(@Value("${mo.exoskeleton.holdout-ratio:0.2}") double ratio) {
        this.ratio = ratio;
        this.threshold = (int) Math.round(ratio * SLICES);
    }

    /** 该 turn 是否进对照组。 */
    public boolean isHoldout(String agentId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return false;
        }
        return Math.floorMod(fingerprint(agentId, turnId), SLICES) < threshold;
    }

    /** 配置的裸跑比例。 */
    public double ratio() {
        return ratio;
    }

    /** 分配依据（可审计，随响应返回）。 */
    public String basis() {
        return "is_holdout = SHA-256(agent_id + '\\u0000' + turn_id) 取模 " + SLICES
                + " < " + threshold + "（比例 " + ratio + "）；确定性、与 turn 内容无关、可复现。";
    }

    /**
     * 稳定指纹：SHA-256 前 8 字节。
     * 不用 String.hashCode——它对结构相近的 id（同一 agent 的连续 turn）分布不均，会让对照组扎堆。
     */
    private static long fingerprint(String agentId, String turnId) {
        String key = (agentId == null ? "" : agentId) + '\u0000' + turnId;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[i] & 0xFFL);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);   // JDK 必备算法，不会发生
        }
    }
}
