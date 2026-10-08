package io.memobservatory.server.risk;

import io.memobservatory.server.semantic.ConfirmKind;
import io.memobservatory.server.semantic.SemanticFilterService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容风险监测：扫描会话内容（memory_summary / 用户输入）是否透出敏感信息。
 *
 * 覆盖：云厂商 AK、API Key、GitHub/Slack/Google Token、Bearer Token、JWT、
 * 私钥、明文密码、账号密码对、数据库连接串（含凭据）、通用密钥赋值。
 * 流程：SQL ILIKE 预过滤（缩窄候选集）→ Java 正则逐条确认 → 脱敏输出。
 */
@Service
public class RiskScanService {

    private final JdbcTemplate jdbc;

    /** 单条敏感规则：type=分组键，name=展示名，severity=critical/high/medium。 */
    private record Rule(String type, String name, String severity, Pattern p) {}

    private static final List<Rule> RULES = List.of(
        new Rule("cloud-ak",  "云访问密钥 AK",          "critical", Pattern.compile("(LTAI[0-9A-Za-z]{10,30}|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16})")),
        new Rule("api-key",   "API Key（sk-）",         "critical", Pattern.compile("sk-[A-Za-z0-9_-]{16,}")),
        new Rule("github",    "GitHub Token",           "critical", Pattern.compile("gh[posu]_[A-Za-z0-9]{30,}")),
        new Rule("slack",     "Slack Token",            "critical", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")),
        new Rule("google",    "Google API Key",         "critical", Pattern.compile("AIza[0-9A-Za-z_-]{30,}")),
        new Rule("private-key","私钥内容",              "critical", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
        new Rule("bearer",    "Bearer Token",           "high",     Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=-]{15,}")),
        new Rule("jwt",       "JWT 令牌",               "high",     Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")),
        // password/cred-pair/secret 规则用 (?!\\$\\{) 排除「值 = 环境变量占位符 ${...}」的情况：
        // 配置模板里 password: ${MO_DB_PASSWORD:mo} 是引用而非真实明文，不算泄漏。
        new Rule("password",  "明文密码字段",           "high",     Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:：]\\s*[\"']?(?!\\$\\{)[^\\s\"'&<>{}$]{3,}")),
        new Rule("cred-pair", "账号密码组合",           "high",     Pattern.compile("(?i)(user(name)?|account)\\s*[=:：]\\s*[\"']?(?!\\$\\{)[^\\s\"',;&<>]{2,}[\"']?\\s*[,;，；]\\s*(password|passwd|pwd)\\s*[=:：]")),
        new Rule("db-conn",   "数据库连接串（含凭据）", "high",     Pattern.compile("(?i)(jdbc:\\w+://|(mysql|postgres(?:ql)?|mongodb(?:\\+srv)?|redis|amqp)://)(?!\\$\\{)\\S+:\\S+@")),
        new Rule("secret",    "通用密钥赋值",           "medium",   Pattern.compile("(?i)(api[_-]?key|secret|access[_-]?key|token)\\s*[=:：]\\s*[\"']?(?!\\$\\{)[A-Za-z0-9_./+=-]{8,}"))
    );

    /** SQL 预过滤关键字（ILIKE ANY 数组），只做粗筛，命中后仍需正则确认。 */
    private static final String[] LIKE_KEYS = {
        "LTAI","AKIA","ASIA","sk-","ghp_","gho_","ghu_","ghs_","xox","AIza",
        "Bearer","eyJ","BEGIN","password","passwd","pwd","api_key","apiKey",
        "api-key","secret","access_key","accessKey","token","jdbc:","mysql://",
        "postgres://","mongodb://","redis://","amqp://","user","account"
    };

    /**
     * 弱格式风险规则：靠「关键字 + 值形状」匹配，示例代码 / 占位符 / 文档 / 测试夹具都会命中，
     * 是假阳性主要来源。强格式规则（云 AK / sk- / ghp_ / 私钥 / JWT 等）零误报，不参与语义过滤。
     */
    private static final Set<String> WEAK_RISK_TYPES =
            Set.of("password", "cred-pair", "db-conn", "secret");

    private final SemanticFilterService semanticFilter;

    public RiskScanService(JdbcTemplate jdbc, SemanticFilterService semanticFilter) {
        this.jdbc = jdbc;
        this.semanticFilter = semanticFilter;
    }

    /** 不做语义过滤（等价于 {@code scan(agentId, days, false)}）。 */
    public Map<String, Object> scan(String agentId, int days) {
        return scan(agentId, days, false);
    }

    /**
     * 扫描指定 Agent 近 N 天的会话内容，返回命中列表与分类统计。
     * hits 按时间倒序，最多 200 条；每个事件最多记录 3 个命中。
     *
     * <p>{@code semantic=true} 时，对弱格式规则的命中做语义确认，剔除「示例 / 占位符 / 文档 /
     * 测试夹具」这类假阳性；强格式规则的命中原样保留。后端不可达时自动跳过过滤并原样返回
     * （fail-open），响应里的 {@code semantic.status} 会说明是「真的过滤了」还是「因故障跳过了」。
     *
     * <p><b>为什么默认关</b>：实测（见 LAYA-INTEGRATION.md 附·实测记录 C）在当前保守阈值
     * {@code threshold-risk=0.95} 下，送检候选一条都剔不掉——假阳性的 pFalse 只有 0.806，
     * 达不到 0.95。也就是说默认开会白付最多 20 次推理（3~7s 延迟）而收益为 0。
     * 而本场景漏报不可逆，不能为了收益贸然下调阈值。
     */
    public Map<String, Object> scan(String agentId, int days, boolean semantic) {
        Map<String, Object> result = scanRaw(agentId, days);
        if (!semantic) {
            return result;
        }
        Object raw = result.get("hits");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return result;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) list;
        var outcome = semanticFilter.filter(hits, ConfirmKind.RISK, RiskScanService::riskState);
        result.put("hits", outcome.hits());
        result.put("total", outcome.hits().size());
        result.put("semantic", outcome.meta(ConfirmKind.RISK));
        return result;
    }

    /** 把一条风险命中映射为 laya 的 state；返回 null 表示该条不参与语义判断（直接保留）。 */
    private static Map<String, Object> riskState(Map<String, Object> hit) {
        if (!WEAK_RISK_TYPES.contains(str(hit.get("type")))) {
            return null;    // 强格式规则零误报，不动
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("snippet", truncate(str(hit.get("snippet")), 600));
        state.put("rule_name", str(hit.get("name")));
        state.put("field", str(hit.get("field")));
        return state;
    }

    /**
     * 截断到 laya 可处理的长度。
     * laya 的 max_len 为 512/1024 token，超出部分会在 build_sequence 里被静默截断——
     * 那会悄悄丢掉判断所需的上下文，所以宁可自己显式截断。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 只跑规则，不做语义过滤。 */
    private Map<String, Object> scanRaw(String agentId, int days) {
        Instant from = days > 0 ? Instant.now().minus(days, ChronoUnit.DAYS) : Instant.EPOCH;
        // ILIKE ANY 预过滤：memory_summary 或用户输入含任一关键字
        StringBuilder likes = new StringBuilder();
        for (int i = 0; i < LIKE_KEYS.length; i++) {
            if (i > 0) likes.append(',');
            likes.append('\'').append('%').append(LIKE_KEYS[i]).append('%').append('\'');
        }
        String sql = "SELECT event_id, session_id, trace_id, ts, layer, operation, memory_summary, " +
            "metadata->>'turn_user' AS turn_user FROM memory_events " +
            "WHERE agent_id = ? AND ts >= ? " +
            "AND (memory_summary ILIKE ANY(ARRAY[" + likes + "]) " +
            "OR metadata->>'turn_user' ILIKE ANY(ARRAY[" + likes + "])) " +
            "ORDER BY ts DESC LIMIT 5000";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, agentId, java.sql.Timestamp.from(from));
        List<Map<String, Object>> hits = new ArrayList<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0;

        for (Map<String, Object> row : rows) {
            String summary = str(row.get("memory_summary"));
            String userInput = str(row.get("turn_user"));
            List<Map<String, Object>> found = new ArrayList<>();
            scanText(summary, "memory", found);
            scanText(userInput, "user_input", found);
            for (Map<String, Object> f : found) {
                if (hits.size() >= 200) break;
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("eventId", str(row.get("event_id")));
                h.put("ts", row.get("ts"));
                h.put("sessionId", str(row.get("session_id")));
                h.put("traceId", str(row.get("trace_id")));
                h.put("layer", str(row.get("layer")));
                h.put("operation", str(row.get("operation")));
                h.put("field", f.get("field"));
                h.put("type", f.get("type"));
                h.put("name", f.get("name"));
                h.put("severity", f.get("severity"));
                h.put("snippet", f.get("snippet"));
                hits.add(h);
                byType.merge((String) f.get("name"), 1, Integer::sum);
                total++;
            }
            if (hits.size() >= 200) break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", agentId);
        out.put("days", days);
        out.put("scannedCandidates", rows.size());
        out.put("total", total);
        out.put("byType", byType);
        out.put("hits", hits);
        return out;
    }

    /** 对单字段跑全部规则，命中脱敏后记入 found（每字段最多 3 条）。 */
    private void scanText(String text, String field, List<Map<String, Object>> found) {
        if (text == null || text.isBlank() || found.size() >= 3) return;
        for (Rule r : RULES) {
            if (found.size() >= 3) break;
            Matcher m = r.p().matcher(text);
            if (!m.find()) continue;
            String masked = mask(m.group());
            int s = Math.max(0, m.start() - 50), e = Math.min(text.length(), m.end() + 50);
            String snippet = text.substring(s, e).replace(m.group(), masked);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("field", field);
            f.put("type", r.type());
            f.put("name", r.name());
            f.put("severity", r.severity());
            f.put("snippet", snippet);
            found.add(f);
        }
    }

    /** 脱敏：保留前 3 后 2 字符，中间以 *** 代替。 */
    private static String mask(String s) {
        if (s.length() <= 6) return "***";
        return s.substring(0, 3) + "***" + s.substring(s.length() - 2);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
