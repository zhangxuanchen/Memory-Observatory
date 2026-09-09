"""Memory Observatory OTel 导出 demo。

演示 Agent 接入：构造记忆事件 + 上下文快照，上报到本地 Java 服务端。
前提：docker-compose up 已启动（mo-server 监听 4318）。

运行（在项目根目录）：
    python examples/otel_demo.py

随后打开 Dashboard http://localhost:5173 ，Agent 填 demo-agent 查看数据。
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import time
from mo_sdk import MemoryObserver, MemoryEvent, ContextSnapshot, OTelSpanExporter

SESSION = "demo-session-001"
AGENT = "demo-agent"


def main():
    observer = MemoryObserver(agent_id=AGENT)
    observer.add_exporter(OTelSpanExporter(
        endpoint=os.environ.get("MO_OTLP_ENDPOINT", "http://localhost:4318/v1/traces"),
        service_name="demo-agent",
    ))

    # 模拟一轮 Agent 对话的记忆操作（覆盖四种归一操作）
    ops = [
        MemoryEvent(operation="store", layer="session",
                    memory_key="user_pref", memory_summary="用户偏好深色模式",
                    token_count=180, session_id=SESSION, agent_id=AGENT,
                    metadata={"source": "user_input"}),                       # → WRITE
        MemoryEvent(operation="retrieve", layer="prompt",
                    memory_key="MEMORY.md", memory_summary="检索项目记忆文件",
                    token_count=1200, session_id=SESSION, agent_id=AGENT,
                    metadata={"kv_cache_check": True, "kv_cache_hit": True}),  # → READ
        MemoryEvent(operation="store", layer="skill",
                    memory_key="skill_result", memory_summary="工具执行结果缓存更新",
                    token_count=450, session_id=SESSION, agent_id=AGENT,
                    metadata={"is_update": True}),                            # → UPDATE
        MemoryEvent(operation="consolidate", layer="session",
                    memory_key="session_log", memory_summary="会话日志压缩",
                    token_count=800, session_id=SESSION, agent_id=AGENT,
                    metadata={"compression": True}),                          # → UPDATE
        MemoryEvent(operation="forget", layer="provider",
                    memory_key="expired_ctx", memory_summary="过期上下文清理",
                    token_count=60, session_id=SESSION, agent_id=AGENT),      # → EXPIRE
    ]
    for ev in ops:
        observer.record_event(ev)
        time.sleep(0.05)  # 让时间戳略有差异，便于时间线展示

    # 五区快照（合计 8000）
    observer.record_snapshot(ContextSnapshot(
        agent_id=AGENT, session_id=SESSION,
        total_tokens=8000, system_tokens=800, task_tokens=400,
        memory_tokens=1200, tool_history_tokens=3200, free_tokens=2400,
        compression_count=1, last_compression_ratio=0.6,
    ))

    # 手动触发上报（旁路容错，失败也不抛）
    results = observer.export()
    print("上报结果:", results)
    print(f"已上报 {len(ops)} 事件 + 1 快照，session={SESSION}")
    print("打开 Dashboard http://localhost:5173 ，Agent 填 demo-agent 查看")


if __name__ == "__main__":
    main()
