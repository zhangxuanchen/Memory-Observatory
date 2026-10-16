/*******************************************************************************
 * 【模块】Agent 工作流 Workflow
 * 【文件】WorkflowTemplateInitializer.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】把 java-mvn / frontend 两个模板清单预置到工作区 $ROOT/.workbench/agents/ 下，
 *            供 createRole 复用。已存在则不覆盖（可被用户自定义）。
 * 【核心改动】2026-08-24 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 工作流模板清单初始化器。懒执行：仅在运行工作流的目标工作区写入两个模板 manifest
 * （对应设计文档第 8 章示例，tools 按模板裁剪）。
 */
public class WorkflowTemplateInitializer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateInitializer.class);

    private final WorkspaceManager workspaceManager;
    private final ObjectMapper om = new ObjectMapper();

    public WorkflowTemplateInitializer(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /** 确保目标工作区包含 backend-java / frontend-web 两个模板清单（缺失才写入）。 */
    public void ensureTemplates(String workspaceId) {
        Workspace ws = workspaceManager.byId(workspaceId);
        writeIfMissing(ws, WorkflowConfig.TEMPLATE_JAVA_MVN, javaMavenManifest());
        writeIfMissing(ws, WorkflowConfig.TEMPLATE_FRONTEND, frontendManifest());
    }

    private void writeIfMissing(Workspace ws, String templateId, Map<String, Object> manifest) {
        try {
            Path dir = ws.root().toAbsolutePath().normalize()
                    .resolve(WorkspaceManager.META_DIR)
                    .resolve(WorkspaceManager.AGENTS_DIR)
                    .resolve(templateId);
            Path target = dir.resolve("manifest.json");
            if (Files.exists(target)) {
                return;
            }
            Files.createDirectories(dir);
            atomicWrite(target, manifest);
            log.info("[workflow] template manifest reset: {}/{}", ws.root().getFileName(), templateId);
        } catch (IOException e) {
            log.warn("[workflow] 模板清单写入失败 {}: {}", templateId, e.toString());
        }
    }

    private Map<String, Object> javaMavenManifest() {
        // Map.of 禁止 null 值，model 缺省需用 LinkedHashMap 拼装
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema", 1);
        m.put("id", WorkflowConfig.TEMPLATE_JAVA_MVN);
        m.put("name", "Java-Maven 后端开发");
        m.put("description", "Java-Maven 后端编程模板：改代码、补依赖、写 Junit、mvn 构建");
        m.put("systemPrompt",
                "你是 Java-Maven 后端开发 Agent。工作区是 Maven 工程：先读 pom.xml 与目录结构；" +
                "改动遵循增量最小化；写 Junit 用例；用 `mvn -q test` 验证并以退出码为准；构建产物放 target/。");
        m.put("tools", Map.of(
                "readFile", true, "writeFile", true, "editFile", true,
                "bash", true, "webSearch", false, "askUser", true));
        m.put("skills", List.of());
        m.put("subagents", List.of());
        m.put("hooks", List.of());
        return m;
    }

    private Map<String, Object> frontendManifest() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema", 1);
        m.put("id", WorkflowConfig.TEMPLATE_FRONTEND);
        m.put("name", "前端开发");
        m.put("description", "前端编程模板：改页面/组件、对接接口、npm 构建/lint");
        m.put("systemPrompt",
                "你是前端开发 Agent。先识别包管理器（npm/pnpm）并读 package.json；" +
                "改动遵循增量最小化；用 `npm run build`（必要时 lint）验证并以退出码为准。");
        m.put("tools", Map.of(
                "readFile", true, "writeFile", true, "editFile", true,
                "bash", true, "webSearch", false, "askUser", true));
        m.put("skills", List.of());
        m.put("subagents", List.of());
        m.put("hooks", List.of());
        return m;
    }

    private void atomicWrite(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, om.writeValueAsString(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}