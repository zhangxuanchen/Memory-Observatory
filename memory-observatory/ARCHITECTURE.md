# MemoryObservatory 架构文档

## 1. 产品定位

MemoryObservatory（品牌名 MindSprout）是一个 AI Agent 记忆系统可观测平台。

- 开源本地：记忆操作采集、时间线、Token 分析、Trace 链路
- 服务端定制（付费）：验证、评估、治理、安全

双栈架构：Python SDK 在 Agent 进程内旁路采集记忆操作，通过 OpenTelemetry 协议（OTLP）上报到 Java 服务端；Java 服务端负责接收、存储、查询与可视化。

---

## 2. 整体分层

```
┌─────────────────────────────────────────────────────────┐
│  L4  前端 Dashboard（mo-dashboard/）                      │  可视化层
│     index.html · nginx                                  │
├─────────────────────────────────────────────────────────┤
│  L3  Java 服务端（mo-server/）                           │  接收+存储+查询
│     API · OTLP Receiver · IngestQueue · Repository      │
├─────────────────────────────────────────────────────────┤
│  L2  Python SDK（mo_sdk/）+ 示例（examples/）              │  采集层
│     collector · exporter · interceptors                  │
├─────────────────────────────────────────────────────────┤
│  L1  基础设施（docker-compose.yml）                        │  运行环境
│     PostgreSQL 16 · Nginx · Spring Boot                  │
└─────────────────────────────────────────────────────────┘
```

---

## 3. L1 基础设施层

提供运行环境：PostgreSQL 数据库、Java 服务容器、Nginx 静态资源服务。

### 文件清单

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 编排 3 个服务：postgres（PG 16-alpine + 持久卷）、mo-server（Java，4318→OTLP / 8080→REST）、mo-dashboard（Nginx，5173） |
| `mo-server/Dockerfile` | Java 服务镜像构建 |
| `mo-dashboard/Dockerfile` | Nginx 镜像构建 |
| `mo-dashboard/nginx.conf` | Nginx 反代配置，`/api/` 转发到 mo-server:8080 |

### 服务端口

| 服务 | 容器端口 | 宿主映射 | 说明 |
|---|---|---|---|
| postgres | 5432 | 5432 | PostgreSQL 数据库 |
| mo-server | 8080 | 4318（OTLP）+ 8080（REST） | OTLP 接收路径 `/v1/traces` 和 REST 查询共用 8080 |
| mo-dashboard | 80 | 5173 | Nginx 静态资源 + API 反代 |

---

## 4. L2 Python SDK 采集层

在 Agent 应用进程内旁路采集记忆操作（store/retrieve/forget/consolidate），归一化为 READ/WRITE/UPDATE/EXPIRE，通过 OTLP 协议上报到 Java 服务端。

设计遵循"旁路观测容错范式"：观测代码全 try-catch，异常只记 WARN 不影响业务；数据写内存缓冲区，后台单线程批量刷新；缓冲满丢最旧保最新。

### 文件清单

| 文件 | 职责 |
|---|---|
| `mo_sdk/core.py` | 核心数据模型：`MemoryEvent`、`MemorySnapshot`、`ObserverConfig`、`MemoryObserver`（编排采集→指标计算→导出触发） |
| `mo_sdk/collector.py` | 采集器：拦截 memory 操作，生成事件对象，缓冲在内存 |
| `mo_sdk/exporter.py` | 导出器：定时/批量将缓冲区事件序列化为 OTLP JSON，HTTP POST 到服务端 |
| `mo_sdk/otel_exporter.py` | OTLP/HTTP JSON 格式构造，对齐 OTel GenAI 语义约定（`memory.*` 属性命名空间） |
| `mo_sdk/interceptors.py` | 拦截器：装饰器/AOP 零侵入接入 Agent 代码 |
| `mo_sdk/__init__.py` | 包入口，导出公共 API |

### 示例与工具

| 文件 | 职责 |
|---|---|
| `examples/trae_report_event.py` | 零依赖 CLI 上报脚本（urllib），支持 `--trace-id` / `--parent-span-id` / `--meta k=v`，TraeCode 直接 RunCommand 调用 |
| `examples/trae_importer.py` | 批量导入历史 JSONL 对话日志，从 `~/.trae-cn/memory/projects/` 提取多项目 Agent |
| `examples/otel_demo.py` | OTLP 上报演示 |
| `hermes_instrumentation.py` | Hermes 框架插桩示例 |

### 操作归一化

Python 原始操作名和 MO 归一化操作的映射：

| Python 原始 | MO 归一 | 说明 |
|---|---|---|
| retrieve | READ | 检索记忆 |
| store | WRITE | 写入记忆 |
| update / consolidate | UPDATE | 更新/合并记忆 |
| forget / delete | EXPIRE | 遗忘/删除记忆 |

---

## 5. L3 Java 服务端层

单进程内含 4 个子层：OTLP 接收 → 内存队列削峰 → 批量写库 → REST 查询。技术栈：Spring Boot 3.4 + JDBC + PostgreSQL。

### 5.1 入口与配置

| 文件 | 职责 |
|---|---|
| `mo-server/src/main/java/io/memobservatory/server/ServerApplication.java` | Spring Boot 入口，单进程启动全部组件 |
| `mo-server/src/main/resources/application.yml` | 端口 8080、HikariCP 连接池（max 5）、IngestQueue 配置（capacity=10000, flush=5000ms）、schema.sql 自动执行 |
| `mo-server/pom.xml` | Maven 依赖：Spring Boot 3.4 + PostgreSQL JDBC + Jackson |
| `mo-server/settings.xml` | Maven 仓库镜像配置 |

### 5.2 数据模型层（model/）

| 文件 | 职责 |
|---|---|
| `MemoryEvent.java` | 记忆事件 record：eventId(=spanId)、agentId、operation、layer、traceId、parentSpanId 等 13 字段 |
| `MemoryOp.java` | 操作枚举：READ/WRITE/UPDATE/EXPIRE + 归一化方法 `of()` |
| `MemorySnapshot.java` | 五区快照：system/task/memory/toolHistory/free tokens |

### 5.3 接收层（receiver/）

| 文件 | 职责 |
|---|---|
| `OtlpReceiver.java` | HTTP 端点 `/v1/traces`，接收 OTLP JSON，交给 OtlpParser 解析后入队 IngestQueue |
| `OtlpParser.java` | OTLP 解析器：从 `resourceSpans.scopeSpans.spans[]` 提取 `memory.*` 属性重建 MemoryEvent/Snapshot；解析 traceId/parentSpanId + 缺失兜底（session 派生 trace + orphan 标记） |

### 5.4 摄取/存储层（ingest/ + storage/）

| 文件 | 职责 |
|---|---|
| `IngestQueue.java` | 内存有界队列（ArrayBlockingQueue 10000）削峰 + 后台单线程 5s 定时 drain → 批量写库；满则丢最旧保最新 |
| `EventRepository.java` | 数据访问层：批量 INSERT 事件/快照 + 25 个查询方法（事件流、时间线、Token 分析 8 维度、Agent/Skill 分析、Trace 查询 3 个） |
| `schema.sql` | 建表脚本：`memory_events`（含 trace_id/parent_span_id）+ `memory_snapshots` + TimescaleDB hypertable DO 块 |

### 5.5 API 层（api/）

| 文件 | 职责 |
|---|---|
| `ApiController.java` | REST 查询接口，共 20+ 端点 |

#### API 端点清单

| 分组 | 方法 | 路径 | 功能 |
|---|---|---|---|
| 事件流 | GET | `/api/v1/agents/{id}/events` | 事件列表（分页+过滤） |
| | GET | `/api/v1/events/{eventId}` | 单事件详情（含 metadata + turnEvents） |
| 消息详情 | GET | `/api/v1/sessions/{id}/turns` | 会话所有 turn |
| | GET | `/api/v1/sessions/{id}/timeline` | 时间线泳道 |
| Token 分析 | GET | `/api/v1/agents/{id}/token-analytics` | 8 维度聚合 |
| | GET | `/api/v1/agents/{id}/token-heatmap` | 时间×层热力图 |
| | GET | `/api/v1/agents/{id}/token-stats` | 基础 Token 统计 |
| Agent/Skill | GET | `/api/v1/agents` | Agent 列表 |
| | GET | `/api/v1/agents/{id}/sessions` | 会话列表 |
| | GET | `/api/v1/agent-analysis` | Agent 全局对比 |
| | GET | `/api/v1/agents/{id}/skill-analysis` | Agent Skill 分析 |
| | GET | `/api/v1/skill-analysis` | 全局 Skill 分析 |
| Trace | GET | `/api/v1/traces/{traceId}` | 完整 Trace（spans + tree + statistics） |
| | GET | `/api/v1/events/{eventId}/trace` | 从事件反查 Trace |
| | GET | `/api/v1/agents/{agentId}/traces` | Agent 维度 Trace 列表 |

---

## 6. L4 前端 Dashboard 层

暗紫色霓虹风格单页应用。纯 HTML/JS/CSS，无框架依赖，通过 Nginx 反代调后端 API。

### 文件清单

| 文件 | 职责 |
|---|---|
| `mo-dashboard/index.html` | 全部前端代码（HTML + CSS + JS），约 2000 行 |
| `mo-dashboard/nginx.conf` | Nginx 配置：静态资源服务 + `/api/` 反代到 mo-server |
| `mo-dashboard/Dockerfile` | Nginx 镜像构建 |

### Tab 结构

| Tab | 功能 |
|---|---|
| 记忆事件流 | 分页表格（20 条/页）+ Agent/Session 过滤 + 点击跳转详情 |
| 消息详情 | 会话选择 → Turn 列表 → 点击打开事件详情抽屉 |
| Token 分析 | 8 维度：Top5 会话、24h 分布、操作×层矩阵、延迟分位、Top10 key、比率、直方图、日趋势 |
| 会话列表 | 最近活动会话列表 |

### 交互特性

- 事件详情抽屉：基本信息 + memory_summary + turn 详情 + 同 Turn 事件列表 + 原始 JSON
- Agent/Session 下拉框：自动加载真实数据 + x 清除按钮
- 中英双语切换
- SessionId 截断显示 + hover 全量 + 点击自动填充过滤
- 同 Turn 事件点击：平滑切换（不清空抽屉内容，保留滚动位置）

---

## 7. 数据库 Schema

### memory_events 表

| 列 | 类型 | 说明 |
|---|---|---|
| event_id | TEXT PK | 事件 ID = Span ID（16 hex） |
| agent_id | TEXT | Agent 标识 |
| session_id | TEXT | 会话 ID |
| operation | TEXT | READ/WRITE/UPDATE/EXPIRE |
| layer | TEXT | prompt/session/skill/provider |
| memory_key | TEXT | 记忆键（文件路径/工具名/会话 ID） |
| memory_summary | TEXT | 前 200 字预览 |
| token_count | INTEGER | Token 数 |
| latency_ms | DOUBLE | 延迟毫秒 |
| ts | TIMESTAMPTZ | 时间戳 |
| metadata | JSONB | 扩展元数据 |
| trace_id | VARCHAR(32) | Trace ID（32 hex，一个 session = 一个 trace） |
| parent_span_id | VARCHAR(16) | 父 Span ID（Turn Root 为 NULL） |

### memory_snapshots 表

| 列 | 类型 | 说明 |
|---|---|---|
| snapshot_id | TEXT PK | 快照 ID |
| agent_id | TEXT | Agent 标识 |
| session_id | TEXT | 会话 ID |
| total_tokens | INTEGER | 总 Token |
| system_tokens | INTEGER | System 区 |
| task_tokens | INTEGER | Task 区 |
| memory_tokens | INTEGER | Memory 区 |
| tool_history_tokens | INTEGER | Tool History 区 |
| free_tokens | INTEGER | Free 区 |
| compression_count | INTEGER | 压缩次数 |
| last_compression_ratio | DOUBLE | 最近压缩比 |
| ts | TIMESTAMPTZ | 时间戳 |

### 索引

| 索引 | 列 | 用途 |
|---|---|---|
| idx_me_session | session_id, ts | 会话时间线查询 |
| idx_me_agent | agent_id, ts | Agent 事件流查询 |
| idx_me_op | operation, ts | 按操作过滤 |
| idx_me_trace | trace_id | Trace 查询 |
| idx_me_parent | parent_span_id | Span 父子关系查询 |

---

## 8. 数据流

```
Agent 应用（Python/Java/任意）
  │
  │  mo_sdk 装饰器拦截
  │  trae_report_event.py CLI 上报
  │  OTLP HTTP POST /v1/traces
  │
  ▼
OtlpReceiver（端口 4318→8080）
  │  OtlpParser 解析 memory.* 属性
  │  → MemoryEvent + MemorySnapshot
  │  → 填充 traceId / parentSpanId（缺失兜底）
  │
  ▼
IngestQueue（ArrayBlockingQueue 10000, 5s flush）
  │  批量 batchInsert
  │  满则丢最旧保最新
  │
  ▼
PostgreSQL（memory_events + memory_snapshots）
  │
  │  REST 查询 /api/v1/*
  │
  ▼
ApiController（端口 8080）
  │  事件流 / 时间线 / Token 分析 / Trace
  │
  │  Nginx 反代
  │
  ▼
Dashboard index.html（端口 5173）
  4 Tab + 事件详情抽屉 + Trace 视图
```

---

## 9. Trace 契约

复用 OTLP Trace 标准，事件 ID 即 Span ID，一个会话一个 Trace ID，支持跨进程传播，通过 Span 树串联记忆操作、工具调用、LLM 推理等事件。

### ID 生成规则

| 标识 | 格式 | 作用域 | 生成规则 |
|---|---|---|---|
| trace_id | 32 hex | 一个 session = 一个 trace | 会话入口生成；缺失时由 session_id MD5 派生 |
| span_id | 16 hex | 单条操作/事件 | 每次操作生成；= event_id |
| parent_span_id | 16 hex | 描述父子关系 | 创建子 Span 时复制父 Span 的 span_id；Turn Root 为 NULL |

### Span 树结构（一个 LLM Turn 的典型结构）

```
Turn Root Span（parent=NULL）
├─ cache.check          ← KVCache 前缀命中检查
├─ context.build        ← 上下文窗口组装（5 区分配）
├─ memory.read          ← 主检索
│   ├─ memory.search    ← 向量/文本搜索子 span
│   └─ memory.restore   ← 归档恢复子 span
├─ LLM 推理 Span         ← 外部 LLM SDK 上报
│   └─ tool: xxx        ← 工具调用（skill 层）
│       └─ memory.read  ← 工具内部记忆读取
├─ context.compression  ← 仅超阈值触发
│   ├─ memory.decay
│   └─ memory.evict
└─ memory.write         ← 学到新东西
    └─ memory.conflict  ← 仅检测到冲突时存在
```

### Trace 查询 API 响应格式

`GET /api/v1/traces/{traceId}` 返回：

- `traceId` / `agentId` / `sessionId`：Trace 归属
- `spans[]`：flat span 列表（按 start_ts 升序）
- `tree[]`：预构建树（children 递归）
- `statistics`：totalSpans / turns / criticalPathMs / failedSpans / orphanSpans / topSlowSpans / memoryEffectiveness

---

## 10. 五区 Token 预算

来自 memory_snapshots 表，五个区域的颜色约定：

| 区域 | 颜色 | 说明 |
|---|---|---|
| System | 紫色 #7C5CFF | 系统提示词 |
| Task | 青色 #69E7FF | 任务描述 |
| Memory | 绿色 #62FAD3 | 记忆内容 |
| Tool History | 黄色 #FFB547 | 工具调用历史 |
| Free | 灰色 #5A6580 | 空闲空间 |

---

## 11. 数据模型 V2（设计中）

V2 采用五层架构，当前项目处于 V1 → V2 融合阶段。设计文档位于 `docs/data-model-v2/`。

### 五层架构

| 层 | 名称 | 说明 |
|---|---|---|
| L1 | 原始事件层 | 10 种事件类型（memory.read/write/decay/restore/evict/search/conflict + context.compression/build + cache.check） |
| L2 | 记忆项层 | 记忆项元数据：tier、decay_score、content_hash |
| L3 | 上下文窗口与会话层 | 五区快照、压缩历史、会话状态 |
| L4 | 派生指标层 | 16 个派生指标（RetrievalEffectivenessScore、CompressionRatio 等） |
| L5 | 观测维度层 | 七维观测模型（有效性/效率/容量/漂移/UX/成本/健康度） |

### 文档清单

| 文件 | 内容 |
|---|---|
| `docs/data-model-v2/fusion-design.md` | 五层架构设计、10 种事件类型、16 个派生指标、数据库 Schema |
| `docs/data-model-v2/input-contracts.md` | 输入数据结构 + MCP 接入 + Skill 插件 + Trace 契约（§6） |
| `docs/data-model-v2/integration-plan.md` | 七阶段集成计划 |
| `docs/data-model-v2/README.md` | 文档索引 |

---

## 12. 文件总览

```
memory-observatory/
├── docker-compose.yml              # L1 基础设施编排
├── hermes_instrumentation.py       # L2 Hermes 插桩示例
├── mo_sdk/                         # L2 Python SDK
│   ├── __init__.py
│   ├── core.py                     # 数据模型 + MemoryObserver
│   ├── collector.py                # 采集器
│   ├── exporter.py                 # 导出器
│   ├── otel_exporter.py            # OTLP 格式构造
│   └── interceptors.py             # 拦截器/装饰器
├── examples/                       # L2 示例/工具
│   ├── trae_report_event.py        # 实时上报 CLI
│   ├── trae_importer.py            # 批量导入脚本
│   └── otel_demo.py                # OTLP 演示
├── mo-server/                      # L3 Java 服务端
│   ├── pom.xml
│   ├── settings.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/io/memobservatory/server/
│       │   ├── ServerApplication.java   # 入口
│       │   ├── api/
│       │   │   └── ApiController.java  # REST API（20+ 端点）
│       │   ├── receiver/
│       │   │   ├── OtlpReceiver.java    # OTLP 接收
│       │   │   └── OtlpParser.java      # OTLP 解析
│       │   ├── ingest/
│       │   │   └── IngestQueue.java     # 内存队列削峰
│       │   ├── storage/
│       │   │   └── EventRepository.java # 数据访问（25 个查询方法）
│       │   └── model/
│       │       ├── MemoryEvent.java     # 事件模型
│       │       ├── MemoryOp.java        # 操作枚举
│       │       └── MemorySnapshot.java  # 快照模型
│       └── resources/
│           ├── application.yml          # 配置
│           ├── schema.sql               # 建表脚本
│           └── static/index.html        # 静态首页
├── mo-dashboard/                   # L4 前端 Dashboard
│   ├── index.html                  # 全部前端代码（~2000 行）
│   ├── nginx.conf                  # Nginx 反代配置
│   └── Dockerfile
└── docs/                           # 文档
    └── data-model-v2/
        ├── README.md
        ├── fusion-design.md        # V2 五层架构设计
        ├── input-contracts.md       # 输入契约 + Trace 契约
        └── integration-plan.md     # 集成计划
```
