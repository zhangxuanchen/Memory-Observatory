# MemoryObservatory 架构文档

## 1. 产品定位

MemoryObservatory（品牌名 MindSprout）是一个 AI Agent 记忆系统可观测平台。

- 开源本地：记忆操作采集、时间线、Token 分析、Trace 链路
- 服务端定制（付费）：验证、评估、治理、安全

双栈架构：Python SDK 在 Agent 进程内旁路采集记忆操作，通过 OpenTelemetry 协议（OTLP）上报到 Java 服务端；Java 服务端负责接收、存储、查询与可视化。

在此之上还有两条独立支线，本文件一并说明：

- **工作台（agentloop）**：mo-server 内的一个完整 Agent 运行环境（工具、HITL、工作流引擎、MCP），既是产品功能，也是 L6 所需真实流量的来源
- **记忆外骨骼（mo-exoskeleton）**：L6「能学」的落地模块，独立的 Spring Boot 应用，与 mo-server **共读同一个库**

---

## 2. 整体分层

```
┌───────────────────────────────────────────────────────────────┐
│  采集侧                                                         │  写入
│    mo_sdk（Python 旁路）· examples（导入/上报）· 工作台中间件     │
├───────────────────────────────────────────────────────────────┤
│  mo-server · 接收与削峰                                         │
│    OtlpReceiver → OtlpParser → IngestQueue（容量 10000）        │
├───────────────────────────────────────────────────────────────┤
│  mo-server · 存储（PostgreSQL）                                 │
│    memory_events · memory_snapshots · memory_turns（物化）      │
├──────────────────────────────┬────────────────────────────────┤
│  mo-server · 查询与判定       │  mo-exoskeleton（L6 记忆外骨骼）│
│    ApiController · laya 语义  │    读同一库 · 写规则草稿         │
│    风险评估 · 流程分析 · 告警  │    写判据：corrected/holdout    │
├──────────────────────────────┤                                │
│  mo-server · 可视化           │                                │
│    static/index.html 单页     │                                │
└──────────────────────────────┴────────────────────────────────┘
```

关键点：**查询层与记忆外骨骼是并列的两个消费者，读的是同一个 PostgreSQL**。外骨骼不在主链上（没有它，采集/观测/告警照常），但**已在 docker-compose 里**，随 `docker compose up -d` 一起就绪。

---

## 3. 基础设施层（docker-compose.yml）

### 服务与端口

| 服务 | 镜像 / 目录 | 容器端口 | 宿主 | 说明 |
|---|---|---|---|---|
| postgres | `postgres:16-alpine` | 5432 | 5432 | 主库，库名/用户名/口令均默认 `mo` |
| laya-backend | `laya-backend/` | 8770 | — | 语义过滤推理后端（本项目自有 `server.py`，Apache-2.0） |
| mo-server | `mo-server/` | 8080 | 4318（OTLP）+ 8080（REST） | OTLP 路径 `/v1/traces` 与 REST 共用 8080 |
| mo-exoskeleton | `mo-exoskeleton/` | 8081 | 8081（仅本机回环，便于 curl 调试） | 记忆外骨骼（L6），连同一个库；容器间由服务名 `mo-exoskeleton:8081` 访问。工作台「观测 → 记忆提炼」页（分类清单 + 技能发现）经 mo-server 转发取数，见 §6.3 |
| mo-dashboard | `mo-dashboard/` | 80 | 5173 | Nginx 静态资源 + `/api/` 反代 |

> `mo-server` **刻意不 `depends_on` mo-exoskeleton**：外骨骼没起来时转发层应**如实回 503**、不降级成空列表（§6.3），让它拖住 mo-server 启动反而丢掉了这个性质。

### 关键文件

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 编排上述 5 个服务；卷 `mo-pg` / `mo-certs` / `mo-laya-weights` |
| `mo-server/Dockerfile` | Java 服务镜像构建 |
| `mo-exoskeleton/Dockerfile` | 记忆外骨骼镜像构建（与 mo-server 同构的多阶段构建） |
| `mo-dashboard/Dockerfile` + `entrypoint.sh` | Nginx 镜像构建与启动 |
| `mo-dashboard/nginx.conf` | 静态资源 + `/api/` 反代到 mo-server:8080 |
| `install.sh` | 首次安装：生成含随机鉴权密钥的 `.env` |
| `ENV.md` / `.env.example` | 全部环境变量的说明与模板 |

---

## 4. 采集侧

### 4.1 Python SDK（mo_sdk/）

在 Agent 应用进程内旁路采集记忆操作（store/retrieve/forget/consolidate），归一化为 READ/WRITE/UPDATE/EXPIRE，通过 OTLP 上报。

设计遵循「旁路观测容错范式」：观测代码全 try-catch，异常只记 WARN 不影响业务；数据写内存缓冲区，后台单线程批量刷新；缓冲满丢最旧保最新。

| 文件 | 职责 |
|---|---|
| `mo_sdk/core.py` | 核心数据模型：`MemoryEvent`、`MemorySnapshot`、`ObserverConfig`、`MemoryObserver`（编排采集→指标计算→导出触发） |
| `mo_sdk/collector.py` | 采集器：拦截 memory 操作，生成事件对象，缓冲在内存 |
| `mo_sdk/exporter.py` | 导出器：定时/批量将缓冲区事件序列化为 OTLP JSON，HTTP POST 到服务端 |
| `mo_sdk/otel_exporter.py` | OTLP/HTTP JSON 格式构造，对齐 OTel GenAI 语义约定（`memory.*` 属性命名空间）；支持 `api_key` 参数 |
| `mo_sdk/interceptors.py` | 拦截器：装饰器/AOP 零侵入接入 Agent 代码 |
| `mo_sdk/__init__.py` | 包入口，导出公共 API |

`MemoryEvent.event_id` 默认取 `uuid4`，**没有幂等语义**——同一份数据重复上报会产生重复事件。

### 4.2 脚本与示例（examples/、根目录）

| 文件 | 职责 |
|---|---|
| `examples/trae_report_event.py` | 零依赖 CLI 上报脚本（urllib），支持 `--trace-id` / `--parent-span-id` / `--meta k=v`，TraeCode 直接 RunCommand 调用 |
| `examples/trae_importer.py` | 批量导入历史 JSONL 对话日志，从 `~/.trae-cn/memory/projects/` 提取多项目 Agent；`--api-key` 默认取环境变量 `MO_API_KEY` |
| `examples/otel_demo.py` | OTLP 上报演示 |
| `hermes_instrumentation.py` | Hermes 框架插桩示例 |

**turn 埋点契约**（导入器与工作台中间件共同遵守，`memory_turns` 物化依赖它）：

| 键 | 位置 | 说明 |
|---|---|---|
| `turn_message_id` | 主事件 + 子事件 | 同一 turn 的全部事件共享，是物化的分组键 |
| `turn_user` | 仅主事件 | 该 turn 的用户提问 |
| `turn_outcome` | 仅主事件 | 该 turn 的结果摘要 |
| `action_count` | 仅主事件 | **字符串**形式的一轮动作数 |
| `turn_actions` | 仅主事件 | **JSON 数组字符串**，该轮动作列表 |
| `io_kind` | 主事件 + 子事件 | 事件**性质**归一（B4 埋点补强）：`file_read` / `content_search` / `memory_read` / `file_write` / `memory_write` / `tool_other` / `model_call` / `lifecycle`。主事件恒为 `lifecycle`。**与 `operation` 是两把尺子**：`operation` 答「哪种记忆操作」，`io_kind` 答「这一轮到底动了什么」，`memory_turns.io_kind_mix` 与 §4.4 分桶依赖它 |

脚本侧另有 `trae_importer.py` 写入的 `source: "trae_importer"`、`message_id`、`action_full` 等扩展键。

> **`io_kind` 的口径诚实声明**：工作台路径（`MemoryReportMiddleware`）拿到的是**真工具名**，`io_kind` 是观测到的；导入路径（`trae_importer.py`）只有 action 自然语言摘要，`io_kind` 是按关键词**猜的**，属代理口径（同 `_classify_op`）。两条路的词表逐字一致（Java `IoKind.java` ↔ Python `IO_KIND_KEYWORDS`），口径详见《自校准闭环》附录 B4。

### 4.3 工作台中间件

`mo-server` 内的 `MemoryReportMiddleware` 把工作台的每一轮对话转成一个 turn 上报。它取 `AgentInput` 中**最后一条 USER 消息原文**（截 500 字）作为 `turn_user`——这是外部导入路径拿不到的原始量，也是 L6 判据唯一可信的测量面。

### 4.4 操作归一化

| Python 原始 | MO 归一 | 说明 |
|---|---|---|
| retrieve | READ | 检索记忆 |
| store | WRITE | 写入记忆 |
| update / consolidate | UPDATE | 更新/合并记忆 |
| forget / delete | EXPIRE | 遗忘/删除记忆 |

> **别把这张表和 `io_kind` 混起来**（B4 的病根正是两者被混用）。这张表归一的是**记忆操作**（`operation` 列，SDK 契约）；`io_kind` 归一的是**事件性质**（`metadata.io_kind`，B4 埋点补强）。同一个事件可以 `operation=WRITE`（记忆操作轴）而 `io_kind=file_read`（动了什么）——这正是旧设计答不出、B4 要解决的问题。

---

## 5. mo-server（服务端）

单进程内含子层：OTLP 接收 → 内存队列削峰 → 批量写库 → turn 物化 → REST 查询。技术栈：Spring Boot 3.4 + JDBC + PostgreSQL。

### 5.1 观测与存储主链

| 包 / 文件 | 职责 |
|---|---|
| `ServerApplication.java` | Spring Boot 入口，单进程启动全部组件 |
| `resources/application.yml` | 端口 8080；HikariCP（max 5）；`mo.ingest`（队列容量 10000、flush 5000ms）；`mo.turn`（物化开关与间隔）；`mo.analytics` / `mo.usage` / `mo.notify` / `mo.export` / `mo.semantic` 各段配置 |
| `receiver/OtlpReceiver.java` | HTTP 端点 `/v1/traces`，接收 OTLP JSON，交 OtlpParser 解析后入队 |
| `receiver/OtlpParser.java` | 从 `resourceSpans.scopeSpans.spans[]` 提取 `memory.*` 属性重建 MemoryEvent/Snapshot；解析 traceId/parentSpanId 并做缺失兜底（session 派生 trace + orphan 标记） |
| `ingest/IngestQueue.java` | 内存有界队列（ArrayBlockingQueue 10000）削峰 + 后台单线程定时 drain 批量写库；**满则丢最旧** |
| `storage/EventRepository.java` | 数据访问：批量 INSERT 事件/快照 + 全部查询方法 |
| `turn/TurnMaterializer.java` | 把 `memory_events` 按 `turn_message_id` 聚合成 `memory_turns`：启动即跑一轮（首次等价全量回填），之后按间隔增量；水位按事件 `ts` 走，重叠窗口 10 分钟 |
| `model/` | `MemoryEvent`（record，13 字段）、`MemoryOp`（枚举 READ/WRITE/UPDATE/EXPIRE）、`MemorySnapshot` |

### 5.2 判定与分析

| 包 | 职责 |
|---|---|
| `semantic/`（7 类） | laya 语义过滤层：候选召回 + 推理打分，fail-open；`GET /api/v1/semantic/status` 报健康 |
| `risk/` | 内容风险扫描（密钥、连接串、密码等），命中即打掩码 |
| `flow/` | 流程分析：会话流、流程图、流程问题识别 |
| `analytics/` | 跨 Agent / Skill / 问题计数等聚合分析 |
| `notify/`（7 类） | L4 告警闭环：`FindingCollector` → `AlertRepository`（去重/冷却/恢复）→ `WebhookPublisher` / `NotifyScheduler` |
| `security/` | `ApiKeyAuthFilter`：`Authorization: Bearer <MO_API_KEY>` 校验；未配密钥时为开放模式 |
| `excel/` | 用量 Excel 解析（RequestID / 积分 / 模型） |
| `export/` | 事件导出 |
| `exoskeleton/` | `ExoskeletonProxyController`：把 `/api/v1/exoskeleton/**` 原样转发给 mo-exoskeleton（见 §6.3），供工作台「观测 → 记忆提炼」页同源取数；`ExoskeletonSkillController`：**采纳编排器**——`POST /api/v1/exoskeleton/skills/{id}/adopt` 先经转发层取技能 payload、用 `SkillLibrary.save(...,null,null)` 落成**全局**技能，再通知外骨骼标记 ADOPTED。它是**唯一需要 mo-server 插手的写路径**：外骨骼不挂载工作区文件系统，落盘只能由 mo-server 做 |

### 5.3 工作台（agentloop/，约 60 类）

一个完整的 Agent 运行环境，与「监控」本身是两条独立产品线。

| 包 | 职责 |
|---|---|
| `agentloop/workbench/core`（11 类） | Agent 工厂、上下文窗口（五区）、记忆读写、LLM 调用 |
| `agentloop/workbench/tools`（11 类） | 内置工具集 |
| `agentloop/workbench/hitl` | 人机协同：提问、回答、取消 |
| `agentloop/workbench/report` | `MemoryReportMiddleware`：把每轮对话转成 turn 上报 |
| `agentloop/workflow`（10 类） | 工作流引擎（定义、执行、SSE 流式） |
| `agentloop/workspace`（含 plugin / mcp / manifest / web，约 30 类） | 工作区内容（技能、提示、工具）、MCP 接入、文件浏览 |

---

## 6. API 端点

mo-server 内约 90 个端点（含外骨骼转发层与采纳编排器，见 §6.3），mo-exoskeleton 另 17 个。mo-server 的端点全部走 `ApiKeyAuthFilter` 鉴权；mo-exoskeleton 是独立应用、**自身无鉴权过滤器**——它只经 mo-server 转发层对外服务，不直接暴露给局域网。

### 6.1 观测与用量（`/api/v1/*`）

| 分组 | 方法 | 路径 | 功能 |
|---|---|---|---|
| 事件流 | GET | `/agents/{agentId}/events` | 事件列表（分页 + 过滤） |
| | GET | `/agents/{agentId}/events-stats` | 事件统计 |
| | GET | `/events/{eventId}` | 单事件详情（含 metadata + turnEvents） |
| 会话与 Turn | GET | `/agents/{agentId}/sessions` | 会话列表 |
| | GET | `/agents/{agentId}/turns` | Agent 维度 turn 列表 |
| | GET | `/agents/{agentId}/turn-heatmap` | Turn 热力图 |
| | GET | `/agents/{agentId}/turn-message-events` | 单个 turn 的全部事件 |
| | GET | `/sessions/{sessionId}/timeline` | 会话时间线泳道 |
| | GET | `/agents/{agentId}/session-comparison` | 会话对比 |
| Token 分析 | GET | `/agents/{agentId}/token-analytics` | 多维聚合 |
| | GET | `/agents/{agentId}/token-heatmap` | 时间 × 层热力图 |
| | GET | `/agents/{agentId}/token-stats` | 基础 Token 统计 |
| Agent / Skill | GET | `/agents` | Agent 列表 |
| | GET | `/agent-analysis` | Agent 全局对比 |
| | GET | `/agents/{agentId}/skill-analysis` | 单 Agent Skill 分析 |
| | GET | `/skill-analysis` | 全局 Skill 分析 |
| 流程分析 | GET | `/agents/{agentId}/flows` | 会话流列表 |
| | GET | `/agents/flow-summary` | 流程汇总 |
| | GET | `/agents/{agentId}/flow-graph` | 流程图 |
| | GET | `/agents/{agentId}/flow-issues` | 流程问题 |
| | GET | `/agents/{agentId}/flow-issue-detail` | 流程问题详情 |
| | GET | `/agents/{agentId}/flow-sessions` | 流程相关会话 |
| 风险与问题 | GET | `/agents/{agentId}/risk-scan` | 内容风险扫描 |
| | GET | `/analytics/problems` | 问题分析 |
| | GET | `/problem-counts` | 问题计数 |
| 语义层 | GET | `/semantic/status` | laya 后端健康与可用性 |
| Trace | GET | `/traces/{traceId}` | 完整 Trace（spans + tree + statistics） |
| | GET | `/events/{eventId}/trace` | 从事件反查 Trace |
| | GET | `/agents/{agentId}/traces` | Agent 维度 Trace 列表 |
| 写入与导入 | POST | `/events` | 单事件写入 |
| | POST | `/import/single` | 单条导入 |
| | POST | `/import` | 批量导入 |
| | POST | `/import/logs` | `.log` 文件导入（按 MD5 去重） |
| | GET | `/import/template` | 导入模板下载 |
| | GET | `/import/skill-package` | Skill 包下载 |
| 用量 | POST | `/usage/preview` / `/usage/import` | 用量 Excel 预览 / 导入 |
| | GET | `/usage/overview` / `/usage/anomalies` / `/usage/records` | 用量总览 / 异常 / 明细 |
| 导出 | GET | `/export/events` | 事件导出 |
| OTLP | POST | `/v1/traces` | OTLP 上报入口 |

### 6.2 工作台（`/api/agent/*`、`/api/workflow/*`、`/api/mcp/*`）

约 50 个端点，主要分组：

| 前缀 | 数量 | 功能 |
|---|---|---|
| `/api/agent`（`AgentController`） | 7 | 上下文、记忆读取、记忆压缩、对话（SSE）、HITL 问答 |
| `/api/agent/content` | 12 | 工作区内容（技能/提示/工具的 CRUD，分全局、工作区、Agent 三级） |
| `/api/agent/skills` | 14 | 技能包管理、下载、三级绑定 |
| `/api/agent/fs` | 5 | 文件浏览、目录树、读写 |
| `/api/agent/keys` | 3 | 模型密钥：默认值、校验、全局 |
| `/api/workflow` | 2 | 工作流状态、运行（SSE） |
| `/api/mcp` | 7 | MCP 服务器管理、测试、工具列表与调用 |

### 6.3 记忆外骨骼（mo-exoskeleton）

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/v1/exoskeleton/rules` | 返回**八块**：`rules` / `support` / `correctionRate` / `holdout` / `bucketing` / `config` / `taskTypes` / `clusters`。可选 `?taskType=<值>`：按任务类型切分 `support` 与 `correctionRate`（J1）；第七块 `taskTypes` 与第八块 `clusters` **始终是全量**（前者是筛选项的全貌，后者按 A13 只挂在分簇键上）。非法取值 → 计数 0，不报错 |
| POST | `/api/v1/exoskeleton/rules/draft` | 起草：给「支撑度达标但还没有规则」的簇各写一条 SHADOW 草稿（§4.5）；手动触发、可重复调用（幂等） |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/promote` | **晋升闸门**（§4.11）：唯一能把 `state` 从 SHADOW 推到 LIVE 的入口。返回**逐层判决**（① 硬约束 → ② 四项同时改善 → ③ holdout 复现），遇 FAIL 停在那层；不可测（UNKNOWN）不停止但阻止放行。判为拦截时**不改任何状态**。刻意不接受「强制放行」参数——闸门可被参数绕过就不是闸门 |
| POST | `/api/v1/exoskeleton/criteria/recompute` | 判据重算：算 `corrected`（§2.4）与 `is_holdout`（§2.5）并回写 `memory_turns`；手动、幂等。**不受 `?taskType=` 影响，永远全量** |
| POST | `/api/v1/exoskeleton/tasks/classify` | 任务类型分类：按 **session** 粒度调 LLM 生成 `task_type`（封闭 8 值）+ `task_note`（中文备注）并回写；**手动触发、不加调度**，重跑幂等（已分类的 session 不再进候选）。可选 `?force=true`：无视已有分类全量重判并**先清空候选集旧标签**（判失败的 session 回到 NULL 下轮重试）。LLM 未配置时返回 `enabled=false`（fail-open） |
| POST | `/api/v1/exoskeleton/rules/audit` | **规则审计**（§2.6 三道限制 + §2.7 churn）：逐条取簇级判据 → 判决 → 落 `memory_rule_evals` 快照 → 越界才处置。只处置 `LIVE` 规则（SHADOW 不注入、无爆炸半径，读数照记不处置）。可空读数记 `null`（不可测 ≠ 0）。**只降不升**：自动退回影子 / 撤回，回升须人工 |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/revise` | **版本推进**（§2.7）：写入新版本（SHADOW）、旧版置 RETIRED（保留可查）。body 传 `{"body": "..."}`。新增版本后 `churn` 才有真实分母 |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/retire` | 人工停用当前版本（`state=RETIRED`） |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/clear-demotion` | 人工清除降级印记（`demoted_at` + `demote_reason`），**为回升开闸**——清完仍须过 `promote` 闸门，不直接置 LIVE |
| POST | `/api/v1/exoskeleton/cards/generate` | **出卡**（§4.10.5 流水线）：判据表 → 候选 → 选模板 → 填槽 → **合并同类** → 排序截断。幂等：未答刷新（保留 `created_at`）、答「下个周期再报」的重新提出、其余已答跳过。响应里 `templates` 逐条列出**六个模板的触发情况**（未触发的原因也写出来）；**模板库固定六个、能触发的才出** |
| GET | `/api/v1/exoskeleton/cards` | 卡片清单。默认**隐去已到期未答**的卡（到期只是不再展示，不改变卡片状态）；`?includeExpired=true` 连到期的一起列出 |
| POST | `/api/v1/exoskeleton/cards/{cardId}/answer` | **作答**：body `{"answer":"<选项 key>"}`。后果与卡面 `effect` 文案一致——T1「采纳」走 `promote` 闸门（**不等于生效**）、「驳回」停用并抑制该簇；T3「停用」置 RETIRED、「降命中面」升新版本退回影子；T2/T4/T6 与 T5「放宽」**只记录**（改配置的事不由一张卡顺手改）。非法选项 / 已答过 / 卡不存在均如实拒绝 |
| GET | `/api/v1/exoskeleton/skills?agent=&session=` | **技能看板**（「记忆提炼」页）：Agent 清单（**始终全量**，`agentId` / `sessions` / `turns` / `pending`）+ 所选范围内的分类清单（每类 `sessions` / `turns` / 是否达标 `eligible` / 该类提议状态）与提议。口径是「**Agent × 任务类型**」：**未选 `agent` 时不给类清单**（不拿全库聚合冒充「这个 Agent 的分类」）；选定 Agent 后类清单在该范围内**始终全量**——它是「分成了哪些类、每类多少对话」的总览，也是页面自己的选项面。`session`（可选）把清单收窄到单个会话。`__unclassified__`（NULL，还没跑过分类）与 `other`（判过但归不进）刻意分开 |
| GET | `/api/v1/exoskeleton/skills/{id}` | 单条提议 payload（供 mo-server 采纳时取；`id` = `proposal_id` = `agent:<agent_id>:task:<task_type>`） |
| POST | `/api/v1/exoskeleton/skills/discover?agent=` | **技能提炼**：**按 Agent 切开**，再在这一刀之内按 `task_type` 聚该类对话 → 样本门槛（该类 ≥ `min-turns` 条 turn）→ LLM 按**严格口径**提炼「完整闭环技能」→ 落 `memory_skill_proposals`（PENDING）。**必须给 `agent`**（未给则如实返回「未指定 Agent」）。**宁可少而精**：判为无技能则如实留空、不硬凑；幂等（同指纹不重复落、已采纳不动、已不用者指纹变了才重提）。**手动触发，且分类收尾对「本轮新分类的 Agent」逐个接着跑**（详见下行） |
| POST | `/api/v1/exoskeleton/skills/{id}/adopt` | 标记已采纳。**外骨骼侧只记状态、不落盘**（它不挂载工作区文件系统）——落盘由 mo-server 的采纳编排器接管 |
| POST | `/api/v1/exoskeleton/skills/{id}/dismiss` | 标记不用：同一类同一份证据不再重复提（指纹变了才重提） |

**分类收尾自动提炼**：`POST /tasks/classify` 本次有新结果（`classified > 0`）时，同一次调用里**对「本轮真正有新分类的 Agent」逐个跑一轮** `discover`——这就是「自己学、只报结果」：不新增调度器，沿用「手动触发 + 幂等」的既有形态；分类没产出新结果时不空跑（不白付 LLM 调用）。逐个 Agent 提炼代价很小：证据没变的类都会在 `runOnce` 里被整类跳过。

#### 采纳编排（mo-server 侧，唯一插手的写路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/exoskeleton/skills/{id}/adopt` | **采纳编排**（mo-server `ExoskeletonSkillController`；路径比转发层 `**` 更具体，Spring 优先匹配到此）：取 payload → `SkillLibrary.save(...,null,null)` 落**全局**技能 → 通知外骨骼标 ADOPTED。**先落盘、后标记**（顺序不可颠倒：反了会出现「已采纳却没有技能」）；落盘冲突（同名已在其他作用域）如实回 409、不静默覆盖；外骨骼不可达如实回 503。**技能落全局、id 带 Agent 前缀**：技能提议已按「Agent × 任务类型」为单位（登记口 A15），一类任务的手艺不该被某个工作区独占，故仍落全局作用域供所有工作区共享；但 `skillId` 在提炼端加了 Agent 前缀（如 `agent-harness--doc-refinement`），使不同 Agent 的同类技能互不覆盖 |

「不用」（`dismiss`）与看板、提炼都只是外骨骼自己库里的读写，**一律走上面的通用转发层**原样透传——故本编排器只接管 `adopt` 一个端点，不复写其余（见登记口 A14）。

#### 转发层：`/api/v1/exoskeleton/**` → mo-exoskeleton

上表路径由 **mo-server 的 `ExoskeletonProxyController` 原样镜像转发**到外骨骼（基址 `mo.exoskeleton.base-url`：compose 里是服务名 `http://mo-exoskeleton:8081`，本机开发时是 `http://localhost:8081`）。工作台「观测 → 记忆提炼」页走的就是这一条，故浏览器只需同源请求，不必硬编码 8081、也不必给外骨骼加 CORS。

- **路径不翻译**：目标 = `baseUrl + 原始 URI + query`，看页面与看本表是同一串路径。
- **不改写、不缓存、不重试**：外骨骼不可达时**如实回 503** 并说明「未能取数：<异常类名>」，**不降级成空列表**——拿不到卡就说拿不到，与 §4.10「不编证据」同一条原则。
- **转发超时放宽到 3600s**（对齐 `nginx.conf` 的 `proxy_read_timeout`），但**连接超时仍为 2s**。原因：`/tasks/classify` 与 `/skills/discover` 是 LLM 驱动、逐类跑的同步接口，一次要几分钟；卡在 30s 会让「重新提炼」按钮每次都误报成「外骨骼不可达」，明明它在跑。放宽只影响「连上了但对端很慢」，不损害 503 的诚实性。
- **鉴权照旧**：转发的是镜像路径，仍属 `/api/**`，照走 `ApiKeyAuthFilter`；外骨骼自身无鉴权过滤器，故只经这一层对外服务。

---

## 7. 数据库 Schema

建表脚本：`mo-server/src/main/resources/schema.sql`（主库）、`mo-exoskeleton/src/main/resources/schema.sql`（规则表）。两者均幂等（`IF NOT EXISTS`），启动时自动执行。

### 7.1 memory_events（记忆操作事件）

| 列 | 类型 | 说明 |
|---|---|---|
| event_id | TEXT PK | 事件 ID = Span ID（16 hex） |
| agent_id | TEXT | Agent 标识 |
| session_id | TEXT | 会话 ID |
| operation | TEXT | READ/WRITE/UPDATE/EXPIRE。**记忆操作轴**（SDK 契约、看板在用）；**不要**拿它回答「这一轮动了什么」，那是 `metadata.io_kind` 的职责（见下） |
| layer | TEXT | **实际词汇**：`model` / `skill` / `session` / `agent` / `control` / `hitl` / `hint` / `custom`。工作台**从不写** `prompt` / `provider`（旧文档写的 `prompt/session/skill/provider` 与实现不符，已在 B4.3 更正——判据不再依赖 `layer` 词汇，改用 `io_kind`） |
| memory_key | TEXT | 记忆键（文件路径/工具名/会话 ID） |
| memory_summary | TEXT | 前 200 字预览，不含原始数据 |
| token_count | INTEGER | Token 数 |
| latency_ms | DOUBLE | 延迟毫秒 |
| ts | TIMESTAMPTZ | 时间戳 |
| metadata | JSONB | 扩展元数据（turn 契约全在这里） |
| trace_id | VARCHAR(32) | Trace ID（32 hex，一个 session = 一个 trace） |
| parent_span_id | VARCHAR(16) | 父 Span ID（Turn Root 为 NULL） |

### 7.2 memory_snapshots（五区快照）

`snapshot_id` / `agent_id` / `session_id` / `total_tokens` / `system_tokens` / `task_tokens` / `memory_tokens` / `tool_history_tokens` / `free_tokens` / `compression_count` / `last_compression_ratio` / `ts`。

### 7.3 memory_turns（turn 特征物化表）

分类器的输入面。turn 级特征一次性算好落在这张表上，之后所有分析型访问都查它，避免按 JSONB 现算导致全表扫。

| 列 | 类型 | 说明 |
|---|---|---|
| turn_id | TEXT PK | 取自 `metadata.turn_message_id` |
| agent_id / session_id / trace_id | TEXT | 归属 |
| user_text | TEXT | `metadata.turn_user`，写入侧截断到 500 字符 |
| user_text_trgm | TEXT 生成列 | `lower(user_text)`，供 pg_trgm 索引 |
| action_seq | TEXT[] | 工具序列，取自 `metadata.turn_actions` |
| layer_mix / op_mix | JSONB | 各 layer 事件数 / 各 operation 占比 |
| io_kind_mix | JSONB | 各 `io_kind` 事件数（B4 埋点补强）。**由 `TurnMaterializer` 自己的 upsert 写入**——与 `corrected` / `is_holdout` 不同，那两列是判据侧回写、物化不碰；这一列是物化产物。§4.4 分桶据此归一 `shape`（`readonly` / `readwrite` / `writeonly` / `none` / `unknown`） |
| event_count / token_total / latency_ms | 数值 | 该 turn 的聚合量 |
| outcome | TEXT | `metadata.turn_outcome` |
| corrected | BOOLEAN | 隐式纠正标记（§2.4）。**由 mo-exoskeleton 的判据侧回写**，物化器刻意不填 |
| is_holdout | BOOLEAN | 对照组标记（§2.5）。由 mo-exoskeleton `schema.sql` `ALTER` 增列，同样只由判据侧回写 |
| task_type | TEXT | 任务类型（二级筛选维度，session 粒度）：封闭 8 值（`fix_bug` / `add_feature` / `refactor` / `explain` / `config_env` / `test_verify` / `doc_write` / `other`）。由 `POST .../tasks/classify` 回写，物化器不碰。**NULL（没量到）与 `other`（量到了归不进）刻意分开**；**不参与桶键与支撑度计算**，只作筛选 |
| task_note | TEXT | 该 session 任务类型的中文备注（LLM 生成），**只供人眼抽检，不参与任何判定**；模型自述的置信度不落库（同 A2） |
| source | TEXT | `workbench` / `otlp` / `import` |
| started_at | TIMESTAMPTZ | 该 turn 起始时间 |

**注意**：物化水位按事件 `ts` 走，因此**历史数据回灌后需重启 mo-server**（水位重置）才会被回填，纯增量路径会漏掉。

**注意（source 判定）**：`import` 覆盖整个导入家族（`metadata.source` 为 `import` 或 `trae_importer`）。此处曾漏掉 `trae_importer`，导致回灌历史因「带 `turn_user`」被误判成 `workbench`——判据侧据此分不开「可测/不可测」两条路径。另注 `metadata.source` 在两条路径上语义不同：导入器写「来源」，工作台写「子 Agent 路径」。

**注意（判据列不被物化覆盖）**：`corrected`、`is_holdout` 与 `task_type` / `task_note` 都不在 `TurnMaterializer` 的 upsert 列里，故物化重跑（含全量回填）不会擦掉判定与分类结果——已实测：物化跑满 2 轮后 `is_holdout` 618 条完好；全量重物化后 `task_type` 3268/3268 逐值不变。**但重算前若换了数据，需重跑 `POST /criteria/recompute` 刷新。**

### 7.4 其余表

| 表 | 用途 |
|---|---|
| `alert_notifications` | L4 告警推送状态：`dedup_key` 主键保证冷却窗口内只推一次；`payload` 只存已脱敏内容；含 `status`（active/recovered）与 `push_count` |
| `import_log_files` | `.log` 文件 MD5 指纹，同内容不重复抽取 |
| `request_usage` | 用量明细（RequestID / 积分 / 模型 / 客户端 / 时间）；**不对 request_id 加唯一约束**，重复扣费本身就是异常证据 |
| `import_usage_files` | 用量 Excel 的 MD5 指纹 |
| `memory_rules` | 记忆外骨骼的规则表：主键 `(rule_id, version)` 保留旧版本以计算 churn；`state` 为 SHADOW / LIVE / RETIRED。生命周期列：`demoted_at` / `demote_reason`（自动降级印记，**只降不升**——带印记者自动晋升通道关闭）、`hardened_at`（连续两周期「准」无改善的硬化标记，只提示） |
| `memory_rule_evals` | 规则评估快照（§2.6 / §2.7）：每次审计一条，记 `hit_share` / `token_delta` / `fail_delta` / `correction_rate` / `action`。既供「连续两评估周期」的硬化判定攒历史，也留可审计的读数轨迹；**可空列 = 该项结构性不可测，不是 0** |
| `memory_cards` | 决策卡片（§4.10）：`payload` 是**生成当时的卡面快照**（JSONB，判据后来重算也不回填）；`status` 为 OPEN / ANSWERED；`subjects TEXT[]` 记本卡覆盖的簇键（合并卡一次覆盖多个，故为数组）；`expires_at` 到期只是不再展示、**不改变状态**（不替你作决定） |
| `memory_skill_proposals` | 技能提议（观测 · 记忆提炼「按 Agent × 任务类型分类 → 技能发现」）：主键 `proposal_id` **就是 `agent:<agent_id>:task:<task_type>`**（一格一 Agent 一类，天然幂等）；`agent_id` 记提议归属的 Agent（`memory_turns.agent_id`，即遥测上报身份）；`digest_hash` 记提炼输入的摘要指纹（`sha256(agent_id + "\n" + task_type + "\n" + digest)`），供「同输入不重复打扰」判定；`status` 为 PENDING / ADOPTED / DISMISSED。**只落提议、绝不自行装进工作台**——采纳落盘属 mo-server 领域（§6.3）；`task_label` 存中文类名，供人面直接显示 |

### 7.5 索引

| 索引 | 表 / 列 | 用途 |
|---|---|---|
| idx_me_session | memory_events(session_id, ts) | 会话时间线 |
| idx_me_agent | memory_events(agent_id, ts) | Agent 事件流 |
| idx_me_op | memory_events(operation, ts) | 按操作过滤 |
| idx_me_trace | memory_events(trace_id) | Trace 查询 |
| idx_me_parent | memory_events(parent_span_id) | Span 父子关系 |
| **idx_me_metadata** | memory_events USING gin (metadata jsonb_path_ops) | turn_* 键查询；用 `jsonb_path_ops` 而非默认 `jsonb_ops`，体积更小、`@>` / `?` 更快 |
| idx_mt_agent_ts | memory_turns(agent_id, started_at) | turn 列表 |
| idx_mt_trgm | memory_turns USING gin (user_text_trgm gin_trgm_ops) | 相似度匹配；扩展缺失时降级跳过 |
| idx_ms_session | memory_snapshots(session_id, ts) | 快照查询 |
| idx_an_status / idx_an_agent | alert_notifications | 告警状态 / Agent 维度 |
| idx_ru_time / reqid / model | request_usage | 用量查询 |
| idx_mr_cluster / created | memory_rules | 规则簇 / 创建时间 |
| idx_mre_rule | memory_rule_evals(rule_id, version, evaluated_at DESC) | 取该规则该版本最近 N 次评估快照（硬化判定用） |
| idx_mc_status | memory_cards(status) | 按 OPEN / ANSWERED 取卡片清单 |
| idx_mc_subjects | memory_cards USING gin (subjects) | 按簇键查覆盖它的卡片（抑制集、合并卡展开用） |
| idx_msp_status | memory_skill_proposals(status) | 按 PENDING / ADOPTED / DISMISSED 取技能提议清单 |
| idx_msp_agent | memory_skill_proposals(agent_id, status) | 按 Agent 取该 Agent 的提议（含「还等着拍板」的 pending 计数） |

`pg_trgm` 与 `timescaledb` 均放在 `DO $$ ... EXCEPTION` 块中：扩展缺失时降级为普通表并跳过，不阻塞启动。

---

## 8. 前端 Dashboard

暗紫色霓虹风格单页应用。纯 HTML/JS/CSS，无框架依赖。

**位置**：真实前端是 `mo-server/src/main/resources/static/index.html`（约 600 KB，随 mo-server 一起打包）。`mo-dashboard/` 目录里只有 nginx 配置、Dockerfile 与一个 `flow-view-demo.html` 演示页，不再承载主前端。

| 文件 | 职责 |
|---|---|
| `mo-server/src/main/resources/static/index.html` | 全部前端代码 |
| `.../static/marked.min.js` / `mermaid.min.js` / `mo-float-panel.js` / `vendor/` | 前端依赖 |
| `mo-dashboard/nginx.conf` | 静态资源服务 + `/api/` 反代 |

### Tab 结构

| Tab | 功能 |
|---|---|
| 记忆事件流 | 分页表格（20 条/页）+ Agent/Session 过滤 + 点击跳转详情 |
| 消息详情 | 会话选择 → Turn 列表 → 点击打开事件详情抽屉 |
| Token 分析 | 多维：Top5 会话、24h 分布、操作×层矩阵、延迟分位、Top10 key、比率、直方图、日趋势 |
| 会话列表 | 最近活动会话列表 |

另有流程视图、风险评估、用量分析等面板。

### 交互特性

- 事件详情抽屉：基本信息 + memory_summary + turn 详情 + 同 Turn 事件列表 + 原始 JSON
- Agent/Session 下拉框：自动加载真实数据 + x 清除按钮
- 中英双语切换
- SessionId 截断显示 + hover 全量 + 点击自动填充过滤
- 同 Turn 事件点击：平滑切换（不清空抽屉内容，保留滚动位置）

---

## 9. 数据流

```
Agent 应用（Python / Java / 任意）
  │
  │  mo_sdk 装饰器拦截 · trae_report_event.py CLI · trae_importer.py 批量导入
  │  OTLP HTTP POST /v1/traces
  ▼
OtlpReceiver
  │  OtlpParser 解析 memory.* 属性 → MemoryEvent + MemorySnapshot
  │  填充 traceId / parentSpanId（缺失兜底）
  ▼
IngestQueue（ArrayBlockingQueue 10000，5s flush；满则丢最旧）
  │  批量 batchInsert
  ▼
PostgreSQL（memory_events + memory_snapshots）
  │
  ├── TurnMaterializer ──► memory_turns（按 turn_message_id 聚合）
  │
  ├── REST /api/v1/* ──► ApiController
  │     │  事件流 / 时间线 / Token / 流程 / 风险 / Trace
  │     │  Nginx 反代
  │     ▼
  │   Dashboard index.html（5173）
  │
  └── mo-exoskeleton（8081）
        读同一库：memory_events / memory_turns / memory_rules / memory_rule_evals / memory_cards / memory_skill_proposals
        产出：GET /api/v1/exoskeleton/rules（八块，含 J1 与 holdout 对照，支持 ?taskType= 筛选）
             GET /api/v1/exoskeleton/cards（卡片清单，默认隐去到期未答）
             GET /api/v1/exoskeleton/skills?agent=&session=（Agent 清单 + 所选 Agent/Session 范围内的分类清单与技能提议，供「观测 → 记忆提炼」页取数）
        写入：POST /api/v1/exoskeleton/rules/draft → memory_rules（SHADOW 草稿）
              POST /api/v1/exoskeleton/rules/{ruleId}/promote → memory_rules（SHADOW → LIVE，唯一入口）
              POST /api/v1/exoskeleton/rules/{ruleId}/revise|retire|clear-demotion → memory_rules（版本 / 停用 / 清降级印记）
              POST /api/v1/exoskeleton/rules/audit → memory_rule_evals（快照）+ memory_rules（自动降级 / 撤回 / 硬化）
              POST /api/v1/exoskeleton/cards/generate → memory_cards（出卡 / 刷新 / 再出）
              POST /api/v1/exoskeleton/cards/{cardId}/answer → memory_cards（作答）+ memory_rules（T1 走闸门 / T3 停用或退回影子）
              POST /api/v1/exoskeleton/criteria/recompute → memory_turns（corrected / is_holdout）
              POST /api/v1/exoskeleton/tasks/classify → memory_turns（task_type / task_note），收尾对「本轮新分类的 Agent」逐个接着 POST /skills/discover?agent=
              POST /api/v1/exoskeleton/skills/discover?agent= → memory_skill_proposals（按「Agent × 任务类型」提炼技能，落 PENDING 提议；skillId 带 Agent 前缀）
              POST /api/v1/exoskeleton/skills/{id}/adopt|dismiss → memory_skill_proposals（状态流转；adopt 另经 mo-server 落全局技能，见 §6.3）

  外骨骼落盘技能不经自家文件系统（它不挂载工作区目录）：采纳时由 mo-server 的
  ExoskeletonSkillController 取 payload → SkillLibrary.save(...,null,null) 写成全局技能 YAML。
```

工作台的对话走的是另一条入口（`/api/agent/chat`），由 `MemoryReportMiddleware` 在每轮结束时生成 turn 事件，经 `EventReporter` 以 HTTP POST 回本进程的 `/api/v1/events` 上报（与外部上报共用同一条接收与存储链路）。注意该路径**受 `ApiKeyAuthFilter` 保护**：服务端配置了 `MO_API_KEY` 时，`EventReporter` 必须带同一把 key（`agent.report.api-key` 可覆盖，默认取 `mo.auth.api-key`），否则自上报会被 401 拒收、工作台事件一条也进不了库。

---

## 10. Trace 契约

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

### Trace 查询响应格式

`GET /api/v1/traces/{traceId}` 返回：

- `traceId` / `agentId` / `sessionId`：Trace 归属
- `spans[]`：flat span 列表（按 start_ts 升序）
- `tree[]`：预构建树（children 递归）
- `statistics`：totalSpans / turns / criticalPathMs / failedSpans / orphanSpans / topSlowSpans / memoryEffectiveness

---

## 11. 五区 Token 预算

来自 memory_snapshots 表，五个区域的颜色约定：

| 区域 | 颜色 | 说明 |
|---|---|---|
| System | 紫色 #7C5CFF | 系统提示词 |
| Task | 青色 #69E7FF | 任务描述 |
| Memory | 绿色 #62FAD3 | 记忆内容 |
| Tool History | 黄色 #FFB547 | 工具调用历史 |
| Free | 灰色 #5A6580 | 空闲空间 |

---

## 12. 记忆外骨骼（mo-exoskeleton）

L6「能学」的落地模块：把记忆操作日志变成记忆策略规则的半自动流水线，带人工闸门与对照实验。

设计文档：`learn/自校准闭环：设计与实现（L6 能学）.md`（**唯一登记口**，含已定案附录 A 与遗留问题附录 B）。

### 模块结构

| 文件 | 职责 |
|---|---|
| `ExoskeletonApplication.java` | 独立 Spring Boot 入口（端口 8081） |
| `api/RuleController.java` | `GET /api/v1/exoskeleton/rules`（现状）、`POST .../rules/draft`（起草） |
| `api/CriteriaController.java` | `POST /api/v1/exoskeleton/criteria/recompute`（判据重算） |
| `api/TaskTypeController.java` | `POST /api/v1/exoskeleton/tasks/classify`（任务类型分类，手动触发）；启用且本轮确有分类结果时，**收尾对「本轮新分类涉及的 Agent」逐个调 `SkillDiscoverer.runOnce(agentId)`**——「自动、只报结果」的落点，不另起调度器 |
| `api/SkillProposalController.java` | `GET /api/v1/exoskeleton/skills?agent=&session=`（Agent 清单 + 所选范围内的分类清单与技能提议）、`POST .../skills/discover?agent=`（为指定 Agent 手动触发提炼）、`POST .../skills/{id}/adopt|dismiss`（状态流转）。**注意 adopt 只是把库里状态标成 ADOPTED**——真正把技能装进工作台由 mo-server 侧的采纳编排器做（§6.3），因为外骨骼不挂载工作区文件系统 |
| `drafter/RuleDrafter.java` | 规则撰写器契约（§4.5）：`Optional<String> draft(ClusterProfile)`，把「规则由谁写」从机制解耦 |
| `drafter/TemplateRuleDrafter.java` | 确定性替身撰稿器（`@Component`）：按画像查表产出固定文本。B4 切换后改为「`shape` 表 + `steps` 表」拼接（覆盖 20 桶；`unknown` shape 刻意不出规则）；无对应策略返回空（不编） |
| `drafter/RuleDraftService.java` | 起草编排：候选簇 → 起草 → 写 SHADOW；只解决「规则从哪来」，不做调度、不做上线 |
| `promote/PromotionGate.java` | 晋升闸门（§4.11）的**纯判决**：三层（① 硬约束一票否决 → ② 四项同时改善 → ③ holdout 复现）、三态（通过 / 不通过 / 不可测）。**不碰数据库、无随机、无时间依赖**，故能脱离 Spring 单测；参数全部走 `mo.exoskeleton.gate.*`。刻意不写「作弊检测器」——它要求四项同时改善，让作弊在某一项上必露馅 |
| `promote/PromotionService.java` | 晋升编排：取数与落库。**`state=LIVE` 的唯一写入路径**（§4.11 纪律 1）；判决为拦截时零副作用。组向量时取不到的项一律留 `null`（= 不可测），不填 0——否则闸门会把「没测到」读成「改善为 0」。**只降不升**（§2.6 / A5）：带 `demoted_at` 的规则在取到后**短路**返回 ⓪ 层 FAIL，不进三层——否则落库谓词会写 0 行而判决仍报「放行」，成为**假放行** |
| `audit/RuleAuditJudge.java` | 规则审计（§2.6 / §2.7）的**纯判决**：限制一 命中面越界 → `DEMOTE_HIT`、限制二 成本/失败率越界 → `DEMOTE_COST`、churn ≥ 阈值 → `RETIRE_CHURN`、限制三 连续两周期「准」无改善 → `HARDEN`（只提示不停用）。**不碰数据库、无随机、无时间依赖**，可脱离 Spring 单测；参数走 `mo.exoskeleton.audit.*` |
| `audit/RuleLifecycleService.java` | 审计编排：取簇级判据（复用 `CriteriaService.clusters()`）→ 判决 → 落 `memory_rule_evals` 快照 → 执行处置。**只处置 `LIVE` 规则**（SHADOW 不注入、无爆炸半径，读数照记不处置）。人工入口 `revise` / `retire` / `clearDemotion` 带 `@Transactional` |
| `audit/RuleAuditScheduler.java` | 审计调度（§4.7 范式）：`ScheduledExecutorService` + 守护线程 + `scheduleWithFixedDelay`，**刻意不用 `@Scheduled`**；**默认关**（`enabled=false`，没配就不跑）；单次失败只记日志、不影响后续调度 |
| `api/RuleLifecycleController.java` | `POST /rules/audit`（审计）、`/{ruleId}/revise`（版本推进）、`/{ruleId}/retire`（停用）、`/{ruleId}/clear-demotion`（人工回升前清印记）。**这里只降不升**：回升仍须过 `promote` 闸门 |
| `cards/CardPlanner.java` | 决策卡片（§4.10.5）的**纯逻辑**：候选 → 选模板 → 填槽 → 合并同类 → 排序截断。**不碰数据库**，可脱离 Spring 单测。模板三档 / 默认项（恒为「不动」）/ 严重度 / 到期天数**结构固定、人工维护**（§4.10.1）；**证据只填真有的数**，缺项不填 0 也不编（沿用 B7.3）。触发条件：T1 影子+该簇「准」可测+已评估、T2 LIVE+命中面进入区间、T3 已硬化且 LIVE、T5 支撑度不足；**T4/T6 依赖未实现的抽检池与一致性审计，恒不触发并如实说明** |
| `cards/DecisionCardService.java` | 出卡编排：取数（`CriteriaService.clusters()` + `RuleRepository`）→ `planner.plan()` → 落库。`generate()` **幂等**：未答刷新（保留 `created_at`）、答「下个周期再报」的重新提出、其余已答跳过；`list()` 默认隐去到期的卡 |
| `cards/CardActionService.java` | 作答 → 动作回写，**与卡面 `effect` 文案一致**：T1「采纳」走 `PromotionService.promote`（闸门是 LIVE 唯一入口，**不等于生效**）、「驳回」`retire`；T3「停用」`retire`、「降命中面」`revise` 升新版本退回影子；T2/T4/T6 与 T5「放宽」**只记录**。非法选项 / 已答过 / 卡不存在均如实拒绝，不改状态 |
| `api/CardController.java` | `POST /cards/generate`（出卡）、`GET /cards`（清单）、`POST /cards/{cardId}/answer`（作答）。**刻意不做调度**（出卡节奏由人决定，§4.10.6 把「卡片数」当反向指标） |
| `criteria/CorrectionDetector.java` | 隐式纠正判定（§2.4）：两段式相似度 + 困顿迹象（循环 / 失败词表） |
| `criteria/CriteriaService.java` | 判据编排（三期 P0）：重算并回写、J1 headline、命中组/对照组/未命中组交叉指标；`clusters()` 把判据下放到每个桶并**逐簇标注四项可得性**（第八块，CLI 侧全量，不随 `?taskType=` 收窄）。簇内隐式纠正率要求相邻对**两端同桶**，且与 headline 同采信面（只认 `workbench`） |
| `cluster/HoldoutRouter.java` | holdout 路由（§2.5）：SHA-256 确定性分配，与 turn 内容无关 |
| `cluster/ShapeBucketer.java` | 行为形状分桶 v2（`shape` × `steps` = 20 桶）。`shape` 由 `metadata.io_kind` 归一（`readonly` / `readwrite` / `writeonly` / `none` / `unknown`），`steps` 取 `action_seq` 长度（`1-3` / `4-10` / `11+` / `unknown`）；`SQL_IO_KINDS` 被支撑度与判据两侧共用，保证口径同源 |
| `storage/RuleRepository.java` | 规则取数：`SQL_RULES`（版本数 + churn + `demoted_at` / `hardened_at`）、`SQL_SUPPORT`（J2，支持 `taskType` 过滤）、`taskTypeDistribution()`（第七块，**不收过滤参数**，始终全量）、`draftCandidates()` / `insertShadow()`；生命周期：`revise`（旧版 RETIRED + 新版 SHADOW）/ `demote` / `retire` / `harden` / `clearDemotion` / `insertEval` / `recentEvals`；`promote` 谓词带 `AND demoted_at IS NULL`（只降不升的第一道守卫） |
| `storage/TurnCriteriaRepository.java` | 判据取数：读 `memory_turns`、回写 `corrected` / `is_holdout`；`loadTurns(taskTypeFilter)` 支持按任务类型过滤 |
| `storage/CardRepository.java` | 卡片取数：`insert` / `refresh`（只对 OPEN，保留 `created_at`）/ `reissue`（只对 ANSWERED，重置 `created_at`）/ `answer` / `find` / `list` / `count` / `suppressedClusters` / `rulesWithEvals`；`payload` ↔ JSONB。**已记下的取舍**：`reissue` 重置 `created_at` 使 §4.10.6「某周出卡数」算不准，要精确统计需另开只增不改的流水表，本版不建 |
| `skills/SkillDiscoverer.java` | 技能提炼器（**按 Agent × 任务类型**分类 → 技能发现）：**先按 Agent 切开**，再在这一刀之内遍历 8 类（跳过 `other`）→ 过样本门槛（`min-turns`）→ `classTurnTexts(agent,type)` 取该类对话 → `renderDigest` 拼摘要（按 turn 均匀抽样、首尾必取；**前缀与换行也计入 `digest-max-chars`，预算是真兑现的**）→ **算证据指纹 `sha256(agent + "\n" + task_type + "\n" + digest)`，与库里既有指纹比对：没变就整类跳过，不送 LLM、也不打扰人** → `LlmChatClient` → 解析 → `store`。**未指定 Agent 即如实停用**（提炼以「Agent × 任务类型」为单位）。`board(agent,session)` 返回 Agent 清单（始终全量）+ 所选范围内的分类清单与提议，未选 Agent 时不给类清单。system prompt 沿用「铁律一：技能必须是完整闭环，不是零散碎片」的严格口径，**宁可少而精、宁可延后不硬凑**；LLM 不可用即整轮停用、如实报 `enabled=false`。**失败逐类落日志、且区分成因**（超时 / 解析不出 JSON / 缺必需字段 / 判定无新增），四支都记 `agent` 与 `task_type`，超时那支另记 `turns` 与 `digest` 字数——不留「只能靠计数差反推」的盲区（见登记口 B14.1 / B14.4） |
| `skills/SkillProposalRepository.java` | 技能提议取数：`agents()`（Agent 清单，始终全量，含该 Agent 的 pending 提议数）、`sessions(agent)`（Session 下拉的选项面）、`classDistribution(agent,session)`（分类清单，`COALESCE(task_type,'__unclassified__')`——**没量到单列，不混进任何类**；按 agent 过滤、session 可选收窄）、`classTurnTexts(agent,type)`、`listByAgent()`、`store()`（三条「少打扰」规则：同 hash 不重写、已决状态不回退、只落 PENDING）、`mark()`（只改状态、不碰内容） |
| `tasks/TaskType.java` | 任务类型封闭 8 值词表 + 中文标签 + 判据；`disambiguation()` 给三对易混（test_verify↔explain / fix_bug↔doc_write / config_env↔add_feature）的取舍反例；`fromOrOther` 脏值落 `other` 不抛异常 |
| `tasks/LlmChatClient.java` | 零依赖 OpenAI 兼容 HTTP 客户端（**不引 agentscope**）：`temperature=0`，正文空时回落 `reasoning_content`；请求体可选带 `reasoning_effort`（`none` 关掉思考模型的长推理链，见参数节与登记口 B6.7）；未配置时 `isEnabled()=false`（fail-open） |
| `tasks/TaskTypeRepository.java` | 按 session 取待分类素材（`array_agg(user_text)`，保留 turn 边界；`force` 时去 `HAVING task_type IS NULL`）与回写 `task_type` / `task_note`；`clearTaskType` 供 force 重判前清空旧标签 |
| `tasks/TaskClassifier.java` | 分类编排：摘要超限时**按 turn 均匀抽样**（隔 k 条取一条、首尾必取；turn 少时每条都露面、配额变小）；JSON 容错解析；**解析失败不猜、保持 NULL 下轮重试**；单条失败不影响整批；`force` 时先清空候选集旧标签。**收尾回报本轮新分类涉及的 Agent 集合**（供技能提炼按 Agent 逐个触发） |
| `model/` | `MemoryRule`（含 `demotedAt` / `hardenedAt` 生命周期印记）、`RulesReport`、`ClusterProfile`、`CriteriaReport`、`DecisionCard`（`subjects` 列表版）、`CardTemplate`（T1–T6）、`CardReport`（卡片清单/出卡/作答报告）、`TaskClassifyReport`（含本轮新分类的 `agents`）、`SkillProposal`（含 `agentId` + PENDING 常量 + `isPending()`）/ `SkillBoard`（`AgentStat` / `SessionStat` / `ClassStat`）/ `SkillDiscoverReport`（含 `agentId`）、`GateVector` / `PromotionDecision`（晋升闸门）、`RuleAuditReport`（审计报告） |

### 现状（重要）

| 项 | 状态 |
|---|---|
| E1 schema | 归模块自持：建 `memory_rules`，并 `ALTER` 为 `memory_turns` 增 `is_holdout` 列 |
| 规则来源（`RuleDrafter`） | **已落地**（2026-10-06）：确定性替身 + 手动端点；B4 切换后实测写入 7 条 SHADOW（对应 7 个达标桶） |
| 埋点补强（B4） | **已闭环**（2026-10-06）：新增 `metadata.io_kind`（8 值封闭词表），中间件 + 导入器两路同词表；`memory_turns.io_kind_mix` 物化；§4.4 分桶由 8 桶切到 20 桶。`operation` / `layer` 两轴**不动**。导入路径的 `io_kind` 是代理口径 |
| 隐式纠正检测（三期 P0） | **已落地**（2026-10-06）：检测器可运行、可回写；导入路径样本不可采信，工作台路径已首次出数 |
| holdout 路由（三期 P0） | **已落地**（2026-10-06）：确定性分配，实测对照组 621 / 命中组 2631，重跑一致 |
| 工作台真实流量 | **已跑通**（2026-10-06）：修掉 `EventReporter` 自上报 401 后，工作台 turn 首次入库（`source=workbench`，`turn_user` 为原始提问）。当前仅 5 个构造 turn，**自然流量待积累** |
| J1（隐式纠正率） | **量具已可用，样本不足以定性**：工作台路径实测 `transitions=4 / corrections=2 / rate=0.5`（此前 `transitions=0`＝量具没量到；B8 修复前因分母取错曾恒为 `2/2=1.0`）；导入路径 `0.20%` 是摘要域下界，不进验收 |
| J2（支撑度分布） | 实测达标（B4 新口径下 7 个桶 ≥30，最大桶 `writeonly\|steps:4-10` = 958） |
| 二级筛选（任务类型） | **已落地**（2026-10-06，2026-10-07 复测）：LLM 按 session 生成 `task_type`（封闭 8 值）+ `task_note`，`GET /rules` 加第七块与可选 `?taskType=`，可选 `?force=true` 全量重判。**只筛不并**——桶键、规则、闸门全不动。**标签可信度仍未达标**：2026-10-07 关掉思考链后覆盖恢复 32/32；**稳定性已实测通过**（同配置连跑 4 次、标签与备注 0 变动）——此前「标签不稳」系跨配置误报，真实问题是**标签对 prompt 敏感**；已收紧 `add_feature` 宽口径，但全批仅动 3 个 session、`add_feature` 16/32→15/32，**「偏置」的归因被削弱**（保留为待验证的怀疑）；2026-10-07 治「大而杂会话」：真因是**摘要抽样饥饿**（795 轮会话只送 21 条 / 2% 的 turn，而全文仅 23,544 字符），`digest-max-chars` 4000→**30000** 改为**全量送入**，并补 `doc_write↔refactor` 边界——确定性通过，但与旧人工参考一致度 **29→27 略降**，经裁决保「看全量」。详见登记口 B6.8–B6.11 与落地记录 |
| 规则版本与降级（P1） | **已落地**（2026-10-07）：§2.6 三道限制 + §2.7 churn。`audit/RuleAuditJudge`（纯判决）+ `audit/RuleLifecycleService`（取数+落库）+ `audit/RuleAuditScheduler`（默认关）+ 4 个端点；`memory_rules` 加 `demoted_at / demote_reason / hardened_at`，新建 `memory_rule_evals` 快照表。**只降不升**（A5）：落库谓词 `AND demoted_at IS NULL` + `promote()` ⓪ 层短路（后者修掉「写 0 行却报放行」的**假放行**）。实测：审计 7 条全 `NONE`、零副作用（读数照记：`hitShare` 0.008–0.24、`tokenDelta` 有正有负、`failDelta`/`corrRate` 结构性 `null`）；构造 LIVE 后实测 `DEMOTE_HIT`（命中面 24% > 20%）；一次 `revise` 使 **churn 1 → 2**；`clear-demotion` 后回到三层判决（仍 UNKNOWN、不放行）。**限制三（硬化）需两个评估周期，快照表首次写入、尚未自然触发** |
| 晋升闸门 `promote()` | **已落地**（2026-10-07）：`promote/PromotionGate`（纯判决、三层、三态）+ `promote/PromotionService`（取数与落库）+ `POST /rules/{ruleId}/promote`。**这是 `state=LIVE` 的唯一写入路径**——落库 UPDATE 自带 `state='SHADOW'` 谓词，重复晋升与「已停用规则复活」都过不去（§2.6「降级只降不升」）；**并加 ⓪ 层只降不升守卫**：带 `demoted_at` 的规则直接判 FAIL，不靠谓词兜底（避免假放行）。7 条测试复现 lab05 四场景：诚实改善放行、三条退化解分别死在 ①（写垃圾自刷）/②（什么都不写）/③（拒绝难任务）。真实库上**全部拦截**——三条退化解的例子 vs 现实：库里 7 条规则从未注入，不存在「治理前/治理后」两个评估周期，四项中三项结构性不可测（附录 B7），故 3 层全为 UNKNOWN；**拦截时零副作用**（实测 7 条仍全 SHADOW） |
| 簇级判据（第八块） | **已落地**（2026-10-07）：`clusters` 把判据下放到 20 个桶，逐簇报 `turns / supported / hasRule / holdoutTurns / transitions / corrections / correctionRate / avgToken` 并**逐项标注可得性**（`criteria`）。实测：全量 3268 turn、20 桶照报；簇内「准」`transitions=2 / corrections=1`（rate 0.5，落在 `readonly\|steps:11+`）——**口径比 headline 更严**：既要求两端同桶，又只认 `workbench`。**注意它与 headline 不是同一个量**：B8 修复后两者**同形**（都以「配对总数」作分母），但 headline 不要求两端同桶、簇内要求，故**不可直接相加比对**；「记忆有效性 / 多 / 稳 / 约束」四项在所有簇上 `measurable=false`（**结构性缺数据**，非值为 0，理由见登记口 B7） |
| 四项判据 | **在真实库上逐条试算完毕**（2026-10-07，登记口 B7）：两项退化（准可采信面仅 2 个纠正 / 4 对，B8 修复后 rate 0.5；记忆有效性出数即噪声）、一项结构性算不出（稳 `churn ≡ 1`）、一项数据齐但未实现（约束）。故**不补写生产级 SQL**——补了只是把噪声固化成 API。**其中「稳·churn」随后随「规则版本与降级」落地有了真实分母**（版本表出现第二版后 churn 1 → 2，见上）；其余三项的可得性未变 |
| 决策卡片（D，§4.10） | **后端全环已落地**（2026-10-07）：`cards/CardPlanner`（纯逻辑：选模板/填槽/合并同类/排序截断）+ `DecisionCardService`（取数 + 落库）+ `CardActionService`（作答回写）+ `CardRepository` + 3 端点（`generate` / 清单 / 作答），新建 `memory_cards` 表。**模板库固定六个、能触发的才出**：实测 `candidates=13 → merged=8 → created=5`，**首版唯一能自然出量的只有 T5**（样本不足）——因为 T5 不需要任何规则或对照；T1/T3 需构造前置态、T2 需先有 LIVE 规则、T4/T6 依赖未实现的抽检池与一致性审计。**T1 未触发是数据所致**（「准」可测的簇恰好没有规则、有规则的簇恰好量不到「准」，见登记口 **B10**），**非 bug**。实测：`drop` 抑制该簇、`collect` 下轮再出、T1「采纳」走闸门**被拦**（state 仍 SHADOW）、T3「降命中面」升新版本退回影子；三种非法作答（选项非法 / 已答过 / 卡不存在）如实拒绝 |
| 按 Agent × 任务类型分类 → 技能发现（观测 · 记忆提炼） | **已落地**（2026-10-08）：范式从「让人逐张答专业题」改为「记忆提炼自己学、自己调，只报结果」——`skills/SkillDiscoverer`（**先按 Agent 切开**，再在刀内按 `task_type` 聚对话 → 严格口径 LLM → 落提议）+ `SkillProposalRepository` + `SkillProposalController` + `memory_skill_proposals` 表；`classify` 收尾对「本轮新分类的 Agent」逐个接提炼；采纳落全局技能由 mo-server 的 `ExoskeletonSkillController` 编排（§6.3），`skillId` 带 Agent 前缀互不覆盖。**原「决策」页（§4.10 卡面）已从人面撤下**——后端与 39 单测原样保留、无入口。**人面叫法（2026-10-08）**：导航项从「治理 · Governance → 外骨骼」上移到「观测 · Observability → **记忆提炼**」（该组原只此一项，`治理 · Governance` 分组随之撤掉）；页标题 / 文案一并改为「记忆提炼」，**实现上仍叫 mo-exoskeleton**。人面要点：**Agent / Session 两级筛选**（未选 Agent 不出类清单、不设刷新按钮）+ 分类清单（每类 sessions/turns）+ **技能卡把详情摊开**（能干什么 / 完整流程默认展开 / 工具 / 来源）+ 重新提炼（针对所选 Agent）。详见登记口 A15 / B15（口径变更前见 A14 / B14） |

### 参数（`mo.exoskeleton.*`）

> **注意同名不同库**：本节的 `mo.exoskeleton.*` 是 **mo-exoskeleton 自己**的参数（它的 `application.yml`）。**mo-server** 侧另有一个同前缀的 `mo.exoskeleton.base-url`（转发层基址，见 §6.3），由 `MO_EXOSKELETON_BASE_URL` 注入——compose 里默认是服务名 `http://mo-exoskeleton:8081`，本机开发时才是 `http://localhost:8081`。两者互不影响，改哪个要看改的是「外骨骼怎么判」还是「mo-server 往哪儿转发」。

`min-support`（30）、`churn-weeks`（4）、`correction-window-minutes`（10）、`correction-similarity`（0.75）、`holdout-ratio`（0.2）。

`mo.exoskeleton.gate.*`（晋升闸门 §4.11，起点值对齐 lab05 `gate.py`）：`min-effect`（0.05，四项每项至少要改善的绝对百分点）、`churn-tol`（0.0，churn 只要求不上升）、`constraint-tol-token`（0.10，单位成本**相对**容忍；§2.6 的 20% 是「退回影子」阈值，这里 10% 是「不许上线」阈值）、`constraint-tol-fail`（0.0，失败率**绝对**容忍，不许上升）。**注意成本与失败率的容忍算法不同**——前者按比例（`before×(1+tol)`）、后者按绝对值（`before+tol`），这点有测试守着。

`mo.exoskeleton.audit.*`（规则审计 §2.6 / §2.7）：`enabled`（**false**，默认关——没配就不跑，§4.7「不能默认给生产加负载」；手动触发走 `POST /rules/audit`）、`initial-delay-minutes`（1）、`interval-minutes`（1440，一个「评估周期」= 两次相邻审计，默认一天）、`hit-share-max`（0.20，限制一 命中面上限）、`constraint-tol`（0.20，限制二 命中组约束项相对对照组的容忍，**大于**闸门的 `constraint-tol-token` 10%——退回影子门槛比不许上线门槛更宽松）、`churn-high`（3，观察窗 `churn-weeks` 内版本数达到它即撤回）。

`mo.exoskeleton.cards.*`（决策卡片 §4.10）：`cap`（5，§4.10.5 排序截断的**单轮上限**——超出的进 `queued` 排队；§4.10.6 把「卡片数」当**反向指标**，上限是给「人的负担」定的天花板）、`expired-visible`（false，清单默认隐去「已到期未答」的卡；到期只是**不再展示**、不改变卡片状态，不替你作决定）。

`mo.exoskeleton.tasks.*`（二级筛选）：`enabled`（true）、`max-sessions-per-run`（200）、`digest-max-chars`（30000：全库会话全文均能整段送入、不再抽样；超限才按 turn 均匀抽样。2026-10-07 由 4000 上调，见 B6.11）、`timeout-seconds`（180，本机 qwen3 是思考模型，长会话易超时）、`llm.base-url`（`LLM_BASE_URL`，空则分类器停用）、`llm.model`（`LLM_MODEL`）、`llm.api-key`（`LLM_API_KEY`，本机 Ollama 可空）、`llm.reasoning-effort`（`LLM_REASONING_EFFORT`，默认 `none` = 关掉思考链；Ollama 的 OpenAI 兼容端点**只认这个字段**，往 prompt 里塞 `/no_think` 无效——见登记口 B6.7）。**注意 base-url 要按跑法区分**：跑在 compose 里（默认）用 `http://host.docker.internal:11434/v1`（在 `.env` 设 `LLM_BASE_URL`，compose 透传）；本机 `mvn spring-boot:run` 时才用 `http://localhost:11434/v1`（`.env` 里的 `host.docker.internal` 在宿主机不解析）。

`mo.exoskeleton.skills.*`（技能发现）：`enabled`（true）、`min-turns`（30，样本门槛——该类 turns 不够就不提炼，与 `min-support` 同口径）、`max-classes-per-run`（8，单轮最多跑几类）、`digest-max-chars`（20000，该类对话摘要的上限，超限按 turn 均匀抽样；**这个上限是真兑现的**——每条前面的 `[3/12] ` 前缀与分隔换行也计入预算，见登记口 B14.4）。**LLM 连接复用 `mo.exoskeleton.tasks.llm.*`**，不另设一套。

---

## 13. 讲义与文档（learn/）

| 路径 | 内容 |
|---|---|
| `learn/README.md` | 结构标准与六台阶模型（L1 存 → L2 看 → L3 判 → L4 推 → L5 扛 → L6 学） |
| `learn/基础篇/` | 01–06 讲 |
| `learn/进阶篇/` | 07 讲起 |
| `learn/labs/` | `lab01-minimal-loop`、`lab02-rules`、`lab05-selfcal`（自校准三代实验：含「硬化」「自嗨」两种失败复现） |
| `learn/自校准闭环：设计与实现（L6 能学）.md` | L6 的单一登记口 |
| `learn/分类记忆体：设计与实现.md` | `memory_turns` 数据模型与分期验收 |

> 早期文档目录 `docs/data-model-v2/` 已移除，其 V2 五层数据模型的内容并入上述 `learn/` 下的讲义与设计文档。

---

## 14. 文件总览

```
memory-observatory/
├── ARCHITECTURE.md                 # 本文件
├── README.md                       # 快速上手
├── ENV.md / .env.example           # 环境变量说明与模板
├── docker-compose.yml              # postgres · laya-backend · mo-server · mo-dashboard
├── install.sh                      # 首次安装（生成 .env）
├── pom.xml                         # Maven 聚合：mo-server · mo-exoskeleton
├── LICENSE / THIRD_PARTY_NOTICES.md
├── LAYA-INTEGRATION.md             # laya 集成说明
├── LAYA-SEMANTIC-FILTER.md         # 语义过滤层设计
├── OBSERVABILITY-COMPARISON.md     # 与同类方案对比
├── POSITIONING-COMPARISON.md       # 定位对比
├── hermes_instrumentation.py       # Hermes 框架插桩示例
│
├── mo_sdk/                         # 采集：Python SDK
│   ├── core.py / collector.py / exporter.py / otel_exporter.py / interceptors.py
│
├── examples/                       # 采集：脚本与示例
│   ├── trae_report_event.py        # 实时上报 CLI
│   ├── trae_importer.py            # 批量导入历史 JSONL
│   └── otel_demo.py
│
├── mo-server/                      # 服务端（观测 + 判定 + 工作台）
│   └── src/main/
│       ├── java/io/memobservatory/
│       │   ├── server/
│       │   │   ├── ServerApplication.java
│       │   │   ├── api/            # ApiController · WriteController · UsageController · ExportController · dto/
│       │   │   ├── receiver/       # OtlpReceiver · OtlpParser
│       │   │   ├── ingest/         # IngestQueue
│       │   │   ├── storage/        # EventRepository
│       │   │   ├── turn/           # TurnMaterializer
│       │   │   ├── model/          # MemoryEvent · MemoryOp · MemorySnapshot
│       │   │   ├── semantic/       # laya 语义过滤
│       │   │   ├── risk/           # 内容风险扫描
│       │   │   ├── flow/           # 流程分析
│       │   │   ├── analytics/      # 聚合分析
│       │   │   ├── notify/         # L4 告警闭环
│       │   │   ├── security/       # ApiKeyAuthFilter
│       │   │   ├── excel/          # 用量 Excel 解析
│       │   │   └── export/         # 事件导出
│       │   └── agentloop/          # 工作台
│       │       ├── workbench/      # core · tools · hitl · report（含 IoKind · MemoryReportMiddleware）· web
│       │       ├── workflow/       # 工作流引擎
│       │       └── workspace/      # 内容 · 技能 · MCP · 文件 · web
│       └── resources/
│           ├── application.yml
│           ├── schema.sql          # 主库建表
│           └── static/index.html   # 真实前端（约 600 KB 单页）
│
├── mo-exoskeleton/                 # 记忆外骨骼（L6，compose 服务，容器端口 8081）
│   └── src/main/
│       ├── java/io/memobservatory/exoskeleton/
│       │   ├── ExoskeletonApplication.java
│       │   ├── api/                # RuleController · CriteriaController · TaskTypeController · PromotionController · RuleLifecycleController · CardController
│       │   ├── drafter/            # RuleDrafter · TemplateRuleDrafter · RuleDraftService
│       │   ├── promote/            # PromotionGate · PromotionService
│       │   ├── audit/              # RuleAuditJudge · RuleLifecycleService · RuleAuditScheduler
│       │   ├── cards/              # CardPlanner · DecisionCardService · CardActionService
│       │   ├── criteria/           # CorrectionDetector · CriteriaService
│       │   ├── tasks/              # TaskType · LlmChatClient · TaskTypeRepository · TaskClassifier
│       │   ├── storage/            # RuleRepository · TurnCriteriaRepository · CardRepository
│       │   ├── cluster/            # ShapeBucketer · HoldoutRouter
│       │   └── model/              # MemoryRule · RulesReport · ClusterProfile · CriteriaReport · DecisionCard · CardTemplate · CardReport · TaskClassifyReport · GateVector · PromotionDecision · RuleAuditReport
│       └── resources/
│           ├── application.yml
│           └── schema.sql          # memory_rules + memory_rule_evals + memory_cards + 为 memory_turns 增 is_holdout / task_type / task_note 列
│
├── mo-dashboard/                   # Nginx 层（前端已迁到 mo-server/static）
│   ├── nginx.conf / Dockerfile / entrypoint.sh
│   └── flow-view-demo.html
│
├── laya-backend/                   # 语义过滤推理后端（Apache-2.0）
│   ├── server.py / requirements.txt / Dockerfile
│   └── LICENSE / NOTICE
│
├── scripts/                        # setup-laya-backend.sh · check-laya.py · mo-observe.sh 等
│
├── learn/                          # 讲义、实验与设计文档
│   ├── README.md                   # 六台阶结构标准
│   ├── 基础篇/ · 进阶篇/
│   ├── labs/                       # lab01 · lab02 · lab05
│   ├── 自校准闭环：设计与实现（L6 能学）.md   # L6 单一登记口
│   └── 分类记忆体：设计与实现.md
│
└── test-logs/                      # 样例日志与生成脚本
    ├── gen_showcase.py
    └── events-showcase-20260913.log
```
