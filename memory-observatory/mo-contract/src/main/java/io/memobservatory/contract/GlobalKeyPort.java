/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】GlobalKeyPort.java（io.memobservatory.contract）
 * 【核心功能】界面配置的全局模型 AK 只读端口。由工作台提供实现（适配 GlobalKeyStore），
 *            server 侧只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M2 / 步骤 9，治 R3）。
 *******************************************************************************/
package io.memobservatory.contract;

/**
 * 全局模型 AK（界面配置）的只读端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：与 {@link LlmCredentialPort} 同一根因——{@code server/api} 的控制器要
 * 按 provider 取界面配置的密钥（取钥优先级：界面配置 → 环境变量/兜底文件），原先直接静态调用
 * 工作台的 {@code GlobalKeyStore}。抽象成接口后，{@code server} 不再 import {@code agentloop}。
 *
 * <p><b>为什么是实例接口而非静态</b>：原 {@code GlobalKeyStore} 是纯静态工具，无法被注入与替换。
 * 端口化后由工作台提供一个 Spring 适配器实现，便于测试替换与边界固化。
 */
public interface GlobalKeyPort {

    /** 取某 provider 界面配置的全局 AK 明文；未配置返回 null，不抛异常。 */
    String plain(String provider);
}