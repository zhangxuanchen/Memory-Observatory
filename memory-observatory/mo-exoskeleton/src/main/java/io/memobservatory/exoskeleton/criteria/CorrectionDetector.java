package io.memobservatory.exoskeleton.criteria;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 隐式纠正判定（§2.4）——唯一接近「用户说错了」的信号。
 *
 * 定义：同一 session 内相邻两个 turn，用户问题高度相似，<b>且上一轮有困顿迹象</b>，且间隔在窗口内
 * （间隔由调用方按 {@code mo.exoskeleton.correction-window-minutes} 过滤，本类只管文本与迹象）。
 *
 * §2.4 要求必须区分三种形似情形，本类逐条对应：
 *
 * <table>
 *   <tr><td>上一轮做错了，换个说法重问</td><td><b>是</b></td><td>相似 + {@link #hasDistress} 为真</td></tr>
 *   <tr><td>用户想补充新要求</td><td>不是</td><td>长度明显增加（&gt;1.5×）在粗筛②被挡掉</td></tr>
 *   <tr><td>换了话题但句式相近</td><td>不是</td><td>关键词集合几乎不重叠，Jaccard 挡掉</td></tr>
 * </table>
 *
 * 首版只取第一种，<b>宁可漏报不可误报</b>——这个信号要当量纲用，掺假比稀疏更糟（§2.4）。
 *
 * 两段式的理由：{@code user_text} 是中文长文本，直接上编辑距离是 O(n²) 且对噪声敏感；
 * 粗筛把候选量压到可算的量级，精算只跑通过粗筛的那一小撮。
 *
 * 已知局限：困顿迹象是 v1 代理，只覆盖「循环」与「失败」两种，§2.4 提到的「写后速忘」尚未接
 * （需要 op 时序，属 B4 埋点补强的范围）。代理只用真实存在的列，不假装语义。
 */
@Component
public class CorrectionDetector {

    /** 同一工具在一轮里重复到这个次数，判为循环迹象。 */
    private static final int LOOP_REPEAT = 3;

    /** 失败迹象词表：粗但可解释，且方向明确（宁可漏报）。 */
    private static final Set<String> FAILURE_WORDS = Set.of(
            "失败", "报错", "错误", "异常", "未完成", "无法", "不支持", "超时", "有误", "不对",
            "error", "failed", "fail", "exception", "timeout");

    private final double similarityThreshold;

    public CorrectionDetector(@Value("${mo.exoskeleton.correction-similarity:0.75}") double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /** 上一轮的可观测状态，用于判断它是否「卡住了」。 */
    public record PrevTurn(String userText, String[] actions, String outcome) {
    }

    /**
     * 后一轮是否构成对前一轮的隐式纠正。
     *
     * @param prev       前一个 turn
     * @param nextText   后一个 turn 的用户提问
     * @return 三个条件同时成立：文本相似 + 前一轮有困顿迹象
     */
    public boolean isCorrection(PrevTurn prev, String nextText) {
        if (prev == null || nextText == null || nextText.isBlank()) {
            return false;
        }
        if (!similar(prev.userText(), nextText)) {
            return false;
        }
        return hasDistress(prev);
    }

    // ------------------------------------------------------------------
    // 文本相似：粗筛两道 → 精算 Jaccard
    // ------------------------------------------------------------------

    private boolean similar(String prev, String next) {
        if (prev == null || prev.isBlank()) {
            return false;
        }
        // 粗筛①：共同前缀 ≥12 字（同一件事的复述，开头几乎必然相同）
        // 粗筛②：长度比落在 [0.5, 1.5]——上界同时承担「补充新要求」的排除（§2.4：长度明显增加即非纠正）
        boolean coarse = commonPrefixLen(prev, next) >= 12
                || lengthRatioBetween(prev.length(), next.length(), 0.5, 1.5);
        if (!coarse) {
            return false;
        }
        return jaccard(bigrams(prev), bigrams(next)) >= similarityThreshold;
    }

    /**
     * 上一轮的困顿迹象（§2.4：失败 / 循环 / 写后速忘）。
     * 两条已接：循环（同一工具重复 ≥{@link #LOOP_REPEAT} 次）、失败（outcome 命中词表）。
     */
    private static boolean hasDistress(PrevTurn prev) {
        return hasLoopSign(prev.actions()) || hasFailureSign(prev.outcome());
    }

    /** 循环迹象：同一工具在一轮里被反复调用，是「卡住打转」的直接观测量。 */
    private static boolean hasLoopSign(String[] actions) {
        if (actions == null || actions.length == 0) {
            return false;
        }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String a : actions) {
            if (a == null || a.isBlank()) {
                continue;
            }
            int c = counts.getOrDefault(a, 0) + 1;
            if (c >= LOOP_REPEAT) {
                return true;
            }
            counts.put(a, c);
        }
        return false;
    }

    /** 失败迹象：outcome 是自由文本，只能做词表匹配；漏报可接受，误报不可。 */
    private static boolean hasFailureSign(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return false;
        }
        String lower = outcome.toLowerCase();
        for (String w : FAILURE_WORDS) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 文本工具（自 RuleRepository 迁入：相似度只应有一个真相来源）
    // ------------------------------------------------------------------

    private static int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static boolean lengthRatioBetween(int a, int b, double lo, double hi) {
        if (a == 0 || b == 0) {
            return false;
        }
        double ratio = (double) b / a;
        return ratio >= lo && ratio <= hi;
    }

    /** 字符 n-gram（n=2；长度不足 2 时退化为单字），中文无需分词。 */
    private static Set<String> bigrams(String s) {
        Set<String> grams = new HashSet<>();
        if (s.length() < 2) {
            grams.add(s);
            return grams;
        }
        for (int i = 0; i + 2 <= s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
