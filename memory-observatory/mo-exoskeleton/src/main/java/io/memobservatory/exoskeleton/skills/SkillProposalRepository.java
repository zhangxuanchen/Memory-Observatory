package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillProposal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 */
@Repository
public class SkillProposalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public SkillProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc.query(SQL_AGENTS, (rs, n) -> new AgentStat(
                rs.getString("agent_id"), rs.getLong("sessions"),
                rs.getLong("turns"), rs.getLong("pending")));
    }

    /**
     * 某 Agent 的会话清单。分类是 session 粒度（一个会话共享一个 task_type），
     * 故一个会话只归一类——{@code MAX} 只是取那一类，不是聚合。
     */
    private static final String SQL_SESSIONS = """
            SELECT session_id, MAX(task_type) AS task_type, COUNT(*) AS turns
            FROM memory_turns
            WHERE agent_id = ?
            GROUP BY session_id
            ORDER BY turns DESC
            """;

    public List<SessionStat> sessions(String agentId) {
        return jdbc.query(SQL_SESSIONS, (rs, n) -> new SessionStat(
                rs.getString("session_id"), rs.getString("task_type"), rs.getLong("turns")), agentId);
    }

    /**
     * 按任务类型的分布，限定在某个 Agent、可选再限定到某个会话。
     * {@code NULL} 归成 {@code __unclassified__}，与「判过、归不进」的 {@code other}
     * 刻意分开（同 {@code unknown} vs {@code none}）。
     */
    private static final String DIST_HEAD = """
            SELECT COALESCE(task_type, '__unclassified__') AS task_type,
                   COUNT(DISTINCT session_id)             AS sessions,
                   COUNT(*)                               AS turns
            FROM memory_turns
            WHERE agent_id = ?
            """;
    private static final String DIST_TAIL = """
            GROUP BY 1
            ORDER BY 3 DESC
            """;

    public List<ClassCount> classDistribution(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return jdbc.query(DIST_HEAD + DIST_TAIL, MAP_CLASS, agentId);
        }
        return jdbc.query(DIST_HEAD + "  AND session_id = ?\n" + DIST_TAIL, MAP_CLASS, agentId, sessionId);
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
            SELECT user_text
            FROM memory_turns
            WHERE agent_id = ? AND task_type = ?
              AND user_text IS NOT NULL AND btrim(user_text) <> ''
            ORDER BY session_id, started_at NULLS LAST, turn_id
            """;

    public List<String> classTurnTexts(String agentId, String taskType) {
        return jdbc.query(SQL_CLASS_TURNS, (rs, n) -> rs.getString(1), agentId, taskType);
    }

    // ------------------------------------------------------------------
    // 提议：读 / 落 / 状态流转
    // ------------------------------------------------------------------

    private static final String COLS = """
            SELECT proposal_id, agent_id, task_type, task_label, turns, skill_id, name, description,
                   prompt, tools, status, digest_hash, created_at, decided_at
            FROM memory_skill_proposals
            """;

    public SkillProposal find(String proposalId) {
        List<SkillProposal> rows = jdbc.query(COLS + " WHERE proposal_id = ?", this::map, proposalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某个 Agent 名下的全部提议（含已采纳 / 已不用）。 */
    public List<SkillProposal> listByAgent(String agentId) {
        return jdbc.query(COLS + " WHERE agent_id = ? ORDER BY created_at DESC", this::map, agentId);
    }

    private SkillProposal map(ResultSet rs, int n) throws SQLException {
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
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant());
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
     * 落一条提议，并处理「少打扰」的三条规则：
     * <ol>
     *   <li>指纹未变 → 保持原状（PENDING / ADOPTED / DISMISSED 都不打扰）；</li>
     *   <li>已采纳 → 不再动（技能已在工作台技能库里）；</li>
     *   <li>其余（含「已决定不用但证据变了」）→ 覆盖回 PENDING，重新请人看一眼。</li>
     * </ol>
     *
     * @return 落库后的当前状态（可能是既有行）
     */
    public SkillProposal store(SkillProposal p) {
        SkillProposal existing = find(p.proposalId());
        if (existing != null) {
            if (existing.digestHash().equals(p.digestHash())) {
                return existing;
            }
            if (SkillProposal.ADOPTED.equals(existing.status())) {
                return existing;
            }
        }
        upsert(p);
        return find(p.proposalId());
    }

    private static final String SQL_UPSERT = """
            INSERT INTO memory_skill_proposals
              (proposal_id, agent_id, task_type, task_label, turns, skill_id, name, description,
               prompt, tools, status, digest_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
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
              created_at  = now(),
              decided_at  = NULL
            """;

    private void upsert(SkillProposal p) {
        jdbc.update(SQL_UPSERT,
                p.proposalId(), p.agentId(), p.taskType(), p.taskLabel(), p.turns(),
                p.skillId(), p.name(), p.description(), p.prompt(),
                toolsJson(p.tools()), p.status(), p.digestHash());
    }

    /** 状态流转（采纳 / 不用）——只标记，落盘不在这里（由 mo-server 编排）。 */
    private static final String SQL_MARK = """
            UPDATE memory_skill_proposals SET status = ?, decided_at = now() WHERE proposal_id = ?
            """;

    public SkillProposal mark(String proposalId, String status) {
        jdbc.update(SQL_MARK, status, proposalId);
        return find(proposalId);
    }
}