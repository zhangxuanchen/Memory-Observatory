/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】LlmCredentialPort.java（io.memobservatory.contract）
 * 【核心功能】大模型凭据 / 模型名的只读端口。由工作台（agentloop）提供实现，
 *            server 侧只依赖本接口——把「server → agentloop」的直接 import 换成依赖倒置。
 * 【核心改动】2026-10-12 新增（架构重构 M2 / 步骤 9，治 R3）。
 *******************************************************************************/
package io.memobservatory.contract;

/**
 * 大模型凭据 / 模型名的只读端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：{@code server/api} 的两个控制器要读 DashScope 的 key 与模型名，
 * 原先直接 import 工作台的 {@code AgentProperties}——于是 {@code server} 与 {@code agentloop}
 * 两个包互相 import（R3）。把「读配置」这一步抽象成接口后，{@code server} 只认本接口，
 * 工作台 {@code AgentProperties} 实现它，依赖方向变成「两侧都指向契约」，不再彼此纠缠。
 *
 * <p><b>落点沿革</b>：M2（步骤 9）时该接口先以**包** {@code io.memobservatory.contract} 落在 mo-server 内
 * ——因为两个 Java 服务各以自己目录为构建上下文，当时加跨模块 Maven 依赖构建不出来。
 * M3（步骤 13）已把本包提升为独立模块 {@code mo-contract}（依赖倒置的叶子），
 * 并把 mo-server 的 Dockerfile 改为仓库根上下文 + {@code mvn -pl mo-server -am}，
 * 于是「三角色只依赖契约」在编译期即可强制。
 *
 * <p><b>只收必要方法</b>：目前只暴露 DashScope 的 key 与模型名——两个调用方真正用到的就这两项，
 * 不把 {@code AgentProperties} 的其余配置面搬进来。
 */
public interface LlmCredentialPort {

    /** DashScope（Qwen）API Key；未配置返回 null。 */
    String getDashscopeApiKey();

    /** DashScope 模型名。 */
    String getDashscopeModel();
}