/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】EventStreamPort.java（io.memobservatory.contract）
 * 【核心功能】记忆事件的只读流式导出端口。由 server（EventRepository）实现，
 *            mo-support 的导出服务（EventExportService）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M3 / 步骤 14，防 mo-support ⇄ mo-server 循环依赖）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * 记忆事件的只读流式导出端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：导出服务 {@code EventExportService} 原直接 import server 的
 * {@code EventRepository}。M3 把 export 下沉到 mo-support 后，直接 import 会造成
 * {@code mo-support → mo-server} 与 mo-server → mo-support 的 Maven 循环依赖。
 *
 * <p><b>为什么用 {@code ResultSet} 回调</b>：导出窗口动辄几十万行，必须逐行流式处理，
 * 不能先物化成 List（会把堆打满）。{@code java.sql.ResultSet} 属 JDK，用它作回调参数
 * 不破坏「契约只依赖 JDK」这条守则。
 *
 * <p><b>只读</b>：{@code memory_events} 的 owner 仍是 mo-server（表所有权矩阵 §4.4），
 * 调用方不得经此写库。
 */
public interface EventStreamPort {

    /** 逐行回调。签名允许抛 {@code SQLException}，与 JDBC 读取天然对齐。 */
    @FunctionalInterface
    interface RowSink {
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * 按条件流式读取事件（{@code ts} 升序，最多 {@code limit} 行）。
     * 空串/null 的过滤参数视为「不限」。
     */
    void streamEventsForExport(Instant from, Instant to, String agentId, String sessionId,
                               String traceId, int limit, RowSink sink);
}