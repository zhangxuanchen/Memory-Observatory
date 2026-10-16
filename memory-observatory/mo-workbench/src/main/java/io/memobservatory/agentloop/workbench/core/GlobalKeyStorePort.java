/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】GlobalKeyStorePort.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】把静态工具 GlobalKeyStore 适配成可注入的 GlobalKeyPort，
 *            供 server 侧依赖倒置取用（架构重构 M2 / 步骤 9）。
 * 【核心改动】2026-10-12 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.memobservatory.contract.GlobalKeyPort;
import org.springframework.stereotype.Component;

/** {@link GlobalKeyStore} 的端口适配器：行为原样转发，只是把静态调用变成可注入的实例依赖。 */
@Component
public class GlobalKeyStorePort implements GlobalKeyPort {

    @Override
    public String plain(String provider) {
        return GlobalKeyStore.plain(provider);
    }
}