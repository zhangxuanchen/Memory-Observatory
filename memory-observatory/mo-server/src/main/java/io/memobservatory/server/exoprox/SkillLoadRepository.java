package io.memobservatory.server.exoprox;

import io.memobservatory.contract.SkillLoadPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 技能装载回流的<strong>写侧</strong>（表 {@code memory_skill_loads}，owner = mo-server，DDL 在 mo-server 的 schema.sql）。
 *
 * <p><b>为什么 owner 是 mo-server</b>：装载事实只产生在 mo-server（只有它读技能库并装配 Agent），
 * 表所有权与 writer 一致（蓝图 §4.4）。原先该表由外骨骼建、本模块直写——「建表方 ≠ 写入方」，
 * M3 步骤 15 已把 DDL 收敛到唯一 writer 名下。外骨骼只读。
 *
 * <p><b>记的是「装载」不是「调用」</b>：本类只如实记录「这次装配把某个已采纳技能装进了某个 Agent」。
 * 为什么不记调用——现有工具执行事件（{@code memory_events}）里的 {@code memory_key='tool:<name>'}
 * （read_file / bash 之类）是通用工具的，yaml 技能注册的正是这些工具，<b>归因不回具体技能</b>。
 * 故回流口径止于装载，不伪造调用归因（见 schema.sql 的表注释与登记口 B20）。
 *
 * <p><b>为什么是旁路</b>：装载事实对装配本身没有影响，回流属于旁路观测。三个「不」——
 * 表未建不报错（启动次序极端情况）、写库失败不抛错（catch 后仅 WARN）、上报不占装配线程（异步）。
 * 任一环节出问题都不能影响 Agent 装配这条主链路。
 *
 * <p><b>为什么要 TTL 去重</b>：同一 Agent 在一段活跃期内会反复装配（每次新会话 / 每个请求都 create），
 * 每次都写库会把计数刷成噪声、也白白加压。故同一 {@code (agentId, skillId)} 在窗口内只上报一次，
 * 窗口外的下一次装配才 +1——计数表达的因此是「跨越窗口的装载次数」，这才是人面想看的量级。
 *
 * <p><b>前提：单实例</b>——去重窗口是进程内 {@link ConcurrentHashMap}，多实例下会各自计数（放大写入）。
 * 这与本模块其它单实例假设一致（如 SkillDiscoverer 的互斥）；多实例部署需换成带租约的去重。
 */
@Slf4j
@Repository
public class SkillLoadRepository implements SkillLoadPort {

    /** 同一 (agentId, skillId) 的去重窗口：窗口内不重复上报。 */
    private static final long DEDUP_TTL_MILLIS = 10 * 60 * 1000L;

    private static final String SQL_UPSERT = """
            INSERT INTO memory_skill_loads (agent_id, skill_id, workspace_id, load_count, last_session_id)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (agent_id, skill_id) DO UPDATE SET
                workspace_id    = EXCLUDED.workspace_id,
                load_count      = memory_skill_loads.load_count + 1,
                last_loaded_at  = now(),
                last_session_id = EXCLUDED.last_session_id
            """;

    private final JdbcTemplate jdbc;

    /** 去重窗口：key = agentId + '\u0000' + skillId → 上次上报的 epoch 毫秒。 */
    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();

    /** 上报执行器：守护线程，不阻塞装配、不阻应用退出。 */
    private final Executor executor;

    @Autowired
    public SkillLoadRepository(JdbcTemplate jdbc) {
        this(jdbc, defaultExecutor());
    }

    /**
     * 可注入执行器的构造：Spring 走上面的单参构造（用守护线程池）；
     * 单测走这里传一个同步执行器（{@code Runnable::run}），让上报在内联执行、结果可确定断言。
     */
    SkillLoadRepository(JdbcTemplate jdbc, Executor executor) {
        this.jdbc = jdbc;
        this.executor = executor;
    }

    private static Executor defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "skill-load-report");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 记一次装配装载（异步、旁路）：把本次装配装载的技能 id 逐个上报。
     *
     * <p>空白 {@code skillId} 跳过；{@code agentId}/{@code skillIds} 为空则什么都不做。
     * 去重与写库都在后台线程完成，调用方（装配流程）立即返回，绝不被回流拖住。
     *
     * @param agentId     装载这些技能的 Agent（装配清单的 id）
     * @param workspaceId 所属工作区（可空）
     * @param sessionId   触发本次装配的会话（可空，只记最近一次）
     * @param skillIds    本次装载的技能 id 集合
     */
    @Override
    public void recordLoads(String agentId, String workspaceId, String sessionId,
                            Collection<String> skillIds) {
        if (agentId == null || agentId.isBlank() || skillIds == null || skillIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String skillId : skillIds) {
            if (skillId == null || skillId.isBlank()) {
                continue;
            }
            String key = agentId + '\u0000' + skillId;
            Long last = recent.get(key);
            if (last != null && now - last < DEDUP_TTL_MILLIS) {
                continue;   // 窗口内已上报：不重复写库
            }
            recent.put(key, now);
            executor.execute(() -> upsert(agentId, skillId, workspaceId, sessionId));
        }
    }

    /** 单条 upsert：失败只记日志——回流是旁路，不该回溅到装配。 */
    private void upsert(String agentId, String skillId, String workspaceId, String sessionId) {
        try {
            jdbc.update(SQL_UPSERT, agentId, skillId, workspaceId, sessionId);
        } catch (Exception e) {
            log.warn("[skill-load] 装载回流写库失败 agent={} skill={} : {}",
                    agentId, skillId, e.toString());
        }
    }
}