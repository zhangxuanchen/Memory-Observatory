/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】WorkspaceManager.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】工作区管理（文件系统即数据库）：打开/登记用户选定目录，读写
 *            $ROOT/.workbench/manifest.json；维护 id→root 索引（便于按 id 取工作区）；
 *            Agent 清单的读写与索引维护。所有写入用原子写，避免半截文件。
 * 【核心改动】2026-08-23 新增；2026-08-23 元数据目录 .mo-agent → .workbench，
 *            Agent 配置收进 $ROOT/.workbench/agents/<agentId>/（workspace-agent-design.md 第 2 章）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.memobservatory.agentloop.workspace.manifest.AgentManifest;
import io.memobservatory.agentloop.workspace.manifest.WorkspaceManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工作区管理器：以文件系统为数据库，不依赖外部 DB 即可独立运行。
 * 索引（id→root）落在 {@code <base>/workspaces/index.json}。
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 元数据目录名（隐藏于用户工作区根下）。 */
    public static final String META_DIR = ".workbench";

    /** 工作区内 Agent 配置存放的子目录（相对 META_DIR）。 */
    public static final String AGENTS_DIR = "agents";

    /** 技能配置子目录名，与 HarnessAgent 标准 {@code skills/} 对齐（原名单数 skill）。 */
    public static final String SKILLS_DIR = "skills";

    /** 子 Agent 配置子目录名，与 HarnessAgent 标准 {@code subagents/} 对齐（原名单数 subagent）。 */
    public static final String SUBAGENTS_DIR = "subagents";

    /** 工作区自有的配置子目录（相对 META_DIR）：subagents/skills/hook/memory/rules/runs。 */
    public static final String[] WORKSPACE_SUBDIRS = {SUBAGENTS_DIR, SKILLS_DIR, "hook", "memory", "rules", "runs"};

    /** 每个 Agent 自身的配置子目录（相对 agents/&lt;agentId&gt;/）：存该 Agent 私有的 skills/hook/memory/rules/runs。
     *  与工作区级同构，与全局/工作区一起参与「全局→工作区→自身」三级渐进披露加载。 */
    public static final String[] AGENT_SUBDIRS = {SKILLS_DIR, "hook", "memory", "rules", "runs"};

    /** 全局工作台目录（用户级）：保存全局可用的 Skill 等配置，所有工作区共享。环境变量 MO_HOME 可覆盖。 */
    public static final String GLOBAL_DIR = System.getenv().getOrDefault("MO_HOME", System.getProperty("user.home") + "/.workbench");

    /** 全局可用的 Skill 目录（相对 GLOBAL_DIR）。 */
    public static final String GLOBAL_SKILL_DIR = GLOBAL_DIR + "/" + SKILLS_DIR;

    /** 工作区主 Agent（工作区管理员）固定 id，与工作区绑定，创建即存在、不可删除。 */
    public static final String ADMIN_AGENT_ID = "admin";
    /** 工作区主 Agent 固定名称。 */
    public static final String ADMIN_AGENT_NAME = "工作区经理";

    private final Path indexDir;
    private final Path indexFile;
    private final Map<String, String> index = new LinkedHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    public WorkspaceManager(Path registryBase) {
        this.indexDir = registryBase.resolve("workspaces");
        this.indexFile = indexDir.resolve("index.json");
        loadIndex();
    }

    // ---------- 工作区 ----------

    /**
     * 打开或登记一个工作区：root 必须是已存在的目录。已登记则读回清单，否则新建清单。
     * 校验失败抛 {@link IllegalArgumentException}（即 Agent 的边界不被伪造路径突破）。
     */
    public Workspace openOrCreate(String root, String name) {
        Path r = Path.of(require(root, "root")).toAbsolutePath().normalize();
        if (!Files.isDirectory(r)) {
            throw new IllegalArgumentException("工作区路径不存在或不是目录: " + r);
        }
        Path meta = r.resolve(META_DIR);
        Path mf = meta.resolve("manifest.json");
        // 开启前先做一次幂等的目录改名对齐（skill→skills、subagent→subagents），让新旧命名共存过渡
        migrateWorkspaceDirs(meta);
        WorkspaceManifest wm;
        try {
            // 同一 root 已登记则直接复用（避免同路径重复注册产生重复工作区）
            for (Map.Entry<String, String> e : index.entrySet()) {
                if (r.toString().equals(e.getValue())) {
                    return new Workspace(readJson(mf, WorkspaceManifest.class), r);
                }
            }
            if (Files.exists(mf)) {
                wm = readJson(mf, WorkspaceManifest.class);
            } else {
                Files.createDirectories(meta);
                // 新建工作区：创建 subagents / skills / hook / memory / rules / runs + agents 配置目录
                ensureWorkspaceDirs(meta);
                String id = genId("ws");
                wm = WorkspaceManifest.create(id, name == null || name.isBlank() ? r.getFileName().toString() : name);
                atomicWrite(mf, wm);
            }
        } catch (IOException e) {
            throw new IllegalStateException("工作区清单读写失败: " + r, e);
        }
        index.put(wm.id(), r.toString());
        saveIndex();
        Workspace ws = new Workspace(wm, r);
        ensureAdmin(ws);
        return ws;
    }

    /** 若工作区缺主 Agent（工作区管理员），补建一个，与工作区绑定、不可删除。 */
    private void ensureAdmin(Workspace ws) {
        try {
            if (ws.manifest().agents().contains(ADMIN_AGENT_ID)) {
                return;
            }
            createAdmin(ws);
        } catch (Exception e) {
            log.warn("工作区主 Agent 创建失败 {}: {}", ws.root(), e.toString());
        }
    }

    /** 按 id 取出工作区；未登记抛异常。 */
    public Workspace byId(String id) {
        String root = index.get(require(id, "workspaceId"));
        if (root == null) {
            throw new IllegalArgumentException("工作区不存在: " + id);
        }
        return openRoot(Path.of(root));
    }

    /** 列出全部已登记工作区（清单缺失的跳过），供前端下拉选择。 */
    public List<Workspace> list() {
        List<Workspace> out = new ArrayList<>();
        for (Map.Entry<String, String> e : index.entrySet()) {
            try {
                out.add(openRoot(Path.of(e.getValue())));
            } catch (Exception ex) {
                log.warn("跳过缺失/损坏的工作区 {}: {}", e.getKey(), ex.toString());
            }
        }
        return out;
    }

    /** 读取指定 root 下的工作区清单；缺失抛异常。 */
    private Workspace openRoot(Path root) {
        Path mf = root.toAbsolutePath().normalize().resolve(META_DIR).resolve("manifest.json");
        try {
            if (!Files.exists(mf)) {
                throw new IllegalArgumentException("工作区清单缺失: " + root);
            }
            return new Workspace(readJson(mf, WorkspaceManifest.class), root);
        } catch (IOException e) {
            throw new IllegalStateException("工作区清单读取失败: " + root, e);
        }
    }

    // ---------- Agent ----------

    /** 枚举工作区下的全部 Agent 清单（丢失的项跳过）。缺主 Agent 先补建。 */
    public List<AgentManifest> listAgents(Workspace ws) {
        ensureAdmin(ws);
        List<AgentManifest> out = new ArrayList<>();
        for (String agentId : ws.manifest().agents()) {
            try {
                AgentManifest am = readAgent(ws, agentId);
                // 主 Agent 名字固定，读取时强制覆盖（兼容历史工作区老名称）
                if (am.admin()) {
                    am = am.withName(ADMIN_AGENT_NAME, am.description());
                }
                out.add(am);
            } catch (Exception e) {
                log.warn("跳过缺失/损坏的 Agent 清单 {}: {}", agentId, e.toString());
            }
        }
        return out;
    }

    /** 读取指定 Agent 清单；缺失抛异常。 */
    public AgentManifest readAgent(Workspace ws, String agentId) {
        Path mf = agentMeta(ws, require(agentId, "agentId")).resolve("manifest.json");
        try {
            if (!Files.exists(mf)) {
                throw new IllegalArgumentException("Agent 清单缺失: " + agentId);
            }
            return readJson(mf, AgentManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Agent 清单读取失败: " + agentId, e);
        }
    }

    /** 在工作区内创建一个 Agent 清单并更新工作区索引。 */
    public AgentManifest createAgent(Workspace ws, String name, String description) {
        return createAgent(ws, name, description, List.of());
    }

    /** 在工作区内创建一个 Agent 清单（可挂载能力包 skills）并更新工作区索引。 */
    public AgentManifest createAgent(Workspace ws, String name, String description, List<String> skills) {
        String id = genId("a");
        AgentManifest am = AgentManifest.create(id, name, description, skills);
        registerAgent(ws, am);
        return am;
    }

    /** 预置工作区主 Agent（工作区管理员）：固定 id=admin，admin=true，与工作区绑定、不可删除。 */
    public AgentManifest createAdmin(Workspace ws) {
        AgentManifest am = AgentManifest.createAdmin(ADMIN_AGENT_ID, ADMIN_AGENT_NAME);
        registerAgent(ws, am);
        return am;
    }

    /** 把 Agent 清单写入其目录并加入工作区 agents 索引。
     *  创建时同时创建该 Agent 私有的 skill/hook/memory/rules/runs 配置子目录，
     *  使其可独立承载「全局→工作区→自身」三级装载中的「自身」这一级。 */
    private void registerAgent(Workspace ws, AgentManifest am) {
        Path dir = agentMeta(ws, am.id());
        try {
            Files.createDirectories(dir);
            for (String sub : AGENT_SUBDIRS) {
                Files.createDirectories(dir.resolve(sub));
            }
            atomicWrite(dir.resolve("manifest.json"), am);
            // 更新工作区 agents 索引并写盘
            List<String> agents = ws.manifest().agents();
            if (!agents.contains(am.id())) {
                agents.add(am.id());
            }
            atomicWrite(ws.root().resolve(META_DIR).resolve("manifest.json"), ws.manifest());
        } catch (IOException e) {
            throw new IllegalStateException("Agent 清单创建失败: " + am.id(), e);
        }
    }

    /** 重命名（或清空名）工作区内 Agent 的展示名，写回其清单并返回更新后清单。 */
    public AgentManifest renameAgent(Workspace ws, String agentId, String name, String description) {
        require(agentId, "agentId");
        // 工作区管理员名字固定、不可改名
        AgentManifest am = readAgent(ws, agentId);
        if (am != null && am.admin()) {
            throw new IllegalArgumentException("工作区经理不可改名");
        }
        AgentManifest upd = am.withName(name, description);
        try {
            atomicWrite(agentMeta(ws, agentId).resolve("manifest.json"), upd);
        } catch (IOException e) {
            throw new IllegalStateException("Agent 清单更新失败: " + agentId, e);
        }
        return upd;
    }

    /** 从工作区 agents 索引移除该 Agent（软删除，保留清单目录），并清理其会话与激活状态。 */
    public void deleteAgent(Workspace ws, String agentId) {
        String aid = require(agentId, "agentId");
        // 工作区管理员与工作区绑定、不可删除
        AgentManifest am = readAgent(ws, aid);
        if (am != null && am.admin()) {
            throw new IllegalArgumentException("工作区经理不可删除");
        }
        ws.manifest().agents().remove(aid);
        try {
            atomicWrite(ws.root().resolve(META_DIR).resolve("manifest.json"), ws.manifest());
        } catch (IOException e) {
            throw new IllegalStateException("Agent 删除失败: " + agentId, e);
        }
        // 一并清理该 Agent 的会话记录；若它是当前激活会话则清空激活状态
        Map<String, Object> conv = loadConversations(ws);
        if (!conv.isEmpty()) {
            Object msgs = conv.get("msgs");
            if (msgs instanceof Map) {
                ((Map<?, ?>) msgs).remove(aid);
            }
            if (aid.equals(conv.get("active"))) {
                conv.put("active", null);
            }
            saveConversations(ws, conv);
        }
    }

    // ---------- 会话持久化（Agent 即会话） ----------

    /** 会话文件：$ROOT/.workbench/conversations.json，schema 同前端 {active, msgs:{agentId:[{role,text,ts}]}}。 */
    private Path conversationsFile(Workspace ws) {
        return ws.root().resolve(META_DIR).resolve("conversations.json");
    }

    /** 读取工作区会话；缺失/损坏返回空。 */
    public Map<String, Object> loadConversations(Workspace ws) {
        Path f = conversationsFile(ws);
        try {
            if (Files.exists(f)) {
                Map<String, Object> c = om.readValue(Files.readString(f, StandardCharsets.UTF_8), new TypeReference<>() {
                });
                return c == null ? Map.of() : c;
            }
        } catch (IOException e) {
            log.warn("会话读取失败 {}: {}", f, e.toString());
        }
        return Map.of();
    }

    /** 覆盖写工作区会话（原子写；覆盖前先备份旧文件，避免误覆盖即丢数据）。 */
    public void saveConversations(Workspace ws, Map<String, Object> conv) {
        try {
            backupBeforeWrite(conversationsFile(ws));
            atomicWrite(conversationsFile(ws), conv == null ? Map.of() : conv);
        } catch (IOException e) {
            log.warn("会话写入失败: {}", e.toString());
        }
    }

    /** Agent 目录：收进工作区元数据目录下的 agents/（根 → 工作区 → .workbench → agents → Agent）。 */
    private Path agentMeta(Workspace ws, String agentId) {
        return ws.root().resolve(META_DIR).resolve(AGENTS_DIR).resolve(sanitize(agentId));
    }

    /** Agent 配置目录（公开版）：供密文库等按目录旁路读写不受清单迁移影响的独立文件。 */
    public Path agentMetaDir(Workspace ws, String agentId) {
        return agentMeta(ws, require(agentId, "agentId"));
    }

    /** Agent 自身某个配置子目录：$ROOT/.workbench/agents/<agentId>/<sub>（sub=skill/hook/memory/rules/runs）。 */
    public Path agentConfigDir(Workspace ws, String agentId, String sub) {
        return agentMeta(ws, require(agentId, "agentId")).resolve(sanitize(sub));
    }

    /** 新建工作区时创建其自有配置子目录（含 Agent 配置目录）。 */
    private void ensureWorkspaceDirs(Path meta) throws IOException {
        for (String sub : WORKSPACE_SUBDIRS) {
            Files.createDirectories(meta.resolve(sub));
        }
        Files.createDirectories(meta.resolve(AGENTS_DIR));
    }

    /** 工作区特有的某个配置子目录（skills / hook / subagents / memory）。 */
    public Path workspaceConfigDir(Workspace ws, String sub) {
        return ws.root().resolve(META_DIR).resolve(sanitize(sub));
    }

    /** 全局 Skill 目录（用户级 .workbench/skills，所有工作区共享）。 */
    public Path globalSkillDir() {
        return Path.of(GLOBAL_SKILL_DIR);
    }

    /** 工作区 Skill 目录（$ROOT/.workbench/skills，工作区私有）。
     *  访问时兜底合并历史单数目录 .workbench/skill/ 里遗留的文件（早于命名对齐的发现技能等会落在这里）。 */
    public Path workspaceSkillDir(Workspace ws) {
        Path dir = workspaceConfigDir(ws, SKILLS_DIR);
        Path legacy = ws.root().resolve(META_DIR).resolve("skill");
        if (!Path.of(dir.toString()).equals(legacy)) {
            moveDirIfPresent(legacy, dir);
        }
        return dir;
    }

    /** 全局工作台下的某类配置目录（rules / memory / hook / skills…），所有工作区共享的预装基线。 */
    public Path globalConfigDir(String sub) {
        return Path.of(GLOBAL_DIR).resolve(sanitize(sub));
    }

    /** 确保全局工作台目录存在（含已支持的预装配置子目录）。 */
    public void ensureGlobalDir() throws IOException {
        migrateLegacyDir(Path.of(GLOBAL_DIR));
        Files.createDirectories(Path.of(GLOBAL_DIR));
        Files.createDirectories(globalSkillDir());
        Files.createDirectories(globalConfigDir("rules"));
        Files.createDirectories(globalConfigDir("memory"));
        Files.createDirectories(globalConfigDir("hook"));
    }

    // ---------- Harness 命名对齐：单数→复数 兼容迁移 ----------

    /** 把历史单数目录（skill / subagent）原地改名为 HarnessAgent 一致的名（skills / subagents），幂等。 */
    private void migrateLegacyDir(Path parent) {
        if (parent == null) {
            return;
        }
        moveDirIfPresent(parent.resolve("skill"), parent.resolve(SKILLS_DIR));
        moveDirIfPresent(parent.resolve("subagent"), parent.resolve(SUBAGENTS_DIR));
    }

    /** 迁移工作区与每个 Agent 目录下的历史单数配置子目录。 */
    private void migrateWorkspaceDirs(Path meta) {
        migrateLegacyDir(meta);
        Path agents = meta.resolve(AGENTS_DIR);
        if (!Files.isDirectory(agents)) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.list(agents)) {
            s.filter(Files::isDirectory).forEach(this::migrateLegacyDir);
        } catch (IOException e) {
            log.warn("迁移 Agent 子目录失败 {}: {}", meta, e.toString());
        }
    }

    /** 目标不存在时把源目录改名过去，原子；目标已存在则忽略（避免覆盖新数据）。 */
    private static void moveDirIfPresent(Path from, Path to) {
        if (!Files.isDirectory(from)) {
            return;
        }
        try {
            if (!Files.exists(to)) {
                Files.move(from, to); // 目标不存在，整目录改名
            } else {
                // 目标已存在（新技能已写入），逐文件合并，同名不覆盖
                try (java.util.stream.Stream<Path> s = Files.list(from)) {
                    s.filter(Files::isRegularFile)
                     .filter(p -> !Files.exists(to.resolve(p.getFileName())))
                     .forEach(p -> {
                         try {
                             Files.move(p, to.resolve(p.getFileName()));
                         } catch (IOException e) {
                             log.warn("技能文件合并失败 {} -> {}: {}", p, to, e.toString());
                         }
                     });
                }
            }
        } catch (IOException e) {
            log.warn("目录合并失败 {} -> {}: {}", from, to, e.toString());
        }
    }

    // ---------- 工具 ----------

    private String genId(String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    private String sanitize(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String require(String s, String what) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(what + " 不能为空");
        }
        return s;
    }

    private <T> T readJson(Path p, Class<T> type) throws IOException {
        return om.readValue(Files.readString(p, StandardCharsets.UTF_8), type);
    }

    /** 覆盖写前备份：若目标已存在且有内容，先复制为同名 .bak（仅保留最近一份）。 */
    private void backupBeforeWrite(Path target) {
        if (target == null || !Files.exists(target)) return;
        try {
            if (Files.size(target) > 0) {
                Files.copy(target, target.resolveSibling(target.getFileName().toString() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("写前备份失败 {}: {}", target, e.toString());
        }
    }

    private void atomicWrite(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, om.writeValueAsString(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadIndex() {
        try {
            if (Files.exists(indexFile)) {
                index.putAll(om.readValue(Files.readString(indexFile, StandardCharsets.UTF_8), Map.class));
            } else {
                Files.createDirectories(indexDir);
            }
        } catch (IOException e) {
            log.warn("工作区索引加载失败，将重建: {}", e.toString());
        }
    }

    private void saveIndex() {
        try {
            Files.createDirectories(indexDir);
            atomicWrite(indexFile, index);
        } catch (IOException e) {
            log.warn("工作区索引保存失败: {}", e.toString());
        }
    }
}