package io.memobservatory.server.semantic;

/**
 * 三类语义确认场景。
 *
 * <p>三者的<b>漏报代价完全不同</b>，因此阈值、问题措辞、激进程度都不同——
 * 不要为了省事共用一套阈值：
 * <ul>
 *   <li>{@link #RISK} 漏报 = 真实安全事故且不可逆 → 最保守</li>
 *   <li>{@link #PROBLEM} 漏报 = 少发现一个优化点 → 可激进</li>
 *   <li>{@link #LOOP} 仅在流程分析 reasonType="loop" 上使用</li>
 * </ul>
 */
public enum ConfirmKind {
    /** 内容风险监测：这条命中是真凭据泄漏，还是示例 / 占位符？ */
    RISK(null),
    /** 问题分析：这次超阈值是真问题，还是任务本身就需要这么多资源？ */
    PROBLEM("zh"),
    /** 流程分析：这次重复是病态空转，还是正常的重试 / 增量推进？ */
    LOOP("zh");

    private final String langHint;

    ConfirmKind(String langHint) {
        this.langHint = langHint;
    }

    /**
     * 送检时显式声明的语言族（{@code null} = 交回 Router 自动探测）。
     *
     * <p>依据是<b>「被判内容本身是什么语言」，不是「状态里有没有中文」</b>——这是两个不同的判断：
     * <ul>
     *   <li>PROBLEM / LOOP 的被判内容是 {@code turn_user} / {@code summary} 这类<b>中文叙述</b>，
     *       而状态里同时混着英文标识符（skill 名 / 文件名 / 字段名）。ASCII 字母占比过半时
     *       Router 会按脚本探测把整个状态判成 latin → 送进读不了中文的 english checkpoint
     *       （实测 han 占 28.6% 即触发），所以必须显式声明。</li>
     *   <li>RISK 的被判内容是 {@code snippet}——<b>被判的代码 / 配置片段</b>，中文只出现在
     *       {@code rule_name} 这种短标签里。实测这类片段交给 english 更准（方向性 2/3，
     *       改走 multilingual 后降到 1/3，且 P(真) 一律抬到 0.9 附近、概率失去信息量），
     *       故不声明、让 Router 自己探测：英文片段走 english，中文片段它也能自己识别。</li>
     * </ul>
     */
    public String langHint() {
        return langHint;
    }
}
