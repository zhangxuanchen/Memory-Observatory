# Memory Observatory (MindSprout)

> 面向 AI Agent 记忆系统的一体化可观测性与智能工作台平台

Memory Observatory（品牌名 MindSprout）是一套聚焦 AI Agent 记忆系统的开发平台，覆盖「观测—分析—编排」三条链路：

- **记忆可观测**：通过 `mo-sdk` 在 Agent 运行时采集记忆操作事件、上下文快照与质量指标，服务端按 OTLP 接收并落库，Web 端提供事件时间线、Token 分析、Agent / Skill 分析与数据导入看板。
- **智能工作台**：内置多子 Agent 编排（工作区经理 + 员工），采用 ReAct 工具链（文件、Bash、联网搜索、用户问询、MCP 等），通过 SSE 实时推送进度与思考树，支持将需求拆解为可独立交付的子任务并并行派发、逐步验收。
- **流程引擎**：角色化工作流（项目经理 / 架构师 / 后端开发 / 前端开发 / 验证工程师 / 修复工程师 / Git 提交，含**门禁验收**），以气泡流形式结构化输出关键节点与验收结论。

## 技术栈

- 后端：Java 17+ / Spring Boot / PostgreSQL（TimescaleDB）· OTLP 接收 + REST API + SSE
- 前端：单页 Web UI（原生 HTML/CSS/JS，内联 mermaid / marked）
- 采集：`mo-sdk`（Python，框架无关 + Hermes 拦截器）
- 编排：工作区式 `.workbench` 目录与 manifest，Agent / Skill 等均可自定义

## 快速开始

```bash
docker compose up --build -d        # postgres + mo-server + mo-dashboard 三容器
python examples/otel_demo.py        # 上报演示记忆事件与五区快照
```

打开 Web 端选择 Agent 即可查看记忆事件与 Token 全景；进入「工作区」可创建多子 Agent、派发任务并实时跟踪编排进度。

> 📐 **数据契约规范**：[MOSpec — Memory Observability Data Specification v0.1](./docs/spec/mospec.md)；更多说明见 [ARCHITECTURE.md](./ARCHITECTURE.md) 与 [`docs/`](./docs)。

---

# Memory Observatory SDK (mo-sdk)

> Agent 记忆层可观测性平台的 Python SDK

mo-sdk 提供了一套轻量的 Python 工具，用于在 Agent 运行时采集记忆操作事件、上下文快照和质量指标，并导出到本地文件或 Observatory 服务端进行分析。

---

## 目录

- [项目简介](#项目简介)
- [架构图](#架构图)
- [快速开始](#快速开始)
- [API 参考](#api-参考)
- [Hermes 集成指南](#hermes-集成指南)
- [核心指标说明](#核心指标说明)
- [路线图](#路线图)

---

## 项目简介

mo-sdk 为 AI Agent 的记忆系统提供统一的可观测性接入层。它支持：

- **四层记忆观测**：Prompt 记忆、会话归档 (Session Archive)、技能 (Skills)、外部记忆提供者 (Memory Providers)
- **三大事件类型**：Store（存储）、Retrieve（检索）、Forget（遗忘）、Consolidate（整合/压缩）
- **五区 Token 预算**：实时监控 System / Task / Memory / Tool History / Free 五个区域的 Token 分配
- **健康评估**：自动评估记忆系统健康状态，生成告警和优化建议
- **双模式导出**：本地 JSON 文件 + HTTP 推送到服务端

本 SDK 设计为框架无关的核心库 + 框架专用拦截器（Interceptor）的结构，当前已内置 **Hermes Agent** 的完整拦截器实现。

---

## 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Application / Agent                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │   AIAgent    │  │ Memory Tools │  │ Memory Providers │   │
│  │  (chat loop) │  │  (add/search)│  │ (mem0/honcho/...)│   │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘   │
│         │                 │                    │             │
│         ▼                 ▼                    ▼             │
│  ┌──────────────────────────────────────────────────────┐    │
│  │         HermesMemoryInterceptor (拦截器层)            │    │
│  │  ┌──────────┐ ┌───────────┐ ┌─────────────────────┐ │    │
│  │  │ prompt   │ │ session   │ │ provider operations │ │    │
│  │  │ memory   │ │ search    │ │ compression / cache │ │    │
│  │  └────┬─────┘ └─────┬─────┘ └──────────┬──────────┘ │    │
│  └───────┼──────────────┼──────────────────┼────────────┘    │
└──────────┼──────────────┼──────────────────┼─────────────────┘
           │              │                  │
           ▼              ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│                    MemoryObserver (核心)                     │
│                                                             │
│  ┌──────────────────┐   ┌───────────────────────────┐      │
│  │   Event Buffer   │   │     Snapshot Buffer       │      │
│  │ (MemoryEvent[])  │   │  (ContextSnapshot[])      │      │
│  └─────────┬────────┘   └──────────────┬────────────┘      │
│            │                           │                   │
│            └─────────────┬─────────────┘                   │
│                          │                                 │
│                          ▼                                 │
│                ┌───────────────────┐                       │
│                │  MetricsCollector │                       │
│                │  (实时指标计算)    │                       │
│                └─────────┬─────────┘                       │
│                          │                                 │
│                          ▼                                 │
│                ┌───────────────────┐                       │
│                │   Health Report   │                       │
│                │  (健康评估 + 告警) │                       │
│                └─────────┬─────────┘                       │
└──────────────────────────┼─────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │      Exporters         │
              │  ┌────────┐ ┌────────┐ │
              │  │ JSON   │ │ HTTP   │ │
              │  │ File   │ │ Server │ │
              │  └────────┘ └────────┘ │
              └────────────────────────┘
```

---

## 快速开始

### 环境要求

- Python 3.10+
- 无强制外部依赖（HTTP 导出可选 httpx 以获得更好性能）

### 安装

```bash
# 从源码使用（当前方式）
cd memory-observatory
```

### 最小示例

```python
from mo_sdk import MemoryObserver, MemoryEvent, JSONFileExporter

# 1. 创建观测器
observer = MemoryObserver(agent_id="my-agent")

# 2. 添加导出器
observer.add_exporter(JSONFileExporter("./mo_output"))

# 3. 记录记忆事件
observer.record_event(MemoryEvent(
    operation="store",
    layer="prompt",
    memory_key="MEMORY.md",
    memory_summary="User preference settings",
    token_count=1200,
    latency_ms=5.2,
    cost_usd=0.0,
    metadata={"source": "prompt_memory"},
))

# 4. 获取实时指标
metrics = observer.get_metrics()
print(metrics["kv_cache_hit_rate"])

# 5. 手动触发导出
observer.export()
```

### 运行 Hermes 插桩演示

```bash
python hermes_instrumentation.py
```

运行后你将看到：
- 5 轮模拟对话的记忆操作
- 实时计算的各项记忆指标
- 健康检查报告（含告警和优化建议）
- 导出的 JSON 文件（位于 `mo_output/` 目录）

---

## 服务端部署（MVP · 开源观测）

开源版本地部署，看见记忆操作。详见 [MVP 设计文档](MemoryObservatory_MVP_设计_v1.html)。

### 一键启动（Docker Compose）

```bash
docker compose up --build -d
```

启动 3 个容器：

| 容器 | 端口 | 作用 |
|------|------|------|
| postgres（TimescaleDB） | 5432 | 存储 memory_events / memory_snapshots |
| mo-server（Spring Boot） | 4318 OTLP / 8080 REST | 接收 OTLP + 查询 API |
| mo-dashboard（nginx） | 5173 | Web UI |

打开 http://localhost:5173 ，Agent 填 `demo-agent` 即可查看。

> 国内拉取 Docker Hub 镜像较慢时，可在 Docker Desktop 设置中配置镜像加速器（如阿里云个人加速地址）。

### 产生演示数据

```bash
python examples/otel_demo.py
# 上报 5 个记忆事件（覆盖 WRITE/READ/UPDATE/EXPIRE）+ 1 个五区快照
```

### 手动验证（不依赖 Docker）

```bash
cd mo-server
mvn package -DskipTests                      # 打包，已验证生成 jar
java -jar target/mo-server-0.1.0.jar         # 运行（需先启动 PostgreSQL）
```

### Agent 接入（OTel 导出器）

```python
from mo_sdk import MemoryObserver, OTelSpanExporter

observer = MemoryObserver(agent_id="my-agent")
observer.add_exporter(OTelSpanExporter(
    endpoint="http://localhost:4318/v1/traces",
    service_name="my-agent",
))
# record_event(...) 之后 observer.export() 即上报
```

属性对齐 OpenTelemetry GenAI 语义约定（OTEP 4959），`memory.operation` / `memory.size_delta` 用标准名，扩展属性用 `memory.*` 命名空间。

### REST 查询接口

| 方法 路径 | 说明 |
|-----------|------|
| GET `/api/v1/agents/{id}/events` | 事件列表（记忆追踪），支持 session/op/layer/时间筛选 |
| GET `/api/v1/sessions/{id}/timeline` | 会话时间线（按 layer 分泳道） |
| GET `/api/v1/agents/{id}/token-stats` | Token 分析（分层/按操作/趋势/五区） |
| GET `/api/v1/agents/{id}/sessions` | 会话列表（最近活动） |

### 架构要点

- 操作归一化在 SDK 侧完成（store/retrieve/forget/consolidate → READ/WRITE/UPDATE/EXPIRE）
- 服务端 `IngestQueue` 采用旁路容错：内存有界队列 + 后台批量写库，失败只记 WARN 不阻塞
- OTLP 用 JSON 编码，SDK 零外部依赖（httpx 可选，回退 urllib）

---

## API 参考

### MemoryObserver

核心观测器，负责事件收集、指标计算和导出调度。

| 方法 | 说明 |
|------|------|
| `record_event(event: MemoryEvent)` | 记录一个记忆操作事件 |
| `record_snapshot(snapshot: ContextSnapshot)` | 记录一个上下文快照 |
| `add_exporter(exporter)` | 绑定导出器 |
| `get_metrics() -> dict` | 获取当前指标汇总 |
| `export() -> list[str]` | 手动触发导出 |
| `shutdown()` | 关闭观测器，刷新数据，取消定时器 |

### MemoryEvent

记忆操作事件数据模型。

| 字段 | 类型 | 说明 |
|------|------|------|
| `event_id` | str | 事件 UUID |
| `timestamp` | float | Unix 时间戳 |
| `agent_id` | str | Agent 标识 |
| `session_id` | str | 会话 ID |
| `operation` | str | 操作类型：`store` / `retrieve` / `forget` / `consolidate` |
| `layer` | str | 记忆层：`L1` / `L2` / `L3` / `prompt` / `session` / `skill` / `provider` |
| `memory_key` | str | 记忆标识 |
| `memory_summary` | str | 记忆摘要 |
| `token_count` | int | 涉及的 Token 数量 |
| `latency_ms` | float | 操作延迟（毫秒） |
| `cost_usd` | float | 操作成本（美元） |
| `metadata` | dict | 扩展元数据 |

### ContextSnapshot

上下文窗口快照，记录五区 Token 预算分布。

| 字段 | 类型 | 说明 |
|------|------|------|
| `total_tokens` | int | 总 Token 数 |
| `system_tokens` | int | System Prompt 区 |
| `task_tokens` | int | Task Description 区 |
| `memory_tokens` | int | Memory Retrieval 区 |
| `tool_history_tokens` | int | Tool History 区 |
| `free_tokens` | int | Free Space 区 |
| `compression_count` | int | 累计压缩次数 |
| `last_compression_ratio` | float | 最近一次压缩比 |
| `kv_cache_hit` | bool | KV-cache 是否命中 |
| `kv_cache_prefix_tokens` | int | KV-cache 前缀 Token 数 |
| `decay_score` | float | 腐烂分数（1.0=新鲜，0.0=陈旧） |
| `drift_cosine` | float | 漂移余弦相似度 |

### MetricsCollector

指标聚合器，提供：
- 分层（per-layer）统计摘要
- 延迟分布分析
- Token 预算趋势
- **健康检查** (`run_health_check()`)：自动评估 6 项核心指标的健康状态

### 导出器

| 导出器 | 用途 |
|--------|------|
| `JSONFileExporter` | 写入本地 JSON 文件（默认带缩进，便于阅读） |
| `HTTPExporter` | POST JSON 到 Observatory 服务端（支持 httpx 或 urllib） |
| `CompositeExporter` | 组合多个导出器，同时写入多个目标 |

---

## Hermes 集成指南

### 前置条件

- 已安装 `hermes-agent`（pip 或源码安装均可）

### 集成方式

**方式一：一键安装（推荐）**

在你的启动脚本中，导入 hermes 之前或之后调用 `install_hermes_instrumentation()`：

```python
from hermes_instrumentation import install_hermes_instrumentation

observer, interceptor = install_hermes_instrumentation(
    agent_id="my-hermes-agent",
    output_dir="./mo_output",
    # http_endpoint="https://observatory.example.com/api/ingest",
    # http_api_key="your-api-key",
)

# 然后正常使用 hermes
from hermes.run_agent import AIAgent
agent = AIAgent(...)
agent.chat("Hello")
```

**方式二：手动选择性拦截**

只需要拦截部分模块时，可以手动调用 interceptor：

```python
from mo_sdk import MemoryObserver, HermesMemoryInterceptor

observer = MemoryObserver(agent_id="hermes-agent")
interceptor = HermesMemoryInterceptor(observer, session_id="session-123")

# 拦截上下文压缩
interceptor.intercept_compression(
    before_tokens=8000,
    after_tokens=5000,
    method="summarization",
)

# 拦截会话搜索
interceptor.intercept_session_search(
    query="project plan",
    results=[{"content": "..."}],
    latency_ms=42.5,
)
```

### 拦截点一览

| 拦截点 | 对应 Hermes 模块 | 事件类型 |
|--------|-----------------|----------|
| 提示记忆读写 | `prompt_builder` / `memory_tool` | store / retrieve (layer: prompt) |
| 会话归档搜索 | `session_search` tool | retrieve (layer: session) |
| 上下文压缩 | `context_compressor` | consolidate (layer: L1) |
| Prompt 缓存 | `prompt_caching` | retrieve/store (layer: prompt) |
| 外部 Provider | `base_provider` + 各 Provider 实现 | 各类操作 (layer: provider) |
| 技能加载/生成 | skills 目录 | store / retrieve (layer: skill) |
| 每轮对话快照 | `AIAgent.chat()` | ContextSnapshot |

### Monkey-patch 原理

`install_hermes_instrumentation()` 使用 Python 的 `functools.wraps` 对 hermes 的关键方法进行猴子补丁：
- 不修改 hermes 源码
- 保留原函数签名和文档
- 拦截异常不会影响主流程（try/except 保护）
- 支持热插拔（理论上，实际生产建议启动时安装）

---

## 核心指标说明

### 1. KV-cache 命中率

- **含义**：模型推理时 KV-cache 前缀命中的比例
- **健康范围**：> 50%
- **低于阈值的影响**：推理速度慢、成本高
- **优化建议**：优化系统提示结构、保持长对话中的稳定前缀

### 2. 平均压缩频率

- **含义**：每轮对话触发上下文压缩的频率
- **目标范围**：0.05 - 0.15（约 7-20 轮压缩一次）
- **过高**：压缩过于频繁，可能丢失重要上下文，导致记忆保真度下降
- **过低**：压缩不足，上下文膨胀，浪费 Token 预算

### 3. 还原调用率

- **含义**：重新加载之前被驱逐记忆的频率
- **健康范围**：< 0.1
- **高的含义**：驱逐策略有问题，"遗忘"的内容很快又需要找回来
- **优化建议**：提升 L2/L3 容量，改进驱逐算法

### 4. 平均腐烂分数

- **含义**：记忆内容的"新鲜度"评分（1.0 最鲜，0.0 完全陈旧）
- **健康范围**：> 0.7
- **过低的影响**：记忆老化严重，可能导致 Agent 基于过时信息做决策
- **优化建议**：触发 consolidate 操作，刷新和修剪记忆

### 5. 漂移告警率

- **含义**：记忆内容相对于原始上下文的语义漂移比例
- **健康范围**：< 0.05
- **高的含义**：压缩/整合过程中信息失真严重
- **优化建议**：优化压缩算法，减少层级压缩的信息损失

### 6. Session Resume 成功率

- **含义**：恢复历史会话的成功率
- **健康范围**：> 90%
- **过低的影响**：用户体验差，跨会话记忆不可靠

### 五区 Token 预算模型

| 区域 | 目标占比 | 说明 |
|------|---------|------|
| System | 10% | 系统提示、角色设定、基础指令 |
| Task | 5% | 当前任务描述、用户目标 |
| Memory | 15% | 检索到的相关记忆 |
| Tool History | 40% | 工具调用历史和结果 |
| Free | 30% | 可用空间（留给推理和回复） |

### 工作记忆三层分区

| 层级 | 名称 | 特点 |
|------|------|------|
| L1 | Working Window | 当前上下文窗口，完整保真 |
| L2 | Summary | 压缩/摘要后的中期记忆 |
| L3 | Index | 长期记忆索引，按需检索 |

---

## 路线图

### v0.2 (Q4 2026)
- [ ] 更多 Agent 框架拦截器（LangChain、AutoGPT、CrewAI）
- [ ] SQLite 本地存储（替代纯内存缓冲）
- [ ] 实时指标计算优化（滑动窗口 + 指数衰减）

### v0.3 (Q1 2027)
- [ ] 分布式 Agent 追踪支持（OpenTelemetry 兼容）
- [ ] 记忆演化可视化（记忆生命周期图谱）
- [ ] 异常检测算法集成（基于历史基线的自动告警）

### v0.4 (Q2 2027)
- [ ] 记忆质量自动评估（基于下游任务性能反馈）
- [ ] 记忆策略推荐引擎（根据指标自动建议配置调整）
- [ ] 多 Agent 记忆交互追踪

---

## License

MIT License
