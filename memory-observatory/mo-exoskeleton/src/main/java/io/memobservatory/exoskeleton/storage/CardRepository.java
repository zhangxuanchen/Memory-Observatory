package io.memobservatory.exoskeleton.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.cards.CardPlanner;
import io.memobservatory.exoskeleton.model.CardReport;
import io.memobservatory.exoskeleton.model.DecisionCard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 决策卡片的落库层（§4.10）。
 *
 * <p>为什么卡片非落库不可：§4.10.4 的三条后果自己就要求记住状态——
 * reject「同一簇不再重复提议」要知道谁被驳回过，defer「下个周期再报」要知道谁被推迟过，
 * expires_in_days 要有 created_at 才谈得上到期。只在内存里生成，这三条一条都做不到。
 *
 * <p><b>payload 是快照，不是渲染缓存</b>：卡面（标题 / 证据 / 选项 / 后果）是<b>当时</b>生成的，
 * 判据随新数据重算后也不回填已发出的卡——否则人看到的证据与点下时的证据不是一份。
 * 刷新只发生在「重新生成」时（{@link #refresh}，status 回 OPEN）。
 */
@Repository
public class CardRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 库里的一行：卡片快照 + 状态与时间。 */
    public record Stored(
            String cardId, String template, List<String> subjects, int severity,
            DecisionCard card, String status, Instant createdAt, Instant expiresAt,
            Instant answeredAt, String answer) {

        /** 「到期」只是不再展示：状态不因到期而变，由时间比较得出（不替人作决定）。 */
        public boolean expired(Instant now) {
            return expiresAt != null && expiresAt.isBefore(now);
        }

        public CardReport.CardView view(Instant now) {
            return new CardReport.CardView(card, status, createdAt, expiresAt, answeredAt, answer, expired(now));
        }
    }

    private static final String COLS =
            "card_id, template, subjects, severity, payload::text AS payload, status, "
                    + "created_at, expires_at, answered_at, answer";

    private Stored map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Stored(
                rs.getString("card_id"),
                rs.getString("template"),
                strings(rs.getArray("subjects")),
                rs.getInt("severity"),
                read(rs.getString("payload")),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "answered_at"),
                rs.getString("answer"));
    }

    /** JSON 是卡片契约本身，解析失败说明库里有脏数据——抛出来，不静默给一张空卡。 */
    private DecisionCard read(String payload) {
        try {
            return json.readValue(payload, DecisionCard.class);
        } catch (Exception e) {
            throw new IllegalStateException("卡片 payload 解析失败（库里数据不是 §4.10.4 契约）：" + e.getMessage(), e);
        }
    }

    private String write(DecisionCard card) {
        try {
            return json.writeValueAsString(card);
        } catch (Exception e) {
            throw new IllegalStateException("卡片序列化失败：" + e.getMessage(), e);
        }
    }

    private static List<String> strings(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] arr) {
            return List.of(arr);
        }
        if (raw instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of();
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    public Optional<Stored> find(String cardId) {
        return jdbc.query("SELECT " + COLS + " FROM memory_cards WHERE card_id = ?", this::map, cardId)
                .stream().findFirst();
    }

    /** 卡片清单。{@code status} 为 null 时全量；排序与 §4.10.5 一致：严重度降序，其次新的在前。 */
    public List<Stored> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT " + COLS + " FROM memory_cards ORDER BY severity DESC, created_at DESC, card_id",
                    this::map);
        }
        return jdbc.query("SELECT " + COLS + " FROM memory_cards WHERE status = ? "
                + "ORDER BY severity DESC, created_at DESC, card_id", this::map, status);
    }

    public int count(String status) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_cards WHERE status = ?", Integer.class, status);
        return n == null ? 0 : n;
    }

    /**
     * 曾被「驳回 / 放弃」的簇（§4.10.4 的 reject 后果：同一簇不再重复提议）。
     * subjects 是数组，故用 unnest 展开——合并卡一次覆盖多个簇，逐个都要算进去。
     */
    public Set<String> suppressedClusters() {
        String placeholders = String.join(",", CardPlanner.SUPPRESSING_ANSWERS.stream().map(a -> "?").toList());
        String sql = "SELECT DISTINCT unnest(subjects) AS k FROM memory_cards "
                + "WHERE status = 'ANSWERED' AND answer IN (" + placeholders + ")";
        return new HashSet<>(jdbc.queryForList(sql, String.class,
                CardPlanner.SUPPRESSING_ANSWERS.toArray()));
    }

    /** 已有评估快照的规则。T1 用它判「影子期是否已经过了一轮」（『影子期结束』在当前数据下唯一可判的依据）。 */
    public Set<String> rulesWithEvals() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT rule_id FROM memory_rule_evals", String.class));
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    private static final String SQL_INSERT = """
            INSERT INTO memory_cards (card_id, template, subjects, severity, payload, status, expires_at)
            VALUES (?, ?, ?, ?, ?::jsonb, 'OPEN', ?)
            ON CONFLICT (card_id) DO NOTHING
            """;

    /** 新卡入库。返回 0 表示并发下已存在（由调用方按刷新处理）。 */
    public int insert(CardPlanner.Candidate c) {
        return jdbc.update(SQL_INSERT,
                c.card().cardId(), c.card().template().name(), c.card().subjects().toArray(new String[0]),
                c.severity(), write(c.card()), ts(c.expiresAt()));
    }

    /**
     * 刷新一张未答卡：换上新的卡面与到期时间，<b>保留 created_at</b>（这张卡是什么时候开始问的）。
     * 只对 status='OPEN' 生效——已答的卡不该被静默改写。
     */
    private static final String SQL_REFRESH = """
            UPDATE memory_cards
               SET subjects = ?, severity = ?, payload = ?::jsonb, expires_at = ?
             WHERE card_id = ? AND status = 'OPEN'
            """;

    public int refresh(CardPlanner.Candidate c) {
        return jdbc.update(SQL_REFRESH,
                c.card().subjects().toArray(new String[0]), c.severity(), write(c.card()),
                ts(c.expiresAt()), c.card().cardId());
    }

    /**
     * 重新提出一张「下个周期再报」的卡：回到未答、换上新的卡面。
     *
     * <p><b>只对 {@link CardPlanner#REISSUABLE_ANSWERS} 生效</b>——人已经表过态（采纳 / 驳回 / 停用…）的卡
     * 不该被系统下一轮自动翻回未答，那等于把人的决定擦掉。
     *
     * <p>已知取舍：重置 created_at 会让「某周出了多少张卡」（§4.10.6 的反向指标）算不准——
     * 要精确统计得另开一张只增不改的流水表，本版没建，故不假装能算。
     */
    private static final String SQL_REISSUE = """
            UPDATE memory_cards
               SET status = 'OPEN', answer = NULL, answered_at = NULL,
                   subjects = ?, severity = ?, payload = ?::jsonb, expires_at = ?, created_at = now()
             WHERE card_id = ? AND status = 'ANSWERED'
            """;

    public int reissue(CardPlanner.Candidate c) {
        return jdbc.update(SQL_REISSUE,
                c.card().subjects().toArray(new String[0]), c.severity(), write(c.card()),
                ts(c.expiresAt()), c.card().cardId());
    }

    /** 作答。只对未答卡生效（重复作答不覆盖第一次的决定，由调用方如实报告 0 行）。 */
    private static final String SQL_ANSWER = """
            UPDATE memory_cards SET status = 'ANSWERED', answer = ?, answered_at = now()
             WHERE card_id = ? AND status = 'OPEN'
            """;

    public int answer(String cardId, String answer) {
        return jdbc.update(SQL_ANSWER, answer, cardId);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}