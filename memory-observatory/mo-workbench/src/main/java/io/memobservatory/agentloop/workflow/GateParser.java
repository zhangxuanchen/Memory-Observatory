/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】GateParser.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】解析 PM（项目经理）规划产出中的结构化门禁规则与任务拆解，供门禁与
 *            任务黑板驱动。无法解析时回落 GateSpec.DEFAULTS / 空任务，不阻断流程。
 * 【核心改动】2026-08-24 新增。PM 只需按要求输出块级文本，利润文本硬编码词表。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 门禁 / 任务解析器。解析规则（都在独立块内自行出现即可）：
 * <pre>
 * 【门禁规则】
 * - 后端构建 | backend | 说明：以 mvn 退出码为准
 * - 前端构建 | frontend | 说明：以 npm 退出码为准
 *
 * 【任务拆解】
 * - [BACK-1] 实现用户模块 CRUD，checkpoint：mvn test 通过
 * - [FE-1] 列表页对接 /api/users，checkpoint：npm run build 通过
 * </pre>
 * 行以 `-` 开头；门禁行第 2 段必须为 backend/frontend。
 */
public final class GateParser {

    private GateParser() {
    }

    private static final Pattern GATE_LINE =
            Pattern.compile("^\\s*[-*]\\s*[^|]+\\|\\s*(backend|frontend)\\s*\\|");
    private static final Pattern TASK_LINE =
            Pattern.compile("^\\s*[-*]\\s*\\[([A-Za-z0-9_\\-]+)\\]\\s*(.+?)(?:,|；|，|:|：)?\\s*$");

    /** 解析 PM 产出中的门禁清单；无匹配回落默认（后端+前端）。 */
    public static List<GateSpec> parseGates(String pmOutput) {
        List<GateSpec> out = new ArrayList<>();
        if (pmOutput == null || pmOutput.isBlank()) {
            return GateSpec.DEFAULTS;
        }
        for (String line : pmOutput.split("\\R")) {
            Matcher m = GATE_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            String kind = m.group(1);
            String name = line.replaceFirst("^\\s*[-*]\\s*", "")
                    .split("\\|")[0].trim();
            if (kind.equals(GateSpec.KIND_BACKEND)) {
                out.add(GateSpec.backend(name));
            } else {
                out.add(GateSpec.frontend(name));
            }
        }
        return out.isEmpty() ? GateSpec.DEFAULTS : out;
    }

    /** 解析 PM 产出中的任务拆解；无匹配返回空列表（表示未拆解，走整体式开发）。 */
    public static List<TaskBoard.Task> parseTasks(String pmOutput) {
        List<TaskBoard.Task> out = new ArrayList<>();
        if (pmOutput == null || pmOutput.isBlank()) {
            return out;
        }
        for (String line : pmOutput.split("\\R")) {
            String t = line.trim();
            if (!(t.startsWith("- ") || t.startsWith("* "))) {
                continue;
            }
            Matcher m = TASK_LINE.matcher(t);
            if (m.matches()) {
                String id = m.group(1);
                String desc = m.group(2).trim();
                if (!desc.isBlank()) {
                    out.add(new TaskBoard.Task(id, desc));
                }
            }
        }
        return out;
    }
}