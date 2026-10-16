package io.memobservatory.exoskeleton.skills;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 证据摘要与指纹：把一串用户提问压进预算、再算一个「这份证据变没变」的指纹。
 *
 * <p><b>为什么单独抽出来</b>：技能提炼（{@link SkillDiscoverer}）与事实提炼（{@link FactDiscoverer}）
 * 都要做同一件事——按 turn 均匀抽样把对话压进 {@code digest-max-chars}，再对摘要取 SHA-256 当
 * 「同一份证据」的判据。两处各写一份，就会有两套「预算到底扣没扣前缀」的口径（{@code B14.4} 的教训），
 * 故收敛到一处，谁用谁调。
 */
final class EvidenceDigest {

    /** 均匀抽样时，每条 turn 至少要留这么多字符，否则宁可少取几条 turn。 */
    private static final int MIN_CHARS_PER_TURN = 200;

    private EvidenceDigest() {
    }

    /**
     * 把一串文本按 turn 均匀抽样压进 {@code maxChars}（与 {@code TaskClassifier} 同法），
     * 代表性覆盖整串。放不下时按步长隔条取，首尾必取。
     *
     * <p><b>预算必须真的兑现</b>：每条前面那截 {@code [3/12] } 和分隔换行也是字符。
     * 只拿「正文之和」跟预算比，长串会一路超出去——实测 {@code refactor} 575 条时摘要实得 23890 字、
     * 预算却是 20000（多出来的正是 575 条各自的前缀）。故这里先把骨架开销扣掉再分配正文预算。
     */
    static String render(List<String> texts, int maxChars) {
        if (texts == null || texts.isEmpty()) {
            return "";
        }
        int n = texts.size();
        long total = 0;
        for (String t : texts) {
            total += t.length() + 1;
        }
        List<Integer> all = indexes(n, 1);
        int allOverhead = overhead(all, n);
        if (total + allOverhead <= maxChars) {
            return render(texts, all, Math.max(1, maxChars - allOverhead), maxChars);
        }
        int maxTurns = Math.max(1, maxChars / MIN_CHARS_PER_TURN);
        if (n <= maxTurns) {
            return render(texts, all, Math.max(1, (maxChars - allOverhead) / n), maxChars);
        }
        int step = (int) Math.ceil((double) n / maxTurns);
        List<Integer> idx = indexes(n, step);
        int overhead = overhead(idx, n);
        return render(texts, idx, Math.max(1, (maxChars - overhead) / idx.size()), maxChars);
    }

    /** 选中条目自带的骨架开销：每条一个形如 {@code [3/12] } 的前缀，外加条目之间那一个换行。 */
    private static int overhead(List<Integer> idx, int n) {
        int digitsN = Integer.toString(n).length();
        int sum = 0;
        for (int k = 0; k < idx.size(); k++) {
            sum += 4 + Integer.toString(idx.get(k) + 1).length() + digitsN;  // "[" + i + "/" + n + "] "
            if (k > 0) {
                sum += 1;                                                    // 前缀前的分隔换行
            }
        }
        return sum;
    }

    private static List<Integer> indexes(int n, int step) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            idx.add(i);
        }
        if (idx.get(idx.size() - 1) != n - 1) {
            idx.add(n - 1);
        }
        return idx;
    }

    private static String render(List<String> texts, List<Integer> idx, int perTurn, int maxChars) {
        int n = texts.size();
        // perTurn 已经是「扣掉前缀与换行之后的正文预算」（见 render），这里不再另留余量
        int cap = Math.max(1, perTurn);
        StringBuilder sb = new StringBuilder(Math.min(maxChars + 64, 8192));
        for (int i : idx) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(i + 1).append('/').append(n).append("] ").append(clipOne(texts.get(i), cap));
        }
        return sb.toString();
    }

    /**
     * 把一条正文裁到 {@code limit} 字：留头留尾、中间打省略号。
     *
     * <p><b>省略号也占一个字</b>：头 {@code head} + 省略号 1 + 尾 {@code limit-head-1}，
     * 合计恰好 {@code limit}。若像从前那样「头 + 省略号 + 尾(limit-head)」，每条被裁的正文会
     * 多出 1 个字，长串一多整体就冲出预算（实测 575 条时超 59 字，与 B14.4 同源）。
     */
    private static String clipOne(String s, int limit) {
        if (s.length() <= limit) {
            return s;
        }
        int head = (int) (limit * 0.7);
        int tail = Math.max(0, limit - head - 1);
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    /**
     * 内容指纹：这份技能的「本体」变没变（{@code name + prompt + tools}）。
     *
     * <p><b>与</b>{@link #sha256(String)}<b>（证据指纹）的分工</b>：证据指纹判「喂进去的对话有没有变」，
     * 内容指纹判「提炼出来的东西有没有变」。两者必须分开——证据涨了（又多了几十条同类对话）、
     * 但提炼结果一字未改时，不该算「技能演进」，更不该刷出一个新版本（防噪旋钮之一）。
     *
     * <p>工具顺序算进指纹：同一组工具换个次序，落盘 yaml 的字段就变了，是实打实的内容变化。
     */
    static String contentHash(String name, String prompt, List<String> tools) {
        String t = (tools == null || tools.isEmpty()) ? "" : String.join(",", tools);
        return sha256((name == null ? "" : name) + "\n" + (prompt == null ? "" : prompt) + "\n" + t);
    }

    /** 证据指纹：SHA-256(input)。变了才允许把已「不用」的类重新提上来。 */
    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}