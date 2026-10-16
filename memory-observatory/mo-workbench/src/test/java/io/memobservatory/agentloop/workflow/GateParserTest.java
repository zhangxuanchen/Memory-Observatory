/*******************************************************************************
 * 【模块】Agent 工作流 Workflow
 * 【文件】GateParserTest.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】PM 产出文本 → 门禁清单/任务清单的解析回归：格式内必中、格式外必回落，
 *            绝不因解析失败阻断流程（回落 DEFAULTS / 空清单是设计行为）。
 * 【核心改动】2026-10-12 架构审视「workbench 零测试」补齐。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GateParser} 行为测试。格式契约见类 javadoc 的示例块。
 */
class GateParserTest {

    /** 标准门禁块：两类各一条，kind 与名称都对得上。 */
    @Test
    void 解析门禁清单() {
        String pm = """
                【门禁规则】
                - 后端构建 | backend | 说明：以 mvn 退出码为准
                - 前端构建 | frontend | 说明：以 npm 退出码为准
                """;
        List<GateSpec> gates = GateParser.parseGates(pm);
        assertThat(gates).hasSize(2);
        assertThat(gates.get(0).kind()).isEqualTo(GateSpec.KIND_BACKEND);
        assertThat(gates.get(0).name()).isEqualTo("后端构建");
        assertThat(gates.get(1).kind()).isEqualTo(GateSpec.KIND_FRONTEND);
    }

    /** 空产出 / 无可识别行：都回落到默认两条门禁——流程不中断是硬要求。 */
    @Test
    void 无匹配回落默认() {
        assertThat(GateParser.parseGates(null)).isEqualTo(GateSpec.DEFAULTS);
        assertThat(GateParser.parseGates("  ")).isEqualTo(GateSpec.DEFAULTS);
        assertThat(GateParser.parseGates("随便一段说明文字\n没有管道格式")).isEqualTo(GateSpec.DEFAULTS);
    }

    /** kind 只认 backend/frontend 两个字面值；其它（如 docker）不算门禁行。 */
    @Test
    void 未知kind不被误认() {
        assertThat(GateParser.parseGates("- 部署 | docker | 容器构建"))
                .isEqualTo(GateSpec.DEFAULTS);
    }

    /** 任务拆解：`- [ID] 描述` 行提取 id；非任务行（普通 bullet/说明）不进黑板。 */
    @Test
    void 解析任务拆解() {
        String pm = """
                【任务拆解】
                - [BACK-1] 实现用户模块 CRUD，checkpoint：mvn test 通过
                - [FE-1] 列表页对接 /api/users
                - 这不是任务行
                裸文本 [X-1] 也要跳过
                """;
        List<TaskBoard.Task> tasks = GateParser.parseTasks(pm);
        assertThat(tasks).extracting(TaskBoard.Task::id).containsExactly("BACK-1", "FE-1");
        assertThat(tasks.get(0).description()).contains("实现用户模块 CRUD");
    }

    /** 空产出 → 空清单（表示未拆解，走整体式开发；与门禁的 DEFAULTS 回落刻意不同）。 */
    @Test
    void 无任务返回空清单() {
        assertThat(GateParser.parseTasks(null)).isEmpty();
        assertThat(GateParser.parseTasks("没有方括号任务")).isEmpty();
    }
}
