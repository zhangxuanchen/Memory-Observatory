/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】ContentLibrary.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】预装内容库服务：统一管理「规则 rules / 记忆 memory / 钩子 hook」三类配置的
 *            「全局预装基线 + 工作区私有 + Agent 自身」读写与合并（复用 SkillLibrary 的 yaml 即数据库模式）。
 * 【作用域】global 存 $MO_HOME/.workbench/<type>（所有工作区共享的预装基线）；
 *             workspace 存 $ROOT/.workbench/<type>（工作区私有）；
 *             agent 存 $ROOT/.workbench/agents/<agentId>/<type>（Agent 自身私有）。
 * 【装载语义】三级渐进披露：先装全局预装基线，再并工作区私有，最后并 Agent 自身（按 id 去重，后置覆盖前置）。
 * 【同名约束】同一 type 下 id 不允许在更高/其他作用域重复，创建时校验拒绝，保证合并即拼接、无覆盖歧义。
 * 【核心改动】2026-08-26 新增；2026-08-27 扩展到 Agent 自身级（三级渐进披露）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.Locale;

/**
 * 预装内容库：yaml 即数据库。既是管理端点的存储后端，也是 AgentFactory 装配时
 * 「全局 + 工作区」合并装载的解析来源。写盘用原子写避免半截文件。
 */
public class ContentLibrary {

    private static final Logger log = LoggerFactory.getLogger(ContentLibrary.class);

    private final WorkspaceManager manager;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ContentLibrary(WorkspaceManager manager) {
        this.manager = manager;
    }

    // ---------- 读取 ----------

    /** 指定 type 的全部全局预装项（所有工作区共享）。 */
    public List<ContentItem> global(String type) {
        return read(manager.globalConfigDir(type), "global");
    }

    /** 指定 type 的工作区私有项。 */
    public List<ContentItem> workspace(String type, Workspace ws) {
        return read(manager.workspaceConfigDir(ws, type), "workspace");
    }

    /** 指定 type 的某 Agent 自身私有项：$ROOT/.workbench/agents/<agentId>/<type>。 */
    public List<ContentItem> agent(String type, Workspace ws, String agentId) {
        return read(manager.agentConfigDir(ws, agentId, type), "agent");
    }

    /** 三级渐进披露合并视图：全局预装基线在前，工作区私有居中，Agent 自身在后，同 id 去重（去重后置覆盖前置）。 */
    public List<ContentItem> merge(String type, Workspace ws, String agentId) {
        LinkedHashMap<String, ContentItem> m = new LinkedHashMap<>();
        for (ContentItem it : global(type)) {
            if (it.id() != null && !it.id().isBlank()) m.put(it.id(), it);
        }
        for (ContentItem it : workspace(type, ws)) {
            if (it.id() != null && !it.id().isBlank()) m.put(it.id(), it);
        }
        for (ContentItem it : agent(type, ws, agentId)) {
            if (it.id() != null && !it.id().isBlank()) m.put(it.id(), it);
        }
        return new ArrayList<>(m.values());
    }

    // ---------- 写盘 / 删除 ----------

    /**
     * 保存一项预装配置：type 为 rules/memory/hook 之一；ws=null 记全局，否则记工作区私有，
     * 其中 ws 与 agentId 均非空时记 Agent 自身私有。id 为空时由 name 派生；写盘文件名 = &lt;id&gt;.yaml。
     * <p>创建时校验「同一 type 的 id 不允许与另一作用域重复」：自身级不得与工作区/全局重名，
     * 工作区级不得与全局重名，全局级反向扫描所有工作区及其 Agent 自身级；冲突抛
     * {@link IllegalArgumentException}。</p>
     */
    public ContentItem save(String type, String id, String name, String content,
                            String ref, Boolean enabled, Map<String, Object> config, Workspace ws,
                            String agentId) {
        String sid = (id == null || id.isBlank()) ? slug(name, type) : sanitize(id);
        if (existsInOtherScope(type, sid, ws, agentId)) {
            throw new IllegalArgumentException(
                    "已存在同名预装配置 [" + sid + "]（type=" + type + "，位于更高/其他作用域），请更换 id");
        }
        Path dir;
        String scope;
        if (ws == null) {
            dir = manager.globalConfigDir(type);
            scope = "global";
        } else if (agentId == null || agentId.isBlank()) {
            dir = manager.workspaceConfigDir(ws, type);
            scope = "workspace";
        } else {
            dir = manager.agentConfigDir(ws, agentId, type);
            scope = "agent";
        }
        ContentItem built = ContentItem.of(sid, type, name, content, ref, enabled, config, scope);
        try {
            Files.createDirectories(dir);
            atomicWrite(dir.resolve(sid + ".yaml"), built);
        } catch (IOException e) {
            throw new IllegalStateException("预装配置写入失败: " + sid, e);
        }
        return withScope(built, scope);
    }

    /** 删除指定 type 与作用域下的预装项（ws=null 删全局；ws+agentId 删 Agent 自身；否则删工作区私有）。 */
    public void delete(String type, String id, Workspace ws, String agentId) {
        Path dir;
        if (ws == null) {
            dir = manager.globalConfigDir(type);
        } else if (agentId == null || agentId.isBlank()) {
            dir = manager.workspaceConfigDir(ws, type);
        } else {
            dir = manager.agentConfigDir(ws, agentId, type);
        }
        try {
            Files.deleteIfExists(dir.resolve(sanitize(id) + ".yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("预装配置删除失败: " + id, e);
        }
    }

    // ---------- 内部 ----------

    /**
     * 跨作用域重名校验（返回 true 表示创建应被拒绝）：
     * 自身级检查「本工作区工作区级 + 全局」，工作区级检查「全局」，
     * 全局级反向扫描所有工作区及其 Agent 自身级。同作用域自身的既有同名不算（允许覆盖更新）。
     */
    private boolean existsInOtherScope(String type, String id, Workspace ws, String agentId) {
        if (ws == null) {
            for (Workspace w : manager.list()) {
                if (Files.exists(manager.workspaceConfigDir(w, type).resolve(id + ".yaml"))) {
                    return true;
                }
                for (String aid : w.manifest().agents()) {
                    if (Files.exists(manager.agentConfigDir(w, aid, type).resolve(id + ".yaml"))) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (agentId != null && !agentId.isBlank()) {
            if (Files.exists(manager.workspaceConfigDir(ws, type).resolve(id + ".yaml"))) {
                return true;
            }
            return Files.exists(manager.globalConfigDir(type).resolve(id + ".yaml"));
        }
        return Files.exists(manager.globalConfigDir(type).resolve(id + ".yaml"));
    }

    private List<ContentItem> read(Path dir, String scope) {
        List<ContentItem> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yaml"))
             .sorted()
             .forEach(p -> {
                 try {
                     out.add(readItem(p, scope));
                 } catch (IOException e) {
                     log.warn("预装配置解析失败 {}: {}", p, e.toString());
                 }
             });
        } catch (IOException e) {
            log.warn("预装配置目录读取失败 {}: {}", dir, e.toString());
        }
        return out;
    }

    private ContentItem readItem(Path p, String scope) throws IOException {
        ContentItem it = yaml.readValue(Files.readString(p, StandardCharsets.UTF_8), ContentItem.class);
        // yaml 缺 id（手工编写）时回退到文件名，保证每项都有可用 id
        String fileId = p.getFileName().toString();
        if (fileId.endsWith(".yaml")) {
            fileId = fileId.substring(0, fileId.length() - 5);
        }
        String id = (it.id() == null || it.id().isBlank()) ? fileId : it.id();
        return ContentItem.of(id, it.type(), it.name(), it.content(), it.ref(),
                it.enabled(), it.config(), scope);
    }

    private static ContentItem withScope(ContentItem it, String scope) {
        return ContentItem.of(it.id(), it.type(), it.name(), it.content(), it.ref(),
                it.enabled(), it.config(), scope);
    }

    /** 由 name 派生稳定的 id（type 参与兜底以保证空名时仍可区分）。 */
    private static String slug(String name, String type) {
        String s = (name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-|-$)", "");
        return s.isBlank() ? (type == null ? "item" : type) : s;
    }

    /** 保留 Unicode 字母/数字/._- 的宽松清洗（读取/删除用，兼容手写文件名，防路径穿越）。 */
    private static String sanitize(String id) {
        String s = (id == null ? "" : id.trim()).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return s.isBlank() ? "item" : s;
    }

    private void atomicWrite(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, yaml.writeValueAsString(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}