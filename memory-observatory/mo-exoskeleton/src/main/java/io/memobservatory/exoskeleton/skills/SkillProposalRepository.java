package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.model.SkillVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能提议的取数 / 落库 / 状态流转。
 *
 * <p><b>为什么会话摘要在数据侧</b>：本模块与 mo-server 共读同一个库，{@code memory_turns}
 * 是「分析型访问的唯一入口」——取一类任务的对话，就是按 {@code task_type} 过滤后把
 * {@code user_text} 按序拼起来，不需要新建 session 表（与二级筛选同一定案）。
 *
 * <p><b>幂等与「少打扰」都在 {@link #store}</b>：同一类同一份证据（指纹相同）不再重复落；
 * 已采纳的不再动；已决定不用、但证据变了的，才允许重新提上来。
 *
 * <p><b>三张表的角色</b>：{@code memory_skill_proposals} 是「一格一类的当前态」；
 * {@code memory_skill_versions} 是每个版本的内容快照（只增不改）；
 * {@code memory_skill_events} 是动作流水（只增不改，人机共用）。分工见 schema.sql。
 */
@Repository
public class SkillProposalRepository {

    private final JdbcTemplate jdbc;
    private final VersionPolicy versionPolicy;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Spring 注入用（带版本判决器）。
     */
    @Autowired
    public SkillProposalRepository(JdbcTemplate jdbc, VersionPolicy versionPolicy) {
        this.jdbc = jdbc;
        this.versionPolicy = versionPolicy;
    }

    /**
     * 单参构造：供单测的手写替身 {@code super((JdbcTemplate) null)} 用（不跑 Spring，{@code @Value} 不注入）。
     * 阈值取 {@link VersionPolicy} 的默认值 10。
     */
    protected SkillProposalRepository(JdbcTemplate jdbc) {
        this(jdbc, new VersionPolicy(10));
    }

    // ------------------------------------------------------------------
    // 取数：分类清单与某类的对话原文
    // ------------------------------------------------------------------

    /** 一个 Agent 一行：多少会话 / 多少条对话 / 几条待采纳（下拉的选项面用，始终全量）。 */
    public record AgentStat(String agentId, long sessions, long turns, long pending) {
    }

    /** 一个会话一行：判成哪一类、多少条对话（Session 下拉的选项面用）。 */
    public record SessionStat(String sessionId, String taskType, long turns) {
    }

    /** 分类清单原始读数：一类一行的会话数 / turn 数。 */
    public record ClassCount(String taskType, long sessions, long turns) {
    }

    /**
     * Agent 清单：<b>始终全量</b>——它是「按哪个 Agent 看」这张筛选器的选项面，
     * 不能被自己的筛选结果影响（与 §4.7 {@code taskTypes} 同一规矩）。
     * {@code pending} 是该 Agent 名下还等着人拍板的提议数，让人知道该去看哪个 Agent。
     */
    private static final String SQL_AGENTS = """
            SELECT t.agent_id,
                   COUNT(DISTINCT t.session_id) AS sessions,
                   COUNT(*)                     AS turns,
                   (SELECT COUNT(*) FROM memory_skill_proposals p
                     WHERE p.agent_id = t.agent_id AND p.status = 'PENDING') AS pending
            FROM memory_turns t
            WHERE t.agent_id IS NOT NULL AND btrim(t.agent_id) <> ''
            GROUP BY t.agent_id
            ORDER BY turns DESC
            """;

    public List<AgentStat> agents() {
        return agents(0);
    }

    /** {@code limit > 0} 时只取前 N 行；缺省 0 = 全量（行为不变）。 */
    public List<AgentStat> agents(int limit) {
        String sql = SQL_AGENTS + (limit > 0 ? " LIMIT ?" : "");
        Object[] args = limit > 0 ? new Object[]{limit} : new Object[0];
        return jdbc.query(sql, (rs, n) -> new AgentStat(
                rs.getString("agent_id"), rs.getLong("sessions"),
                rs.getLong("turns"), rs.getLong("pending")), args);
    }

    /**
     * 某 Agent 的会话清单。分类是 session 粒度（一个会话共享一个 task_type），
     * 故一个会话只归一类——{@code MAX} 只是取那一类，不是聚合。
     */
    private static final String SQL_SESSIONS = """
            SELECT t.session_id, MAX(c.task_type) AS task_type, COUNT(*) AS turns
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ?
            GROUP BY t.session_id
            ORDER BY turns DESC
            """;

    public List<SessionStat> sessions(String agentId) {
        return sessions(agentId, 0);
    }

    /** {@code limit > 0} 时只取前 N 行；缺省 0 = 全量（行为不变）。 */
    public List<SessionStat> sessions(String agentId, int limit) {
        String sql = SQL_SESSIONS + (limit > 0 ? " LIMIT ?" : "");
        Object[] args = limit > 0 ? new Object[]{agentId, limit} : new Object[]{agentId};
        return jdbc.query(sql, (rs, n) -> new SessionStat(
                rs.getString("session_id"), rs.getString("task_type"), rs.getLong("turns")), args);
    }

    /**
     * 按任务类型的分布，限定在某个 Agent、可选再限定到某个会话。
     * {@code NULL} 归成 {@code __unclassified__}，与「判过、归不进」的 {@code other}
     * 刻意分开（同 {@code unknown} vs {@code none}）。
     */
    private static final String DIST_HEAD = """
            SELECT COALESCE(c.task_type, '__unclassified__') AS task_type,
                   COUNT(DISTINCT t.session_id)             AS sessions,
                   COUNT(*)                                 AS turns
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ?
            """;
    private static final String DIST_TAIL = """
            GROUP BY 1
            ORDER BY 3 DESC
            """;

    public List<ClassCount> classDistribution(String agentId, String sessionId) {
        return classDistribution(agentId, sessionId, 0);
    }

    /** {@code limit > 0} 时只取前 N 行；缺省 0 = 全量（行为不变）。 */
    public List<ClassCount> classDistribution(String agentId, String sessionId, int limit) {
        String tail = DIST_TAIL + (limit > 0 ? " LIMIT ?" : "");
        if (sessionId == null || sessionId.isBlank()) {
            return limit > 0
                    ? jdbc.query(DIST_HEAD + tail, MAP_CLASS, agentId, limit)
                    : jdbc.query(DIST_HEAD + tail, MAP_CLASS, agentId);
        }
        return limit > 0
                ? jdbc.query(DIST_HEAD + "  AND t.session_id = ?\n" + tail, MAP_CLASS, agentId, sessionId, limit)
                : jdbc.query(DIST_HEAD + "  AND t.session_id = ?\n" + tail, MAP_CLASS, agentId, sessionId);
    }

    private static final org.springframework.jdbc.core.RowMapper<ClassCount> MAP_CLASS = (rs, n) ->
            new ClassCount(rs.getString("task_type"), rs.getLong("sessions"), rs.getLong("turns"));

    /**
     * 某个 Agent 下某一类任务的全部用户提问（按会话、时间顺序）。
     *
     * <p>只取 {@code user_text}：与 {@code TaskClassifier} 同一口径——不喂 {@code io_kind}
     * 等行为特征，避免模型复述 §4.4 的 shape。技能要从「人到底在让 Agent 做什么」里提炼。
     */
    private static final String SQL_CLASS_TURNS = """
            SELECT t.user_text
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ? AND c.task_type = ?
              AND t.user_text IS NOT NULL AND btrim(t.user_text) <> ''
            ORDER BY t.session_id, t.started_at NULLS LAST, t.turn_id
            """;

    /**
     * 抽样版：先按同一顺序编号，再<b>等距</b>取约 {@code sampleCap} 条（{@code total <= cap} 时全取）。
     *
     * <p>为什么是「等距」而不是 {@code LIMIT 前 N 条}：前 N 条会把一类任务压成开头那几个会话，
     * 丢失「跨会话的共性」；等距抽样才与 {@link EvidenceDigest} 的均匀抽样同精神。
     * 步长 = {@code ceil(total / cap)}，写成 {@code (total-1)/cap + 1} 避开 SQL 里的浮点与取整歧义。
     */
    private static final String SQL_CLASS_TURNS_SAMPLED = """
            SELECT user_text FROM (
              SELECT t.user_text AS user_text,
                     row_number() OVER (ORDER BY t.session_id, t.started_at NULLS LAST, t.turn_id) AS rn,
                     count(*)     OVER ()                                                    AS total
              FROM memory_turns t
              LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
              WHERE t.agent_id = ? AND c.task_type = ?
                AND t.user_text IS NOT NULL AND btrim(t.user_text) <> ''
            ) s
            WHERE s.total <= ? OR (s.rn - 1) % ((s.total - 1) / ? + 1) = 0
            ORDER BY s.rn
            """;

    public List<String> classTurnTexts(String agentId, String taskType) {
        return classTurnTexts(agentId, taskType, 0);
    }

    /**
     * 取某类原文；{@code sampleCap > 0} 时在 <b>SQL 层</b>先抽样（见 {@link #SQL_CLASS_TURNS_SAMPLED}），
     * 免得把整类原文搬进内存再在 Java 里抽样。{@code <= 0} 即原样全量（缺省行为不变）。
     */
    public List<String> classTurnTexts(String agentId, String taskType, int sampleCap) {
        if (sampleCap <= 0) {
            return jdbc.query(SQL_CLASS_TURNS, (rs, n) -> rs.getString(1), agentId, taskType);
        }
        return jdbc.query(SQL_CLASS_TURNS_SAMPLED, (rs, n) -> rs.getString(1),
                agentId, taskType, sampleCap, sampleCap);
    }

    /**
     * 某类任务依据了哪些会话（去重、按 id 排序，结果确定）。供「提炼思考历程」的确定性证据用——
     * 让人看得见「这个技能是从哪几个会话里归纳出来的」。只读、与取原文同口径过滤空 session_id。
     */
    private static final String SQL_CLASS_SESSIONS = """
            SELECT DISTINCT t.session_id
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ? AND c.task_type = ?
              AND t.session_id IS NOT NULL AND btrim(t.session_id) <> ''
            ORDER BY t.session_id
            """;

    public List<String> classSessions(String agentId, String taskType) {
        return jdbc.query(SQL_CLASS_SESSIONS, (rs, n) -> rs.getString(1), agentId, taskType);
    }

    /**
     * 某一类对话的时间跨度（首 / 末 turn 的日期，{@code YYYY-MM-DD}）：供「日志总结」的硬统计用。
     * 该类没有可用时间戳时返回 {@code null}。
     */
    public record Span(String first, String last) {
    }

    private static final String SQL_CLASS_SPAN = """
            SELECT to_char(min(t.started_at), 'YYYY-MM-DD'), to_char(max(t.started_at), 'YYYY-MM-DD')
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ? AND c.task_type = ? AND t.started_at IS NOT NULL
            """;

    public Span classSpan(String agentId, String taskType) {
        return jdbc.query(SQL_CLASS_SPAN, rs -> {
            if (!rs.next() || rs.getString(1) == null) {
                return null;
            }
            return new Span(rs.getString(1), rs.getString(2));
        }, agentId, taskType);
    }

    /**
     * 某个 Agent 下<b>全部</b>用户提问（按会话、时间顺序），不分任务类型。
     *
     * <p>供事实提炼用：事实（项目 / 代码结构约定）不属于任何一类任务，本就不该按 task_type 切分——
     * 一格一 Agent。与 {@link #classTurnTexts} 同一口径，只取 {@code user_text}。
     */
    private static final String SQL_AGENT_TURNS = """
            SELECT user_text
            FROM memory_turns
            WHERE agent_id = ?
              AND user_text IS NOT NULL AND btrim(user_text) <> ''
            ORDER BY session_id, started_at NULLS LAST, turn_id
            """;

    public List<String> agentTurnTexts(String agentId) {
        return jdbc.query(SQL_AGENT_TURNS, (rs, n) -> rs.getString(1), agentId);
    }

    // ------------------------------------------------------------------
    // 提议：读 / 落 / 状态流转
    // ------------------------------------------------------------------

    private static final String COLS = """
            SELECT proposal_id, agent_id, task_type, task_label, turns, skill_id, name, description,
                   prompt, tools, status, digest_hash, created_at, decided_at, version,
                   (SELECT MAX(v.version) FROM memory_skill_versions v
                     WHERE v.proposal_id = memory_skill_proposals.proposal_id
                       AND v.status = 'PENDING'
                       AND memory_skill_proposals.status = 'ADOPTED') AS pending_version,
                   reasoning
            FROM memory_skill_proposals
            """;

    public SkillProposal find(String proposalId) {
        List<SkillProposal> rows = jdbc.query(COLS + " WHERE proposal_id = ?", this::map, proposalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某个 Agent 名下的全部提议（含已采纳 / 已不用）。 */
    public List<SkillProposal> listByAgent(String agentId) {
        return listByAgent(agentId, 0);
    }

    /** {@code limit > 0} 时只取前 N 行；缺省 0 = 全量（行为不变）。 */
    public List<SkillProposal> listByAgent(String agentId, int limit) {
        String sql = COLS + " WHERE agent_id = ? ORDER BY created_at DESC" + (limit > 0 ? " LIMIT ?" : "");
        return limit > 0
                ? jdbc.query(sql, this::map, agentId, limit)
                : jdbc.query(sql, this::map, agentId);
    }

    private SkillProposal map(ResultSet rs, int n) throws SQLException {
        // 注意：JDBC 的 wasNull() 只反映「紧邻的上一次取值」，必须取完 pending_version 立刻判，
        // 否则会被后面读的其他列（如 version）覆盖成 false，把 SQL 的 NULL 读成 0——
        // 那会让每张无新版的卡都冒出一个「有新版 v0」徽标。
        Integer pendingVersion = nullableInt(rs, "pending_version");
        return new SkillProposal(
                rs.getString("proposal_id"),
                rs.getString("agent_id"),
                rs.getString("task_type"),
                rs.getString("task_label"),
                rs.getLong("turns"),
                rs.getString("skill_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("prompt"),
                parseTools(rs.getString("tools")),
                rs.getString("status"),
                rs.getString("digest_hash"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                rs.getInt("version"),
                pendingVersion,
                parseReasoning(rs.getString("reasoning")));
    }

    /** 可空整数列：取完立刻用 wasNull() 判，避免被后续列读取冲掉（见 {@link #map} 的注释）。 */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * 读「提炼思考历程」列：空 / 解析不出即返回 null——旧行没有这一列，卡片据此不显示入口。
     * 与 {@link #parseTools} 同一口径：坏数据降级成「没有」，不让一条畸形 JSON 掀翻整张看板。
     */
    private SkillProposal.Reasoning parseReasoning(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, SkillProposal.Reasoning.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写「提炼思考历程」列：null 即写 NULL（SQL 参数里 null 会落成 NULL），空对象也照写。 */
    private String reasoningJson(SkillProposal.Reasoning r) {
        if (r == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(r);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseTools(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toolsJson(List<String> tools) {
        try {
            return mapper.writeValueAsString(tools == null ? List.of() : tools);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 落一条提议，并处理「少打扰 + 可演进」的四条规则：
     * <ol>
     *   <li><b>指纹未变</b> → 保持原状（PENDING / ADOPTED / DISMISSED 都不打扰）；</li>
     *   <li><b>已采纳 + 指纹变</b> → 交 {@link VersionPolicy} 判：内容是新的 <b>且</b> 证据确有增长时
     *       写新版（{@code v+1}，落版本链 status=PENDING、旧版置 SUPERSEDED），提议行<b>仍 ADOPTED</b>、
     *       不动 {@code created_at / decided_at}、不翻 PENDING——只在卡片上标「有新版 vN」，人按需点更新；
     *       缺任一条件则保持原状（防同证据重跑时措辞漂移刷版本）；</li>
     *   <li><b>其余</b>（含「已决定不用但证据变了」）→ 覆盖回 PENDING，重新请人看一眼（不变）；</li>
     *   <li><b>硬重复自动去重</b>：同 Agent 同 {@code skill_id} 是同一技能，落库前只留证据（turns）多的一条。</li>
     * </ol>
     * {@code skill_id} 从 v1 起<b>钉死</b>，后续版本沿用——文件名即身份，改名会留孤儿 yaml。
     *
     * @return 落库后的当前状态（可能是既有行）
     */
    public SkillProposal store(SkillProposal p) {
        SkillProposal existing = find(p.proposalId());
        if (existing != null) {
            if (existing.digestHash().equals(p.digestHash())) {
                return existing;                       // 规则 1
            }
            if (SkillProposal.ADOPTED.equals(existing.status())) {
                return reviseOrKeep(existing, p);      // 规则 2
            }
        }
        // 规则 4：硬重复去重（本条可能被判为「弱者」而作废）→ 再走规则 3 落库
        SkillProposal incoming = dedupe(p);
        upsert(incoming);
        if (incoming.isPending()) {
            insertVersion(incoming, existing == null ? 1 : Math.max(1, existing.version()),
                    SkillVersion.PENDING);
        }
        return find(p.proposalId());
    }

    /**
     * 已采纳提议遇到新证据：判该不该出新版。
     *
     * <p>{@code KEEP} → 原样返回既有行（内容没变，或证据涨得不够）；{@code REVISE} → 写新版。
     * 注意写新版<b>不翻 PENDING</b>：技能仍在工作台里（旧版内容），新内容只是「备好了等人点更新」。
     */
    private SkillProposal reviseOrKeep(SkillProposal existing, SkillProposal p) {
        String oldContent = EvidenceDigest.contentHash(existing.name(), existing.prompt(), existing.tools());
        String newContent = EvidenceDigest.contentHash(p.name(), p.prompt(), p.tools());
        VersionPolicy.Decision d = versionPolicy.decide(oldContent, newContent, existing.turns(), p.turns());
        if (!d.revise()) {
            return existing;
        }
        int next = existing.version() + 1;
        reviseAdopted(existing, p, next);
        supersedeVersionsExcept(p.proposalId(), next);
        insertVersion(p, next, SkillVersion.PENDING);
        recordEvent(SkillEvent.REVISE, SkillEvent.SYSTEM, p.proposalId(), p.agentId(), p.taskType(),
                next, p.digestHash(), d.reason());
        return find(p.proposalId());
    }

    /** 出新版：写提议行的内容与版本号，但<b>不碰</b> status / created_at / decided_at，且技能 id 沿用既有（钉死）。 */
    private static final String SQL_REVISE_ADOPTED = """
            UPDATE memory_skill_proposals
               SET task_label = ?, turns = ?, skill_id = ?, name = ?, description = ?,
                   prompt = ?, tools = ?, digest_hash = ?, version = ?, reasoning = ?
             WHERE proposal_id = ?
            """;

    protected void reviseAdopted(SkillProposal existing, SkillProposal p, int next) {
        jdbc.update(SQL_REVISE_ADOPTED,
                p.taskLabel(), p.turns(), existing.skillId(), p.name(), p.description(),
                p.prompt(), toolsJson(p.tools()), p.digestHash(), next, reasoningJson(p.reasoning()),
                p.proposalId());
    }

    /** 同一条提议下，除保留版本外的旧版本一律置 SUPERSEDED（只降不删，历史可查）。 */
    private static final String SQL_SUPERSEDE_VERSIONS = """
            UPDATE memory_skill_versions SET status = 'SUPERSEDED'
             WHERE proposal_id = ? AND version <> ? AND status <> 'SUPERSEDED'
            """;

    protected void supersedeVersionsExcept(String proposalId, int keepVersion) {
        jdbc.update(SQL_SUPERSEDE_VERSIONS, proposalId, keepVersion);
    }

    // ------------------------------------------------------------------
    // 硬重复去重（同 Agent 同 skill_id）
    // ------------------------------------------------------------------

    /** 一条兄弟提议：同 Agent 下 skill_id 相同、proposal_id 不同的另一条。 */
    public record Sibling(String proposalId, long turns, String status) {
    }

    private static final String SQL_SIBLINGS = """
            SELECT proposal_id, turns, status FROM memory_skill_proposals
             WHERE agent_id = ? AND skill_id = ? AND proposal_id <> ?
            """;

    public List<Sibling> siblings(String agentId, String skillId, String exceptProposalId) {
        return jdbc.query(SQL_SIBLINGS, (rs, n) ->
                new Sibling(rs.getString("proposal_id"), rs.getLong("turns"), rs.getString("status")),
                agentId, skillId, exceptProposalId);
    }

    /**
     * 硬重复去重：只保留证据（turns）多的一条，弱者作废并记 {@link SkillEvent#DEDUP} 事件。
     *
     * <p>本条是弱者 → 返回一份 status=DISMISSED 的副本（照样落库，好让「这条重复已见过」有据可查、
     * 且下轮不再冒出来）；本条是强者 → 把更弱的兄弟标记 DISMISSED。已采纳的兄弟<b>不动</b>——
     * 它已经在工作台里了，不该被一条新提议掀掉。
     */
    private SkillProposal dedupe(SkillProposal p) {
        List<Sibling> sibs = siblings(p.agentId(), p.skillId(), p.proposalId());
        for (Sibling s : sibs) {
            if (s.turns() >= p.turns()) {
                recordEvent(SkillEvent.DEDUP, SkillEvent.SYSTEM, p.proposalId(), p.agentId(), p.taskType(),
                        null, p.digestHash(),
                        "同 Agent 同 skill_id 已有证据更多的提议（" + s.proposalId() + "），本条作废（硬重复）。");
                return copyWithStatus(p, SkillProposal.DISMISSED);
            }
        }
        for (Sibling s : sibs) {
            if (s.turns() < p.turns() && !SkillProposal.ADOPTED.equals(s.status())) {
                mark(s.proposalId(), SkillProposal.DISMISSED);
                recordEvent(SkillEvent.DEDUP, SkillEvent.SYSTEM, s.proposalId(), p.agentId(), p.taskType(),
                        null, null,
                        "同 Agent 同 skill_id 已有证据更多的提议（" + p.proposalId() + "），本条作废（硬重复）。");
            }
        }
        return p;
    }

    private static SkillProposal copyWithStatus(SkillProposal p, String status) {
        return new SkillProposal(p.proposalId(), p.agentId(), p.taskType(), p.taskLabel(), p.turns(),
                p.skillId(), p.name(), p.description(), p.prompt(), p.tools(), status,
                p.digestHash(), p.createdAt(), p.decidedAt(), p.version(), p.pendingVersion(),
                p.reasoning());
    }

    private static final String SQL_UPSERT = """
            INSERT INTO memory_skill_proposals
              (proposal_id, agent_id, task_type, task_label, turns, skill_id, name, description,
               prompt, tools, status, digest_hash, reasoning)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (proposal_id) DO UPDATE SET
              task_label  = EXCLUDED.task_label,
              turns       = EXCLUDED.turns,
              skill_id    = EXCLUDED.skill_id,
              name        = EXCLUDED.name,
              description = EXCLUDED.description,
              prompt      = EXCLUDED.prompt,
              tools       = EXCLUDED.tools,
              status      = EXCLUDED.status,
              digest_hash = EXCLUDED.digest_hash,
              reasoning   = EXCLUDED.reasoning,
              created_at  = now(),
              decided_at  = NULL
            """;

    protected void upsert(SkillProposal p) {
        jdbc.update(SQL_UPSERT,
                p.proposalId(), p.agentId(), p.taskType(), p.taskLabel(), p.turns(),
                p.skillId(), p.name(), p.description(), p.prompt(),
                toolsJson(p.tools()), p.status(), p.digestHash(), reasoningJson(p.reasoning()));
    }

    /** 状态流转（采纳 / 不用）——只标记，落盘不在这里（由 mo-server 编排）。 */
    private static final String SQL_MARK = """
            UPDATE memory_skill_proposals SET status = ?, decided_at = now() WHERE proposal_id = ?
            """;

    /** 采纳：最新版本落 ADOPTED。 */
    private static final String SQL_MARK_VERSION_ADOPTED = """
            UPDATE memory_skill_versions SET status = 'ADOPTED' WHERE proposal_id = ? AND version = ?
            """;

    /** 采纳：其余曾被采纳的旧版本降为 SUPERSEDED（只降不删）。 */
    private static final String SQL_SUPERSEDE_OTHER_ADOPTED = """
            UPDATE memory_skill_versions SET status = 'SUPERSEDED'
             WHERE proposal_id = ? AND version <> ? AND status = 'ADOPTED'
            """;

    /**
     * 状态流转（采纳 / 不用）。
     *
     * <p><b>采纳时顺带把版本链推进一步</b>：最新版本置 ADOPTED、更早的已采纳版本置 SUPERSEDED。
     * 这样「版本历史」能把「当前装在库里的是哪一版」和「上一版长什么样」一并说清。
     */
    public SkillProposal mark(String proposalId, String status) {
        jdbc.update(SQL_MARK, status, proposalId);
        if (SkillProposal.ADOPTED.equals(status)) {
            SkillProposal p = find(proposalId);
            if (p != null) {
                jdbc.update(SQL_MARK_VERSION_ADOPTED, proposalId, p.version());
                jdbc.update(SQL_SUPERSEDE_OTHER_ADOPTED, proposalId, p.version());
            }
        }
        return find(proposalId);
    }

    // ------------------------------------------------------------------
    // 版本链（内容快照）与事件流水
    // ------------------------------------------------------------------

    /** 写一版内容快照，并按 {@code (proposal_id, version)} <b>幂等</b>（同版本重来即刷新内容，不新增行）。 */
    private static final String SQL_UPSERT_VERSION = """
            INSERT INTO memory_skill_versions
              (proposal_id, version, agent_id, task_type, skill_id, name, description,
               prompt, tools, digest_hash, status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (proposal_id, version) DO UPDATE SET
              agent_id    = EXCLUDED.agent_id,
              task_type   = EXCLUDED.task_type,
              skill_id    = EXCLUDED.skill_id,
              name        = EXCLUDED.name,
              description = EXCLUDED.description,
              prompt      = EXCLUDED.prompt,
              tools       = EXCLUDED.tools,
              digest_hash = EXCLUDED.digest_hash,
              status      = EXCLUDED.status
            """;

    public void insertVersion(SkillProposal p, int version, String status) {
        jdbc.update(SQL_UPSERT_VERSION,
                p.proposalId(), version, p.agentId(), p.taskType(), p.skillId(),
                p.name(), p.description(), p.prompt(), toolsJson(p.tools()), p.digestHash(), status);
    }

    /** 某条提议的版本历史（新 → 旧）。 */
    private static final String SQL_LIST_VERSIONS = """
            SELECT proposal_id, version, agent_id, task_type, skill_id, name, description,
                   prompt, tools, digest_hash, status, created_at
            FROM memory_skill_versions
            WHERE proposal_id = ?
            ORDER BY version DESC
            """;

    public List<SkillVersion> listVersions(String proposalId) {
        return jdbc.query(SQL_LIST_VERSIONS, (rs, n) -> new SkillVersion(
                rs.getString("proposal_id"),
                rs.getInt("version"),
                rs.getString("agent_id"),
                rs.getString("task_type"),
                rs.getString("skill_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("prompt"),
                parseTools(rs.getString("tools")),
                rs.getString("digest_hash"),
                rs.getString("status"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                proposalId);
    }

    /** 追加一条事件流水（只增不改）。记失败不得影响主流程——由调用侧旁路 try/catch（见 SkillDiscoverer 的 note）。 */
    private static final String SQL_INSERT_EVENT = """
            INSERT INTO memory_skill_events
              (proposal_id, agent_id, task_type, kind, actor, version, digest_hash, detail)
            VALUES (?,?,?,?,?,?,?,?)
            """;

    public void recordEvent(String kind, String actor, String proposalId, String agentId,
                            String taskType, Integer version, String digestHash, String detail) {
        jdbc.update(SQL_INSERT_EVENT, proposalId, agentId == null ? "" : agentId, taskType,
                kind, actor, version, digestHash, detail);
    }

    /**
     * 事件流水（新 → 旧）。{@code agentId / kind} 为空即不按该维过滤；{@code limit} 由调用侧夹取。
     */
    private static final String SQL_EVENTS_HEAD = """
            SELECT event_id, proposal_id, agent_id, task_type, kind, actor, version, digest_hash,
                   detail, created_at
            FROM memory_skill_events
            WHERE 1 = 1
            """;

    public List<SkillEvent> recentEvents(String agentId, int limit, String kind) {
        StringBuilder sql = new StringBuilder(SQL_EVENTS_HEAD);
        List<Object> args = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            args.add(agentId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        sql.append(" ORDER BY created_at DESC, event_id DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), (rs, n) -> new SkillEvent(
                rs.getLong("event_id"),
                rs.getString("proposal_id"),
                rs.getString("agent_id"),
                rs.getString("task_type"),
                rs.getString("kind"),
                rs.getString("actor"),
                (Integer) (rs.getObject("version") == null ? null : rs.getInt("version")),
                rs.getString("digest_hash"),
                rs.getString("detail"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                args.toArray());
    }

    // ------------------------------------------------------------------
    // 效果验证：采纳前后对照取数
    // ------------------------------------------------------------------

    /**
     * 对照用的一个 turn：只取三项判据（纠正 / 失败 / 回合）需要的列。
     *
     * <p>{@code startedAt} 可为空——没有时刻就归不到「界前 / 界后」任一组，取数时会被剔除，
     * 故这一列必须带出来由上层过滤，而不是在 SQL 里静默丢掉。
     */
    public record ContrastTurn(String turnId, String sessionId, Instant startedAt,
                               boolean corrected, String outcome, String source) {
    }

    /**
     * 某 Agent 某一类任务的全部 turn（按会话、时间排序），供「采纳前 vs 采纳后」分桶。
     *
     * <p>与 {@link #classTurnTexts} 同一谓词口径（agent_id + task_type），只是多取判据列。
     * 不取 {@code user_text}：本闸门不做文本判定，只读已回写的 {@code corrected} 与 {@code outcome}。
     */
    private static final String SQL_CONTRAST = """
            SELECT t.turn_id, t.session_id, t.started_at, COALESCE(c.corrected, false) AS corrected, t.outcome, t.source
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            WHERE t.agent_id = ? AND c.task_type = ?
            ORDER BY t.session_id, t.started_at NULLS LAST, t.turn_id
            """;

    public List<ContrastTurn> classContrastTurns(String agentId, String taskType) {
        return jdbc.query(SQL_CONTRAST, (rs, n) -> new ContrastTurn(
                rs.getString("turn_id"),
                rs.getString("session_id"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getBoolean("corrected"),
                rs.getString("outcome"),
                rs.getString("source")), agentId, taskType);
    }

    // ------------------------------------------------------------------
    // 每类「本轮跑成什么样」的记账（断点续跑的地基）
    // ------------------------------------------------------------------

    /**
     * 一类的上一次处理结果。{@code outcome} ∈ QUIET / FAILED / OFFERED（语义见表注释）。
     *
     * <p>{@code digestHash} 是当轮的证据指纹——只有它和本轮算出来的指纹相同，
     * {@code QUIET} 才允许「本轮不再送 LLM」（{@link io.memobservatory.exoskeleton.skills.SkillDiscoverer}）。
     */
    public record ClassRun(String agentId, String taskType, String digestHash,
                           String outcome, long turns, String reason, Instant updatedAt) {
    }

    /** 读一类的上一次记账；没有行则返回 {@code null}（= 从没处理过，本轮照常提炼）。 */
    private static final String SQL_FIND_CLASS_RUN = """
            SELECT agent_id, task_type, digest_hash, outcome, turns, reason, updated_at
            FROM memory_skill_class_runs
            WHERE agent_id = ? AND task_type = ?
            """;

    public ClassRun findClassRun(String agentId, String taskType) {
        List<ClassRun> rows = jdbc.query(SQL_FIND_CLASS_RUN, (rs, n) -> new ClassRun(
                rs.getString("agent_id"), rs.getString("task_type"), rs.getString("digest_hash"),
                rs.getString("outcome"), rs.getLong("turns"), rs.getString("reason"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()),
                agentId, taskType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 记一类的本轮结果（一「Agent × 一类」一行，upsert 只留最近一次）。
     *
     * <p>只增不改历史之外的东西：这里写的是「上一轮跑成什么样」的读数，
     * 与 {@code memory_skill_proposals}（给人看的待办）刻意分开——判过、无技能的类不该出现在人面清单里，
     * 但它同样是已完成的劳动，不记账下一轮就会为同一份证据再问一次模型（见 schema.sql 注释）。
     */
    private static final String SQL_UPSERT_CLASS_RUN = """
            INSERT INTO memory_skill_class_runs
              (agent_id, task_type, digest_hash, outcome, turns, reason)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (agent_id, task_type) DO UPDATE SET
              digest_hash = EXCLUDED.digest_hash,
              outcome     = EXCLUDED.outcome,
              turns       = EXCLUDED.turns,
              reason      = EXCLUDED.reason,
              updated_at  = now()
            """;

    public void recordClassRun(String agentId, String taskType, String digestHash,
                               String outcome, long turns, String reason) {
        jdbc.update(SQL_UPSERT_CLASS_RUN, agentId, taskType, digestHash, outcome, turns, reason);
    }
}