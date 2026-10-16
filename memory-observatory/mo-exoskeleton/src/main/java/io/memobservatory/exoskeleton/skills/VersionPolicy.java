package io.memobservatory.exoskeleton.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 版本演进判决（登记口 B18.1）：已采纳的技能在证据变化后要不要出新版。
 *
 * <p><b>为什么从前出不来新版</b>：{@link SkillProposalRepository#store} 对 {@code ADOPTED} 直接早退——
 * 「技能已装进工作台，别再打扰」。这在「人已拍板」这层是对的，却把「技能永远停在采纳那一刻的样子」
 * 一并固化了：证据长了、做法变了，库里那份 yaml 还是老的。本类只判「该不该出新版」，落库由 store 执行。
 *
 * <p><b>为什么是纯判决</b>：仿 {@code PromotionGate}——不碰数据库、不改状态、无随机、无时间依赖，
 * 同一输入永远同一判决。这样「什么时候算演进」这条口径能被单独测、单独改，不与落库纠缠。
 *
 * <p><b>两个条件同时满足才出新版</b>：
 * <ol>
 *   <li><b>内容真的变了</b>（{@code contentHash} 不同）——只有证据涨了、提炼结果一字未改时，
 *       不算演进（否则同一份内容会被反复刷版本）；</li>
 *   <li><b>证据确有增长</b>（{@code turns >= 既有turns + 阈值}）——这是<b>唯一</b>的防噪旋钮：
 *       内容因措辞漂移而微变、但没多几条对话时，压住不出新版，等下轮证据够厚再说。</li>
 * </ol>
 * 缺任一条件都返回 {@link #KEEP}——宁可少打扰，不可拿噪声刷版本。
 *
 * <p>阈值走配置 {@code mo.exoskeleton.skills.version-min-new-turns}（默认 10）。注意：这与
 * {@code min-turns}（样本门槛，默认 30）是两回事——后者管「一类够不够格被提炼」，
 * 前者管「已提炼的技能要涨够多少条才值得出新版」。
 */
@Service
public class VersionPolicy {

    /** 保持原状：不出新版。 */
    public static final String KEEP = "KEEP";
    /** 出新版：写 v+1、旧版置 SUPERSEDED、提议行仍 ADOPTED。 */
    public static final String REVISE = "REVISE";

    private final int minNewTurns;

    public VersionPolicy(@Value("${mo.exoskeleton.skills.version-min-new-turns:10}") int minNewTurns) {
        this.minNewTurns = minNewTurns;
    }

    /**
     * 一次判决的结果。
     *
     * @param outcome {@link #KEEP} 或 {@link #REVISE}
     * @param reason  中文一句话，说明为什么这么判（落日志 / 落事件 detail 用）
     */
    public record Decision(String outcome, String reason) {
        public boolean revise() {
            return REVISE.equals(outcome);
        }
    }

    /**
     * 判「已采纳的技能该不该出新版」。
     *
     * @param existingContentHash 当前版本内容的指纹（{@link EvidenceDigest#contentHash}）
     * @param newContentHash      本轮提炼内容的指纹
     * @param existingTurns       当前版本提炼时依据的 turn 数
     * @param newTurns            本轮提炼时依据的 turn 数
     */
    public Decision decide(String existingContentHash, String newContentHash,
                           long existingTurns, long newTurns) {
        if (existingContentHash.equals(newContentHash)) {
            return new Decision(KEEP, "内容未变（仅证据增长），不出新版。");
        }
        long need = existingTurns + minNewTurns;
        if (newTurns < need) {
            return new Decision(KEEP, "内容变了但证据增长不足（" + newTurns + " 条 < 需 " + need
                    + " 条 = 既有 " + existingTurns + " + 阈值 " + minNewTurns + "），暂不出新版。");
        }
        return new Decision(REVISE, "内容变化 + 证据增长（" + existingTurns + " → " + newTurns
                + " 条，阈值 " + minNewTurns + "），出新版。");
    }
}