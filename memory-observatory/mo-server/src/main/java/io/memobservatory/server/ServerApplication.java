package io.memobservatory.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Memory Observatory 服务端入口。
 * 单进程内含：OTLP 接收、内存队列削峰、批量写库、REST 查询、Agent 工作台（对话）。
 * 通过 scanBasePackages 同时扫描 io.memobservatory.server、io.memobservatory.agentloop.workbench
 * 与 io.memobservatory.agentloop.workspace（WorkspaceController）。
 * AgentProperties 由 WorkbenchConfig 通过 @EnableConfigurationProperties 绑定 mo.agent.*。
 */
@SpringBootApplication(scanBasePackages = {
        "io.memobservatory.server",
        "io.memobservatory.agentloop.workbench",
        "io.memobservatory.agentloop.workspace",
        "io.memobservatory.agentloop.workflow"
})
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
