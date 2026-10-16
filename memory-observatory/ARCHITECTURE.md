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
| `scripts/ci-check.sh` | 本地全量检查（与 CI 同构）：Java 全模块测试（含 S1/S2/S3 守则）+ Python 采集层测试，`--docker` 加构建冒烟 |
| `.github/workflows/ci.yml` | CI 守门（2026-10-12）：每次 push/PR 强制跑 java-test / python-test / docker-build 三个 job |
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

### 4.2 统一接入层（mo_ingest/）

一个接入层，两种接入方案，输出同一套标准化字段；接入时按平台可选。整层**零依赖（纯 stdlib）**——Hook 端能整目录拷到任意机器跑。

| 文件 | 职责 |
|---|---|
| `mo_ingest/contract.py` | 标准化契约的**唯一真相**：`MemoryEvent`、`operation` 归一、`io_kind` 词表与工具名/关键词映射、trace/span 推导 |
| `mo_ingest/otlp.py` | OTLP/HTTP JSON payload 构造 + `urllib` POST + `Bearer` 鉴权（`event_to_span` 属性对齐 `mo_sdk/otel_exporter.py`，额外补 `memory.trace_id` / `gen_ai.system`） |
| `mo_ingest/registry.py` | 平台注册表（`PLATFORMS`：id/显示名/模式/source/默认路径/状态目录） |
| `mo_ingest/hook/base.py` + `hook/qoder.py` + `hook/trae.py` | **hook 方案**（实时旁路）：stdin JSON → 事件 → 上报；跨进程 turn 状态累积；stdout 恒 `{}` |
| `mo_ingest/log/base.py` + `log/trae.py` + `log/workbuddy.py` | **log 方案**（旁路日志批量）：读日志目录 → 聚合成 turn → 批量上报 |
| `mo_ingest/cli.py` + `__main__.py` | `python -m mo_ingest --platform <id> --mode hook\|log …`（`list` 子命令列平台） |

接入时按平台可选：

| 平台 id | 模式 | source | 默认路径 / 状态目录 |
|---|---|---|---|
| `qoder` | hook | `qoder_hook` | 状态目录 `~/.qoder/hooks/.mo_state` |
| `trae` | log / hook | log=`trae_importer`，hook=`trae_hook` | log 读 `~/.trae-cn/memory/projects`；hook 状态目录 `~/.trae-cn/hooks/.mo_state` |
| `workbuddy` | log | `workbuddy_importer` | `~/.workbuddy/audit-log` |

> **`trae` 一平台两模式**：`modes` 顺序为 `("log", "hook")`——默认模式仍是 log，`hook` 必须显式 `--mode hook`。
> 理由是 `cli.py` 与 `get_adapter` 都取 `modes[0]` 作默认，若 hook 在首位，`python -m mo_ingest --platform trae` 会变成等 stdin 阻塞，是危险的默认值反转。
>
> **两个 hook 适配器的 turn 切分方式不同**：Qoder 事件带 `request_set_id`，直接拿它当 turn 键；
> Trae **无此字段**，改用 `UserPromptSubmit` 开新 turn / `Stop` 收尾的配对 + 会话计数器（`{sid}#N`）。
> Trae 亦无 `PostToolUseFailure`，失败从 `tool_response` 推断。

CLI 示例：

```
python -m mo_ingest list
echo '{...}' | MO_HOOK_DRY_RUN=1 python -m mo_ingest --platform qoder --mode hook
echo '{...}' | MO_HOOK_DRY_RUN=1 python -m mo_ingest --platform trae --mode hook
python -m mo_ingest --platform trae --mode log --since 20260801 --send
python -m mo_ingest --platform workbuddy --mode log            # 默认只分析
```

**来源家族归类（服务端）**：`metadata.source` 是**平台原始值**（`qoder_hook` / `trae_hook` / `trae_importer` / `workbuddy_importer`）；服务端把它归类到家族 `import / hook / workbench / otlp`，清单存在 DB 表 `event_source_family`（`schema.sql` 幂等播种），由 `TurnMaterializer` 调用 `SourceFamilyClassifier` 装载（60s TTL 惰性刷新）。归类规则：命中清单 → 取清单里的 family；否则有 `turn_user`/`turn_actions` → `workbench`；否则 → `otlp`。**新增平台只需运行时 `INSERT INTO event_source_family` 一行，零改 Java、零重建镜像**（故清单不放 `application.yml`——它在 jar 内，改它必须重建）。`memory_turns` 落 `source`（家族）+ `platform`（原始来源），按平台分析不必回 join `memory_events`。

**turn 埋点契约**（导入器与工作台中间件共同遵守，`memory_turns` 物化依赖它）：

| 键 | 位置 | 说明 |
|---|---|---|
| `turn_message_id` | 主事件 + 子事件 | 同一 turn 的全部事件共享，是物化的分组键 |
| `turn_user` | 仅主事件 | 该 turn 的用户提问 |
| `turn_outcome` | 仅主事件 | 该 turn 的结果摘要 |
| `action_count` | 仅主事件 | **字符串**形式的一轮动作数 |
| `turn_actions` | 仅主事件 | **JSON 数组字符串**，该轮动作列表 |
| `io_kind` | 主事件 + 子事件 | 事件**性质**归一（B4 埋点补强）：`file_read` / `content_search` / `memory_read` / `file_write` / `memory_write` / `tool_other` / `model_call` / `lifecycle`。主事件恒为 `lifecycle`。**与 `operation` 是两把尺子**：`operation` 答「哪种记忆操作」，`io_kind` 答「这一轮到底动了什么」，`memory_turns.io_kind_mix` 与 §4.4 分桶依赖它 |

接入层 `mo_ingest` 另有平台原始键：`log/trae.py` 写 `source: "trae_importer"`、`message_id`、`action_full` 等扩展键；`log/workbuddy.py` 写 `source: "workbuddy_importer"`；`hook/qoder.py` 写 `source: "qoder_hook"`；`hook/trae.py` 写 `source: "trae_hook"`。

> **`io_kind` 的口径诚实声明**：工作台路径（`MemoryReportMiddleware`）与 **hook 路径**（`mo_ingest/hook/qoder.py`、`hook/trae.py`，宿主 stdin 直接给真工具名）拿到的是**真工具名**，`io_kind` 是观测到的；导入路径（`mo_ingest/log/trae.py`）只有 action 自然语言摘要，`io_kind` 是按关键词**猜的**，属代理口径（同 `_classify_op`）。两条路的词表逐字一致（Java `IoKind.java` ↔ Python `IO_KIND_KEYWORDS`），口径详见《自校准闭环》附录 B4。WorkBuddy 导入路径（`mo_ingest/log/workbuddy.py`，审计流）介于两者之间：`commandPreview` 原样记工具调用，多数能拿到**真工具名**，认不出的仍退回关键词猜。

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
| `storage/EventRepository.java` | 数据访问**门面**（`@Repository`，实现 `EventQueryPort`/`EventStreamPort`）：保留 `(JdbcTemplate, ObjectMapper)` 构造器与全部公开方法签名，内部按域委托给同包协作类——`EventWriteStore`（批量 INSERT 事件/快照 + 导入日志）、`EventReadStore`（事件/时间线/基础聚合/导出流）、`TurnStore`（轮次）、`TokenAnalyticsStore`（Token 多维分析）、`AnalyticsStore`（Agent 对比 + Skill 指标）、`ProblemStore`（阈值/流程问题取数）、`TraceStore`（链路）。共享件（时间绑定 `ts()`、jsonb、`EVENT_MAPPER`、`SKILL_CALL_FILTER`/`FLOW_LAYERS` 等）沉到 `SqlRepositoryBase`。2026-10-12：原 1836 行单类拆分，5 个注入点零改动，21 条集成测试作安全网 |
| `storage/SqlRepositoryBase.java` | 协作类公共底座（包内可见，abstract）：`jdbc`/`mapper` + 通用件。PG JDBC 42.7 不认裸 `Instant`，所有时间参数统一经 `ts()` 转 `Timestamp`（拆分前 `queryEvents`/`countEvents`/`eventStats` 漏转导致 `/events?from=` 500，已修） |
| `turn/TurnMaterializer.java` | 把 `memory_events` 按 `turn_message_id` 聚合成 `memory_turns`：启动即跑一轮，之后按间隔增量；水位持久化在 `memory_materialize_watermark`（2026-10-12：重启从库接续，不再 EPOCH 全量回看；水位只当提示，正确性由候选全量重聚合 + turn_id 幂等 upsert 兜底，库故障时 fail-open 退回全扫）；重叠窗口 10 分钟 |
| `model/` | `MemoryEvent`（record，13 字段）、`MemoryOp`（枚举 READ/WRITE/UPDATE/EXPIRE）、`MemorySnapshot` |

### 5.2 判定与分析

| 包 | 职责 |
|---|---|
| `semantic/`（7 类） | laya 语义过滤层：候选召回 + 推理打分，fail-open；`GET /api/v1/semantic/status` 报健康 |
| `risk/` | 内容风险扫描（密钥、连接串、密码等），命中即打掩码 |
| `flow/` | 流程分析：会话流、流程图、流程问题识别 |
| `analytics/` | 跨 Agent / Skill / 问题计数等聚合分析 |
| `notify/`（7 类） | L4 告警闭环：`FindingCollector` → `AlertRepository`（去重/冷却/恢复）→ `WebhookPublisher` / `NotifyScheduler` |
| `security/` | `ApiKeyAuthConfig`：注册共享鉴权过滤器（实现在 mo-support `support.security.ApiKeyAuthFilter`，2026-10-12 由 server/exo 两份重复副本归并），本站保护 `/api/**` 与 `/v1/**`；未配密钥时为开放模式 |
| `excel/` | 用量 Excel 解析（RequestID / 积分 / 模型） |
| `export/` | 事件导出 |
| `exoskeleton/` | `ExoskeletonProxyController`：把 `/api/v1/exoskeleton/**` 原样转发给 mo-exoskeleton（见 §6.3），供工作台「观测 → 记忆提炼」页同源取数；`ExoskeletonSkillController`：**采纳编排器**——`POST /api/v1/exoskeleton/skills/{id}/adopt` 先经转发层取技能 payload、用 `SkillLibrary.save(...,null,null)` 落成**全局**技能，再通知外骨骼标记 ADOPTED。它是**唯一需要 mo-server 插手的写路径**：外骨骼不挂载工作区文件系统，落盘只能由 mo-server 做；`SkillLoadRepository`：**装载回流的写侧**（P0-1，登记口 A23）——`AgentFactory.build()` 装配成功后，把本次装载的**动态 yaml 技能** upsert 进 `memory_skill_loads`（`ON CONFLICT … DO UPDATE SET load_count = load_count + 1`），**异步 + 进程内 TTL 去重 + try/catch fail-open**（失败仅 WARN）。它记的是「装进去了」这一事实，**不伪造调用归因** |

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

mo-server 内约 90 个端点（含外骨骼转发层与采纳编排器，见 §6.3），mo-exoskeleton 另 17 个。鉴权实现只在 **mo-support** 有一份（`support.security.ApiKeyAuthFilter`，2026-10-12 归并；此前 server/exo 各持一份逐字重复的副本），两服务各自 `security/ApiKeyAuthConfig` 以 `@Bean` 注册并声明本站前缀：同一协议、同一把 `MO_API_KEY`（server 拦 `/api/**` 与 `/v1/**`，外骨骼只拦 `/api/**`；未配 key = 开放模式仅 WARN）——回环绑定只是第一道防线，不再唯一道。

### 6.1 观测与用量（`/api/v1/*`）

> 2026-10-12：原 `api/ApiController.java`（1192 行、42 端点）按资源域拆为多个 `@RestController`，均共用同一 `@RequestMapping("/api/v1")` 基址，**路由逐字不变**（Spring 允许不同控制器共享基址，只要「路径+方法」全局唯一，已核 `uniq -d` 无歧义映射、活体启动 `Started ServerApplication` 无 Ambiguous）。`ApiController` 保留核心查询端点（事件流/会话 Turn/Token 分析/Agent·Skill 分析），另拆出 `FlowController`（流程 5）、`RiskSemanticController`（风险/语义/问题计数 3）、`TraceController`（链路 3 + 建树/统计私有件）、`ImportController`（模板与 Skill 包下载，POI/zip 重依赖整域内聚）。下表路径不变。

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
| POST | `/api/v1/exoskeleton/tasks/classify` | 任务类型分类：按 **session** 粒度调 LLM 生成 `task_type`（封闭 8 值）+ `task_note`（中文备注）并回写，同时落 **口径版本** `task_type_version`（`TaskType.TAXONOMY_VERSION`，当前 `taxonomy-v1`）与 `classified_at`；**手动触发、不加调度**，重跑幂等（已分类的 session 不再进候选）。可选 `?force=true`：无视已有分类全量重判并**先清空候选集旧标签**（含版本与时间；判失败的 session 回到 NULL 下轮重试）。**同 Agent 已有分类在跑时如实跳过**（运行级互斥，不重复发 LLM）。报告里 `staleTurns` 报出「还是旧口径」的 turn 数、`failures` 逐条记失败原因。LLM 未配置时返回 `enabled=false`（fail-open） |
| POST | `/api/v1/exoskeleton/rules/audit` | **规则审计**（§2.6 三道限制 + §2.7 churn）：逐条取簇级判据 → 判决 → 落 `memory_rule_evals` 快照 → 越界才处置。只处置 `LIVE` 规则（SHADOW 不注入、无爆炸半径，读数照记不处置）。可空读数记 `null`（不可测 ≠ 0）。**只降不升**：自动退回影子 / 撤回，回升须人工 |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/revise` | **版本推进**（§2.7）：写入新版本（SHADOW）、旧版置 RETIRED（保留可查）。body 传 `{"body": "..."}`。新增版本后 `churn` 才有真实分母 |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/retire` | 人工停用当前版本（`state=RETIRED`） |
| POST | `/api/v1/exoskeleton/rules/{ruleId}/clear-demotion` | 人工清除降级印记（`demoted_at` + `demote_reason`），**为回升开闸**——清完仍须过 `promote` 闸门，不直接置 LIVE |
| POST | `/api/v1/exoskeleton/cards/generate` | **出卡**（§4.10.5 流水线）：判据表 → 候选 → 选模板 → 填槽 → **合并同类** → 排序截断。幂等：未答刷新（保留 `created_at`）、答「下个周期再报」的重新提出、其余已答跳过。响应里 `templates` 逐条列出**六个模板的触发情况**（未触发的原因也写出来）；**模板库固定六个、能触发的才出** |
| GET | `/api/v1/exoskeleton/cards` | 卡片清单。默认**隐去已到期未答**的卡（到期只是不再展示，不改变卡片状态）；`?includeExpired=true` 连到期的一起列出 |
| POST | `/api/v1/exoskeleton/cards/{cardId}/answer` | **作答**：body `{"answer":"<选项 key>"}`。后果与卡面 `effect` 文案一致——T1「采纳」走 `promote` 闸门（**不等于生效**）、「驳回」停用并抑制该簇；T3「停用」置 RETIRED、「降命中面」升新版本退回影子；T2/T4/T6 与 T5「放宽」**只记录**（改配置的事不由一张卡顺手改）。非法选项 / 已答过 / 卡不存在均如实拒绝 |
| GET | `/api/v1/exoskeleton/skills?agent=&session=` | **技能看板**（「记忆提炼」页）：Agent 清单（**始终全量**，`agentId` / `sessions` / `turns` / `pending`）+ 所选范围内的分类清单（每类 `sessions` / `turns` / 是否达标 `eligible` / 该类提议状态）与提议。口径是「**Agent × 任务类型**」：**未选 `agent` 时不给类清单**（不拿全库聚合冒充「这个 Agent 的分类」）；选定 Agent 后类清单在该范围内**始终全量**——它是「分成了哪些类、每类多少对话」的总览，也是页面自己的选项面。`session`（可选）把清单收窄到单个会话。每类还带 `reason`——**无提议时**「这类为何还没提炼出技能」的中文一句话（样本不足 / LLM 超时 / 解析失败 / 证据未变已跳过 / 已取消 / 尚未提炼），**有提议时为 null**（出了提议就不必再解释）。`__unclassified__`（NULL，还没跑过分类）与 `other`（判过但归不进）刻意分开。**每条提议另带 `reasoning`**（提炼思考历程，见 §7.4 与登记口 A25 / A26）：**日志总结** 2 项（系统硬统计 `logFacts`〔几个会话 / 多少条对话 / 时间跨度〕+ 模型总结 `logSummary`）+ 模型自述 4 项 + 确定性证据 2 项，供卡片上「提炼思考历程」入口就地展开——**系统口径的硬统计与模型自述 / 总结分列**（A2：模型自述不作判据，可核对的只有系统那半） |
| GET | `/api/v1/exoskeleton/skills/{id}` | 单条提议 payload（供 mo-server 采纳时取；`id` = `proposal_id` = `agent:<agent_id>:task:<task_type>`） |
| POST | `/api/v1/exoskeleton/skills/discover?agent=` | **技能提炼（同步）**：**按 Agent 切开**，再在这一刀之内按 `task_type` 聚该类对话 → 样本门槛（该类 ≥ `min-turns` 条 turn）→ LLM 按**严格口径**提炼「完整闭环技能」→ 落 `memory_skill_proposals`（PENDING）。**必须给 `agent`**（未给则如实返回「未指定 Agent」）。**宁可少而精**：判为无技能则如实留空、不硬凑；幂等（同指纹不重复落、已采纳不动、已不用者指纹变了才重提）。**同 Agent 已有提炼在跑时如实返回「跳过」报告**（运行级互斥），**破坏性行为为零**。**手动触发，且分类收尾对「本轮新分类的 Agent」逐个接着跑**（详见下行） |
| POST | `/api/v1/exoskeleton/skills/discover/async?agent=` | **技能提炼（异步）**：立即返回 `SkillDiscoverAccepted`（`accepted` / `agentId` / `running` / `note`），真正的提炼在后台跑，进度见 `status`。**与同步共用同一把互斥**：该 Agent 已在跑时 `accepted=false, running=true`，如实拒绝、不重复发 LLM。页面「重新提炼」走这条 |
| GET | `/api/v1/exoskeleton/skills/discover/status?agent=` | **提炼进度**：`running` / `startedAt` / `elapsedMs` / `tokensUsed` / `totalClasses` / `doneClasses` / `currentClass` / `cancelRequested` / `lastReport`。`tokensUsed` 是本轮累计消耗的 token（来自 sink 回调累加，`>0` 时前端进度条补一行「已用 {n} token」——预算开着时人看得见余量）。进度条目**跑完保留**（`running=false` 仍能读 `lastReport`），故页面刷新也能恢复；**从未提炼过与缺数据一律 `null`**，不造值 |
| POST | `/api/v1/exoskeleton/skills/discover/cancel?agent=` | **请求取消**：置取消标志，主循环在**每个类开始前**查它 → 为真即停并返回已完成的局部结果（`lastReport.stopReason="cancel"`），**此后不再发任何 LLM 调用**。**是类间真停**，不是前端断开等待；当前没有在跑的提炼时如实回 `cancelRequested=false`（不谎报「已取消」） |
| POST | `/api/v1/exoskeleton/skills/discover/fact?agent=` | **事实提炼（同步）**（`FactDiscoverer`）：与技能提炼是**两条独立的人面**——技能问「这类任务怎么做」（一格一「Agent × 任务类型」），事实答「这个项目长什么样」（**一格一 Agent、不分任务类型**）。取该 Agent **全部**对话 → 样本门槛（≥ `min-turns`）→ LLM 只提炼「**项目 / 代码结构约定**」（模块划分、目录约定、命名规范、关键类职责、依赖关系）→ 复用同一张 `memory_skill_proposals`，以**哨兵 `task_type=__fact__`**（`task_label=项目结构约定`）存一格一 Agent（`proposal_id=agent:<id>:task:__fact__`）。产物是 PENDING 提议（`tools` 为空），采纳后由 mo-server 写成**全局「事实片段」YAML**、当常驻注入片段用。一轮一次 LLM 调用、**同步**（无需进度面），复用 `EvidenceDigest` 与两道跳过闸门、用量记在能力名 `fact` 下。**必须给 `agent`** |
| POST | `/api/v1/exoskeleton/skills/{id}/adopt` | 标记已采纳。**外骨骼侧只记状态、不落盘**（它不挂载工作区文件系统）——落盘由 mo-server 的采纳编排器接管 |
| POST | `/api/v1/exoskeleton/skills/{id}/dismiss` | 标记不用：同一类同一份证据不再重复提（指纹变了才重提） |
| POST | `/api/v1/exoskeleton/skills/dismiss-batch` | **批量不用**：body `{"ids":[...]}`，逐条独立走与单条 `dismiss` 相同的路径，部分成功如实报——`{total, succeeded, failed, message, results[]}`，`results` 逐条给 `{proposalId, ok, message}`。HTTP **恒 200**（失败信息在每一条里），`ids` 为空如实回 400。**这条在外骨骼侧**（不像 `adopt-batch` 走 mo-server），因它不动工作台文件系统 |
| GET | `/api/v1/exoskeleton/skills/{id}/versions` | **版本历史**（新 → 旧）：返回该提议每个版本的 `version` / `name` / `prompt` / `tools` / `status`（PENDING / ADOPTED / SUPERSEDED）/ `created_at`。只在「有版本可言」（`version > 1` 或非 PENDING）的卡上被前端懒加载；无此提议 → 404 |
| GET | `/api/v1/exoskeleton/skills/events?agent=&limit=` | **事件流水**（操作历史 + 历史视图）：按时间倒序返回某一 Agent 的 `kind`（OFFER / QUIET / FAILED / SKIP / ADOPT / DISMISS / REVISE / DEDUP）/ `actor`（HUMAN / SYSTEM）/ `proposalId` / `taskType` / `version` / `detail`。只增不改的账本，与处于覆盖态的 `memory_skill_class_runs` 刻意分开 |
| GET | `/api/v1/exoskeleton/skills/{id}/effect` | **效果验证**（采纳前后对照）：以**采纳时刻**为界，把同 Agent 同任务类型的会话按首 turn 分成「界前 / 界后」两组，比三项「越低越好」的读数（准·隐式纠正率 / 失败率 / 回合数），判「**不劣化即通过**」。三态：任一组样本不足 → `UNKNOWN`（不假装通过也不假装失败）、任一项明显变差 → `FAIL`、三项都不劣化 → `PASS`（持平也算过）。**只出结论、不改任何能力**——撤回采纳由人拍板（判决与动作分离，与 `promote` 同一纪律）。未采纳（无 `decidedAt`）如实报不可测。**埋点口径说明**：无逐 turn「技能是否被用上」观测，故这是暴露组的上界近似（登记口 B17） |
| GET | `/api/v1/exoskeleton/skills/usage` | **LLM 用量**：按「Agent × 能力 × 任务类型」报调用次数与 token（能力名 `task_type`=分类 / `skill`=技能提炼 / `fact`=事实提炼）。失败也计一次调用但 token 记 0；端点未上报 `usage` 时如实留空、**不造 0**（`null` = 端未报，≠ 端报了 0）。进程内内存态、重启归零 |
| POST | `/api/v1/exoskeleton/sessions/{sessionId}/task-type` | **单会话纠正**（P1-4）：body `{"taskType":"<词表值>", "note":"<可选备注>"}`，把某 session 的 `task_type` 改成人指定的值（整 session UPDATE）。校验用 `TaskType.isValidValue`（匹配 value / name / label 任一，`"other"` 本身合法）——**未命中如实回 400、不回落 `other`**；该 session 一条 turn 都没命中 → 404。**只改标签、不触发下游**：下游重提炼由人手动「重新提炼」触发，也不自动撤销该类已出的提议。返回 `{sessionId, taskType, label, updated, note}`。**会覆盖该 session 原有 `task_note` 与 `classified_at`**（登记口 B22.4）。看板另加 `taskTypes`（全词表）供下拉选项 |

**分类收尾自动提炼**：`POST /tasks/classify` 本次有新结果（`classified > 0`）时，同一次调用里**对「本轮真正有新分类的 Agent」逐个跑一轮** `discover`——这就是「自己学、只报结果」：不新增调度器，沿用「手动触发 + 幂等」的既有形态；分类没产出新结果时不空跑（不白付 LLM 调用）。逐个 Agent 提炼代价很小：证据没变的类都会在 `runOnce` 里被整类跳过。

#### 采纳编排（mo-server 侧，唯一插手的写路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/exoskeleton/skills/{id}/adopt` | **采纳编排**（mo-server `ExoskeletonSkillController`；路径比转发层 `**` 更具体，Spring 优先匹配到此）：取 payload → `SkillLibrary.save(...,null,null)` 落**全局**技能 → 通知外骨骼标 ADOPTED。**先落盘、后标记**（顺序不可颠倒：反了会出现「已采纳却没有技能」）；落盘冲突（同名已在其他作用域）如实回 409、不静默覆盖；外骨骼不可达如实回 503。**技能落全局、id 带 Agent 前缀**：技能提议已按「Agent × 任务类型」为单位（登记口 A15），一类任务的手艺不该被某个工作区独占，故仍落全局作用域供所有工作区共享；但 `skillId` 在提炼端加了 Agent 前缀（如 `agent-harness--doc-refinement`），使不同 Agent 的同类技能互不覆盖 |
| POST | `/api/v1/exoskeleton/skills/adopt-batch` | **批量采纳**：body `{"ids":[...]}`，对每条走与单条 `adopt` **完全相同**的路径（取 payload → 落全局 → 回写状态）。**逐条独立、部分成功如实报**——一条同名冲突（409）不连带其余几条不采纳；HTTP **恒 200**（失败信息在每一条里，不藏进整体状态码），响应 `{total, succeeded, failed, message, results[]}`，`results` 逐条给 `{proposalId, ok, skillId, scope, marked, message}`。`ids` 为空如实回 400。**串行执行**（条数有限，避免并发写技能目录）；任一条失败继续下一条。**事实片段走同一条路**（`tools` 为空，落成全局常驻注入片段） |

「不用」（`dismiss` / `dismiss-batch`）、版本历史（`versions`）、事件流水（`events`）与看板、提炼、效果验证都只是外骨骼自己库里的读写，**一律走上面的通用转发层**原样透传——故本编排器只接管 `adopt` 与 `adopt-batch` 两个端点（后者是把前者串起来），不复写其余（见登记口 A14 / A18 / A19）。**版本演进也不需要 mo-server 改动**：出新版只是外骨骼把提议行的 `version` / 内容前进一位并在 `memory_skill_versions` 落新版；人点「更新」复用同一个 `POST /{id}/adopt`，编排器照旧按提议行最新 prompt 重写 YAML（见登记口 A19）。

#### 转发层：`/api/v1/exoskeleton/**` → mo-exoskeleton

上表路径由 **mo-server 的 `ExoskeletonProxyController` 原样镜像转发**到外骨骼（基址 `mo.exoskeleton.base-url`：compose 里是服务名 `http://mo-exoskeleton:8081`，本机开发时是 `http://localhost:8081`）。工作台「观测 → 记忆提炼」页走的就是这一条，故浏览器只需同源请求，不必硬编码 8081、也不必给外骨骼加 CORS。

- **路径不翻译**：目标 = `baseUrl + 原始 URI + query`，看页面与看本表是同一串路径。
- **不改写、不缓存、不重试**：外骨骼不可达时**如实回 503** 并说明「未能取数：<异常类名>」，**不降级成空列表**——拿不到卡就说拿不到，与 §4.10「不编证据」同一条原则。
- **转发超时放宽到 3600s**（对齐 `nginx.conf` 的 `proxy_read_timeout`），但**连接超时仍为 2s**。原因：`/tasks/classify` 与 `/skills/discover` 是 LLM 驱动、逐类跑的同步接口，一次要几分钟；卡在 30s 会让「重新提炼」按钮每次都误报成「外骨骼不可达」，明明它在跑。放宽只影响「连上了但对端很慢」，不损害 503 的诚实性。
- **鉴权透传**：转发的是镜像路径，仍属 `/api/**`，照走 mo-server 的 `ApiKeyAuthFilter`；外骨骼侧（**同一份** mo-support `support.security.ApiKeyAuthFilter`，经 `mo-exoskeleton/security/ApiKeyAuthConfig` 注册）持同一把 key 再验一道——转发层把浏览器带来的 `Authorization` 原样透传，没带则用本服务配置的 key 补；`ExoskeletonSkillController` 的出向调用（取 payload / 回写已采纳）同样自动补带。

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
| task_type_version | TEXT | 分类所用的**口径版本**（`TaskType.TAXONOMY_VERSION`，当前 `taxonomy-v1`）。**改 prompt 或枚举取值时必须 +1**——否则前后两批分类混在一列里无法区分、被误当同一把尺子。`countStaleVersion` 数出「有 `task_type` 但版本为空或不是当前版本」的 turn = 待重跑（`force` 分类）的盘子。加列前判过的行此处为 NULL（= 旧口径） |
| classified_at | TIMESTAMPTZ | 该 session 被判定为当前任务类型的时刻（写回时 `now()`）。随 `task_type` 一并清空（force 重判前）——不与「新判据产出的标签」错配 |
| task_judge_mode | TEXT | 这条标签**是怎么来的**（2026-10-11 补）：`single`（1 评委）/ `multi-N`（N 评委多数决）/ `human`（人工纠正覆盖）。因单次 LLM 判定可靠性与掷硬币相当（SkillLens 实证准确率 46.4%），分类改为奇数评委多数决。人工纠正（`SessionController.writeBack`）写 `human`——它没有评委票，且必须盖掉上一轮 LLM 的口径，否则「人改过的标签」还挂着旧票型，读数会骗人。由分类侧回写，物化器不碰 |
| task_judge_votes | TEXT | 该次判定的票型，如 `fix_bug=2,explain=1`（按票数降序）。**票并列即判失败、不猜**（保持 NULL 下轮重试），故不会出现并列票型落库。人工纠正为 NULL。由分类侧回写，物化器不碰 |
| source | TEXT | `workbench` / `otlp` / `import` |
| started_at | TIMESTAMPTZ | 该 turn 起始时间 |

**注意**：物化水位按事件 `ts` 走，因此**历史数据回灌后需重启 mo-server**（水位重置）才会被回填，纯增量路径会漏掉。

**注意（source 判定）**：家族归类不再硬编码在 `TurnMaterializer` 里，而是查 DB 表 `event_source_family`（由 `SourceFamilyClassifier` 装载，60s TTL）。导入家族清单（`import` / `trae_importer` / `workbuddy_importer` / …）就存在这张表里。此处曾两度漏项：先漏 `trae_importer`、再漏 `workbuddy_importer`，都会因「带 `turn_user` / `turn_actions`」被误判成 `workbench`——判据侧据此分不开「可测/不可测」两条路径。故新增导入来源时只需 `INSERT INTO event_source_family` 一行（零改 Java、零重建），回归测试 `SourceFamilyClassifierTest` 已覆盖「新增一行即生效」。另注 `metadata.source` 在两条路径上语义不同：导入器写「来源」，工作台写「子 Agent 路径」。

**注意（判据列不被物化覆盖）**：`corrected`、`is_holdout` 与 `task_type` / `task_note` / `task_type_version` / `classified_at` / `task_judge_mode` / `task_judge_votes` 都不在 `TurnMaterializer` 的 upsert 列里，故物化重跑（含全量回填）不会擦掉判定与分类结果——已实测：物化跑满 2 轮后 `is_holdout` 618 条完好；全量重物化后 `task_type` 3268/3268 逐值不变。**但重算前若换了数据，需重跑 `POST /criteria/recompute` 刷新。**

### 7.4 其余表

| 表 | 用途 |
|---|---|
| `alert_notifications` | L4 告警推送状态：`dedup_key` 主键保证冷却窗口内只推一次；`payload` 只存已脱敏内容；含 `status`（active/recovered）与 `push_count` |
| `import_log_files` | `.log` 文件 MD5 指纹，同内容不重复抽取 |
| `request_usage` | 用量明细（RequestID / 积分 / 模型 / 客户端 / 时间）；**不对 request_id 加唯一约束**，重复扣费本身就是异常证据 |
| `import_usage_files` | 用量 Excel 的 MD5 指纹 |
| `memory_rules` | 记忆外骨骼的规则表：主键 `(rule_id, version)` 保留旧版本以计算 churn；`state` 为 SHADOW / LIVE / RETIRED。生命周期列：`demoted_at` / `demote_reason`（自动降级印记，**只降不升**——带印记者自动晋升通道关闭）、`hardened_at`（连续两周期「准」无改善的硬化标记，只提示） |
| `memory_rule_evals` | 规则评估快照（§2.6 / §2.7）：每次审计一条，记 `hit_share` / `token_delta` / `fail_delta` / `correction_rate` / `action`。既供「连续两评估周期」的硬化判定攒历史，也留可审计的读数轨迹；**可空列 = 该项结构性不可测，不是 0** |
| `memory_draft_cache` | 撰写器**草稿缓存**（登记口 B20.3）：主键 `cluster_key`，存 `body` + `model`（只作审计）。把 §4.5「同一画像同一文本」这条契约从**进程内**扩到**跨重启**——首个成功结果落这一行，重启后先查它，命中就直接回、连模型都不问。`ON CONFLICT DO NOTHING`（同簇句子一旦定下不再改写，这就是确定性的落点）；**失败 / 越界不写**（下次仍重试）。它**不是第二条写库路径**：不进注入、不进闸门、不影响任何判据，删行即重算 |
| `memory_cards` | 决策卡片（§4.10）：`payload` 是**生成当时的卡面快照**（JSONB，判据后来重算也不回填）；`status` 为 OPEN / ANSWERED；`subjects TEXT[]` 记本卡覆盖的簇键（合并卡一次覆盖多个，故为数组）；`expires_at` 到期只是不再展示、**不改变状态**（不替你作决定） |
| `memory_skill_proposals` | 技能提议（观测 · 记忆提炼「按 Agent × 任务类型分类 → 技能发现」）：主键 `proposal_id` **就是 `agent:<agent_id>:task:<task_type>`**（一格一 Agent 一类，天然幂等）；`agent_id` 记提议归属的 Agent（`memory_turns.agent_id`，即遥测上报身份）；`digest_hash` 记提炼输入的摘要指纹（`sha256(agent_id + "\n" + task_type + "\n" + digest)`），供「同输入不重复打扰」判定；`status` 为 PENDING / ADOPTED / DISMISSED。**`version` 记「当前第几版」**：已采纳技能不再永久冻住，内容确有变化（`contentHash`）且证据确有增长（`turns` 增量 ≥ `version-min-new-turns`）时，`store()` 写 v+1（**提议行不翻回 PENDING**，仍保持 ADOPTED）。**只落提议、绝不自行装进工作台**——采纳落盘属 mo-server 领域（§6.3）；`task_label` 存中文类名，供人面直接显示。**`reasoning`（可空 TEXT）记「提炼思考历程」**（登记口 A25 / A26）：JSON 对象，含**日志总结** 2 项（`logFacts` 系统硬统计〔几个会话 / 多少条对话 / 时间跨度，由 `classSessions` 总数 + 该类 turns + `classSpan` 首末日期拼成〕、`logSummary` 模型一两句话总结这批日志在做什么）+ **模型自述** 4 项（`patterns` / `preferences` / `keyPoints` / `evidence`）+ **确定性证据** 2 项（`sessions` 依据的会话 id、`samples` 会话代表原话）——**系统口径的硬统计与模型自述 / 总结分列**是 A2「模型自述不能当标签」的直接落地。旧行 / 模型没给心路 / 事实卡（`__fact__`）均为 NULL，卡片据此不显示入口（A26 后，新提议因**必有硬统计**而入口恒显示，见登记口 B24.5）；**`contentHash` 与 digest 均不含它**，故心路措辞变化不刷版本、不动跳过闸门（登记口 B24.4） |
| `memory_skill_versions` | 技能**版本内容快照**（只增不改）：主键 `(proposal_id, version)`，把每个版本的 `name` / `description` / `prompt` / `tools` / `digest_hash` 钉住。`status ∈ PENDING`（当前待采纳）/ `ADOPTED`（当前已采纳）/ `SUPERSEDED`（被新版顶下去的旧版）。`skill_id` 从 v1 起**钉死**（文件名即身份，换 id 会留孤儿 yaml）——版本演进只换内容、不换 id（登记口 B18.1）。**与 `memory_skill_events` 的分工**：本表答「这份 vN 的内容到底是什么」，事件表答「谁在何时做了什么」 |
| `memory_skill_events` | 技能**事件流水**（只增不改，人机共用，操作历史 + 历史视图合一张）：`kind ∈ OFFER / QUIET / FAILED / SKIP / ADOPT / DISMISS / REVISE / DEDUP`，`actor ∈ HUMAN / SYSTEM`，附 `version` / `digest_hash` / `detail`（一句话说明「为什么这么做」）。与 `memory_skill_class_runs` 的分工：后者是**覆盖式**断点续跑缓存（一格一行、只留最近一次，判「这轮要不要跳过」），本表是**只增不改**的流水（每轮追加、答「历史上发生过什么」）——正如 `memory_rules`（当前态）vs `memory_rule_evals`（历史读数）。`proposal_id` 可空，为将来「不属于任何一格」的系统事件留位 |
| `memory_skill_class_runs` | 提炼**断点续跑**记账：主键 `(agent_id, task_type)`，一格记「上轮这一类判成了什么」——`digest_hash`（证据指纹，与提议同算法）、`outcome`、`turns`、`reason`、`updated_at`。`outcome ∈ QUIET`（判过而确无技能）/ `FAILED`（LLM 超时 / 解析失败等）/ `OFFERED`（出了提议，留作读数）。**只有 `QUIET` 参与「证据未变则跳过」**：`FAILED` 必须重试（把失败也跳过等于把错误固化）、`OFFERED` 交由提议指纹闸门管，故不跳过 |
| `memory_skill_loads` | **装载回流**（P0-1，登记口 A23）：主键 `(agent_id, skill_id)`，记 `load_count`（装载次数）/ `first_loaded_at` / `last_loaded_at` / `last_session_id` / `workspace_id`。记录「本次装配装载了哪些**已采纳**技能」——**口径就叫「装载」不叫「调用」**：现有 tool 事件无法归因到具体技能，故只记「装进去了」这一事实，**不伪造调用归因**。**表建在外骨骼 schema、由 mo-server 直写、外骨骼读取出报表**（沿用 `memory_turns` 先例，不新增 HTTP 上报链路）；写入走旁路 try/catch fail-open + 进程内 TTL 去重，读侧只出报表。只读角色不装载技能、静态内置技能与手工技能无 proposal，故回流天然缺失（登记口 B21） |
| `memory_skill_runs` | 提炼**运行态**（P1-3，登记口 A23；P2 加 `HALTED`，登记口 A24）：主键 `run_id`，记 `agent_id` / `status`（**五态**：RUNNING / DONE / CANCELLED / HALTED / INTERRUPTED）/ `total_classes` / `done_classes` / `current_class` / `started_at` / `finished_at`。它落的是「**一次运行的生命周期**」——与 `memory_skill_class_runs`（一格一类的**覆盖式**缓存，判「下轮跳不跳过」）粒度与生命周期都不同，故另起一张表。**五态为什么不是四态**：`CANCELLED` 只表示「**人**点了取消」；预算到顶 / 熔断是**系统自己**刹的车，混进去事后答不出「为什么没跑完」，故单列 `HALTED`。启动时 `@PostConstruct` 把遗留 `RUNNING` 一律标 `INTERRUPTED`（**单实例前提**：内存 `Progress` 全丢 → 遗留 RUNNING 必为中断），使重启后页面能如实显示「上轮被中断（done/total），可重跑」。写入走旁路 try/catch、`bumpDone` 用 `done_classes = done_classes + 1` **原子自增**（登记口 B22） |

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
| **idx_mt_agent_task** | memory_turns(agent_id, task_type) | 按「Agent × 任务类型」取该类 turn（技能提炼取原文、看板分类清单），P0-2 加（登记口 A23）；避免大表全扫 |
| idx_ms_session | memory_snapshots(session_id, ts) | 快照查询 |
| idx_an_status / idx_an_agent | alert_notifications | 告警状态 / Agent 维度 |
| idx_ru_time / reqid / model | request_usage | 用量查询 |
| idx_mr_cluster / created | memory_rules | 规则簇 / 创建时间 |
| idx_mre_rule | memory_rule_evals(rule_id, version, evaluated_at DESC) | 取该规则该版本最近 N 次评估快照（硬化判定用） |
| idx_mc_status | memory_cards(status) | 按 OPEN / ANSWERED 取卡片清单 |
| idx_mc_subjects | memory_cards USING gin (subjects) | 按簇键查覆盖它的卡片（抑制集、合并卡展开用） |
| idx_msp_status | memory_skill_proposals(status) | 按 PENDING / ADOPTED / DISMISSED 取技能提议清单 |
| idx_msp_agent | memory_skill_proposals(agent_id, status) | 按 Agent 取该 Agent 的提议（含「还等着拍板」的 pending 计数） |
| idx_msv_agent | memory_skill_versions(agent_id, created_at DESC) | 取某 Agent 的版本快照（版本链按时间倒序） |
| idx_mse_agent | memory_skill_events(agent_id, created_at DESC) | 按 Agent 取事件流水（历史视图：这一格/这个 Agent 发生过什么） |
| idx_mse_proposal | memory_skill_events(proposal_id, created_at DESC) | 取某条提议的事件流水（`proposal_id` 可空，故不设唯一约束） |
| idx_msl_skill | memory_skill_loads(skill_id) | 按技能取装载回流报表（P0-1，登记口 A23） |
| idx_msr_agent | memory_skill_runs(agent_id, started_at DESC) | 取某 Agent 最近一次运行态（P1-3，登记口 A23；`lastRun` 按此读最近一条） |

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
  │  mo_sdk 装饰器拦截 · mo_ingest 统一接入层（hook 实时旁路 / log 批量导入）
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
        读同一库：memory_events / memory_turns / memory_rules / memory_rule_evals / memory_cards / memory_skill_proposals / memory_skill_versions / memory_skill_events / memory_skill_class_runs / memory_skill_loads / memory_skill_runs
        产出：GET /api/v1/exoskeleton/rules（八块，含 J1 与 holdout 对照，支持 ?taskType= 筛选）
             GET /api/v1/exoskeleton/cards（卡片清单，默认隐去到期未答）
             GET /api/v1/exoskeleton/skills?agent=&session=（Agent 清单 + 所选 Agent/Session 范围内的分类清单与技能提议（每条带 reasoning 提炼思考历程：**日志总结**〔系统硬统计 + 模型总结〕+ 模型自述 + 确定性证据，供卡片入口就地展开）+ loads 装载回流报表〔P0-1〕+ taskTypes 全词表〔供单会话纠正下拉〕，供「观测 → 记忆提炼」页取数）
             GET /api/v1/exoskeleton/skills/{id}/versions（版本历史：该提议每个版本的内容快照与状态，新 → 旧；无此提议 404）
             GET /api/v1/exoskeleton/skills/events?agent=&limit=（事件流水：按时间倒序的 kind / actor / detail，供历史视图；只增不改）
             GET /api/v1/exoskeleton/skills/{id}/effect（采纳前后对照的效果验证判决，纯读：取该 Agent×该类 turn 分组比读数，不写库）
             GET /api/v1/exoskeleton/skills/usage（LLM 用量：按「Agent × 能力 × 任务类型」的调用与 token，来自进程内内存计量器，不落库）
        写入：POST /api/v1/exoskeleton/rules/draft → memory_rules（SHADOW 草稿）
              POST /api/v1/exoskeleton/rules/{ruleId}/promote → memory_rules（SHADOW → LIVE，唯一入口）
              POST /api/v1/exoskeleton/rules/{ruleId}/revise|retire|clear-demotion → memory_rules（版本 / 停用 / 清降级印记）
              POST /api/v1/exoskeleton/rules/audit → memory_rule_evals（快照）+ memory_rules（自动降级 / 撤回 / 硬化）
              POST /api/v1/exoskeleton/cards/generate → memory_cards（出卡 / 刷新 / 再出）
              POST /api/v1/exoskeleton/cards/{cardId}/answer → memory_cards（作答）+ memory_rules（T1 走闸门 / T3 停用或退回影子）
              POST /api/v1/exoskeleton/criteria/recompute → memory_turns（corrected / is_holdout）
              POST /api/v1/exoskeleton/tasks/classify → memory_turns（task_type / task_note / task_type_version / classified_at），收尾对「本轮新分类的 Agent」逐个接着 POST /skills/discover?agent=
              POST /api/v1/exoskeleton/skills/discover?agent=（同步）→ memory_skill_proposals（按「Agent × 任务类型」提炼技能，落 PENDING 提议；skillId 带 Agent 前缀）+ memory_skill_versions（落该版本内容快照）+ memory_skill_class_runs（逐类记 QUIET / FAILED / OFFERED，供下轮断点续跑：只跳过 QUIET 类）+ memory_skill_events（逐类记 OFFER / QUIET / FAILED / SKIP；已采纳技能内容变且证据增长时记 REVISE、写新版 v+1 并落快照）
              POST /api/v1/exoskeleton/skills/discover/async?agent=（异步，立即返回）+ GET /skills/discover/status?agent=（进度/上次报告）+ POST /skills/discover/cancel?agent=（类间真停）——三者与同步共用同一把进程内互斥，写的是同一份内存进度表；进度另**旁路落** memory_skill_runs（P1-3：RUNNING → DONE / CANCELLED / HALTED / INTERRUPTED，全程 try/catch、`done_classes` 原子自增；P2 新增 HALTED = 预算到顶 / 熔断的**系统自停**，登记口 A24），启动时把遗留 RUNNING 标 INTERRUPTED，`status` 据此如实报「上轮已中断」
              POST /api/v1/exoskeleton/skills/discover/fact?agent=（同步，单次 LLM 调用，无进度面）→ memory_skill_proposals（每 Agent 一份「项目结构约定」事实片段，task_type 用哨兵 __fact__，落 PENDING 提议；skillId 带 Agent 前缀）+ memory_skill_versions + memory_skill_class_runs（记 QUIET / FAILED / OFFERED）+ memory_skill_events
              POST /api/v1/exoskeleton/skills/{id}/adopt|dismiss → memory_skill_proposals（状态流转；adopt 另经 mo-server 落全局技能，见 §6.3）+ memory_skill_versions（该版本行置 ADOPTED、其余 ADOPTED 版本降 SUPERSEDED）+ memory_skill_events（记 ADOPT / DISMISS，actor=HUMAN）
              POST /api/v1/exoskeleton/skills/dismiss-batch → 逐条独立走单条 dismiss 链，写 memory_skill_proposals + memory_skill_events，部分成功如实报、HTTP 恒 200
              POST /api/v1/exoskeleton/skills/adopt-batch（mo-server 侧，见 §6.3）→ 逐条独立走单条采纳链，部分成功如实报、HTTP 恒 200
              POST /api/v1/exoskeleton/sessions/{sessionId}/task-type（单会话纠正，P1-4）→ memory_turns（task_type / task_note / classified_at，整 session UPDATE；只改标签、不触发下游重提炼，会覆盖原 task_note，见 §6.3）
              （memory_skill_loads 不由外骨骼写：装配发生在 mo-server，由 mo-server 的 SkillLoadRepository 直写、外骨骼只读取出报表，见 §5.2 / §6.3）

  外骨骼落盘技能不经自家文件系统（它不挂载工作区目录）：采纳时由 mo-server 的
  ExoskeletonSkillController 取 payload → SkillLibrary.save(...,null,null) 写成全局技能 YAML。
  单条（adopt）与批量（adopt-batch）走的是同一条落盘路径——批量只是把单条串起来、逐条独立报结果。
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
| `api/SkillProposalController.java` | `GET /api/v1/exoskeleton/skills?agent=&session=`（Agent 清单 + 所选范围内的分类清单与技能提议）、`POST .../skills/discover?agent=`（同步，为指定 Agent 手动触发技能提炼）、`POST .../skills/discover/fact?agent=`（同步，事实提炼）、`POST .../skills/discover/async\|cancel` + `GET .../skills/discover/status`（异步发起 / 取消 / 查进度）、`GET .../skills/{id}/effect`（效果验证）、`GET .../skills/usage`（LLM 用量）、`POST .../skills/{id}/adopt\|dismiss`（状态流转）。**同步与异步共用同一把互斥**（同一 Agent 已在跑时都不新起一轮）。**注意 adopt 只是把库里状态标成 ADOPTED**——真正把技能装进工作台由 mo-server 侧的采纳编排器做（§6.3），因为外骨骼不挂载工作区文件系统 |
| `api/SessionTaskTypeController.java` | **单会话纠正**（P1-4，登记口 A23）：`POST /api/v1/exoskeleton/sessions/{sessionId}/task-type`——把某 session 的 `task_type` 改成人指定的值（整 session UPDATE）。校验用 `TaskType.isValidValue`，**未命中如实回 400、不回落 `other`**；`updated==0` → 404。**只改标签、不触发下游**（下游重提炼由人手动「重新提炼」触发）；**不预先 `clearTaskType`**（`writeBack` 整 session 覆盖本身即清，clear 只属 `force` 重判）、不新增写库方法。经 mo-server 通配转发自动可达（§6.3） |
| `drafter/RuleDrafter.java` | 规则撰写器契约（§4.5）：`Draft draft(ClusterProfile)`，把「规则由谁写」从机制解耦。**`Draft` = 一句草稿，或 一句「为什么没产出」**（`Draft.of` / `Draft.none`）——不产出时必须说清真因，报告面据此如实呈现（登记口 B20.1） |
| `drafter/TemplateRuleDrafter.java` | 确定性替身撰稿器：按画像查表产出固定文本。B4 切换后改为「`shape` 表 + `steps` 表」拼接（覆盖 20 桶；`unknown` shape 刻意不出规则）；查表没有则 `Draft.none(...)` 并说明是「表里没有」（不编）。**装配开关**：`@ConditionalOnProperty(drafter.mode=template, matchIfMissing=true)`——默认替身 |
| `drafter/LlmRuleDrafter.java` | 真 LLM 撰稿器（§4.5 的生产实现）：把簇画像拆成人话（行为形状 + 步数档 + 支撑度 + **该簇代表性样本**，B20.2——样本由编排侧 `RuleRepository` 取好放进画像，本类仍是**无依赖纯件**）送 `LlmChatClient`，生成一句记忆策略。三条纪律：只写不生效 / 只写记忆策略 / 不编（未配置、调用失败、返回空、**产出越界**一律 `Draft.none(...)` **并带真因**，失效隔离在本层）。**越界机器校验**（B20.4）：提示词禁推理步骤之外，另用一份**保守**词表做机器判（枚举 / 分步结构 `第N步`·`步骤一`·`①②③`·`下一步`·`分步`，推理规划词 `推理`·`规划`·`思考`，猜意图词 `用户意图`·`猜测` 等），命中即不产出、不写也不缓存；词表是**实机调出来的**——裸「步骤」曾误伤真实产出「该忘：已完成的中间步骤」，故只在**作为枚举标记**时才算。**确定性**：首个成功结果由 `DraftCache` 记住——进程内经 L1 内存、**重启后经 `memory_draft_cache` 表**（B20.3），使「同一画像同一文本」跨重启字面成立。**装配开关**：`@ConditionalOnProperty(drafter.mode=llm)`——与替身二选一，注入点永远只有一个 `RuleDrafter` bean |
| `drafter/DraftCache.java` | 草稿缓存（B20.3）：L1 进程内 `ConcurrentHashMap`（省掉重复 DB 往返）→ L2 `memory_draft_cache` 表（重启后兜住确定性）。只记不判——不进注入、不进闸门、不影响任何判据；**读写失败一律降级**（读＝当作没缓存、写＝只留 L1）并只记日志，缓存是优化，它坏了不该拖垮起草 |
| `drafter/RuleDraftService.java` | 起草编排：候选簇 → 起草 → 写 SHADOW；只解决「规则从哪来」，不做调度、不做上线。撰写器不产出时，把**它给出的真因原样**写进 `outcome.detail`（不再是一句笼统的「撰写器无对应策略」——登记口 B20.1 已修） |
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
| `storage/RuleRepository.java` | 规则取数：`SQL_RULES`（版本数 + churn + `demoted_at` / `hardened_at`）、`SQL_SUPPORT`（J2，支持 `taskType` 过滤）、`taskTypeDistribution()`（第七块，**不收过滤参数**，始终全量）、`draftCandidates()`（候选簇；**带上按簇样本**，B20.2）、`samplesByBucket()`（每桶最近 8 条非空 `user_text`、单条截 120 字；分桶在 Java 里用同一个 `ShapeBucketer`，**不把口径抄进 SQL**，保证样本所属的簇与支撑度所属的簇是同一个簇）、`insertShadow()`；生命周期：`revise`（旧版 RETIRED + 新版 SHADOW）/ `demote` / `retire` / `harden` / `clearDemotion` / `insertEval` / `recentEvals`；`promote` 谓词带 `AND demoted_at IS NULL`（只降不升的第一道守卫） |
| `storage/TurnCriteriaRepository.java` | 判据取数：读 `memory_turns`、回写 `corrected` / `is_holdout`；`loadTurns(taskTypeFilter)` 支持按任务类型过滤 |
| `storage/CardRepository.java` | 卡片取数：`insert` / `refresh`（只对 OPEN，保留 `created_at`）/ `reissue`（只对 ANSWERED，重置 `created_at`）/ `answer` / `find` / `list` / `count` / `suppressedClusters` / `rulesWithEvals`；`payload` ↔ JSONB。**已记下的取舍**：`reissue` 重置 `created_at` 使 §4.10.6「某周出卡数」算不准，要精确统计需另开只增不改的流水表，本版不建 |
| `skills/SkillDiscoverer.java` | 技能提炼器（**按 Agent × 任务类型**分类 → 技能发现）：**先按 Agent 切开**，再在这一刀之内遍历 8 类（跳过 `other`）→ 过样本门槛（`min-turns`）→ `classTurnTexts(agent,type)` 取该类对话 → `renderDigest` 拼摘要（按 turn 均匀抽样、首尾必取；**前缀与换行也计入 `digest-max-chars`，预算是真兑现的**）→ **算证据指纹 `sha256(agent + "\n" + task_type + "\n" + digest)`，走两道跳过闸门：① 库里已有提议且指纹相同 → 整类跳过（原行为）；② 上轮记账为 `QUIET` 且指纹相同 → 整类跳过（断点续跑新增）——都没变就不送 LLM、也不打扰人**（`FAILED` 必重试、`OFFERED` 归提议闸门管，见 `memory_skill_class_runs`） → `LlmChatClient`（用量经 sink 记入 `LlmUsageMeter`）→ 解析 → `store` + 逐类 `recordClassRun` 记账。**未指定 Agent 即如实停用**（提炼以「Agent × 任务类型」为单位）。`board(agent,session)` 返回 Agent 清单（始终全量）+ 所选范围内的分类清单与提议，未选 Agent 时不给类清单。system prompt 沿用「铁律一：技能必须是完整闭环，不是零散碎片」的严格口径，**宁可少而精、宁可延后不硬凑**；LLM 不可用即整轮停用、如实报 `enabled=false`。**两道产物机器校验**（登记口 A33，仿 `drafter/LlmRuleDrafter` 的越界机器校验 B20.4 同构做法）：`parse()` 除 JSON 必需字段外另加 ① **产物中立红灯扫描** `runtimeRedLight`——名称 / 描述 / 提示词命中单一 runtime 绑定（绝对路径 `/Users/`、家目录点目录、安装命令 `/plugin install`、`mcp__claude`、`PostToolUse`）即不产出；② **三维 rubric** `rubricViolation`——缺「禁止 / 不得 / 反例」清单、或含「酌情 / 视情况」等软化措辞即不产出。两者命中都 `Drawn(null, 真因)`、**不静默丢弃**（保守词表、仅单测验证、未实机调，见登记口 B25.8）。**失败逐类落日志、且区分成因**（超时 / 解析不出 JSON / 缺必需字段 / 判定无新增），四支都记 `agent` 与 `task_type`，超时那支另记 `turns` 与 `digest` 字数——不留「只能靠计数差反推」的盲区（见登记口 B14.1 / B14.4）。**另有一张进程内进度表 `progress`（按 Agent 分槽），一张表兼三件事**：① **互斥锁**——同一 Agent 已在跑时，同步与异步都不新起一轮（`acquire` 用 `ConcurrentHashMap.compute` 原子占位）；② **进度面**——`totalClasses`（过门槛后的真实类数，分母一开始就稳定）/ `doneClasses` / `currentClass` / `elapsedMs`；③ **取消标志**——主循环在每个类开始前查 `cancelRequested`，为真即停、此后不再发 LLM（**类间真停**）。跑完 `running=false` 但**条目保留**（供 `status` 读 `lastReport`，页面刷新能恢复）；每类「为何没提炼」也写在这张表的 `reasons` 里，经 `board()` 透出为 `ClassStat.reason`。**并发提炼**：`parallelism`（默认 1 = 逐类串行、行为与从前完全一致）；>1 时用有界线程池按类并发，计数走 `AtomicInteger`（类与类互不依赖，调大只缩短墙钟时间、不改任何判据；注意模型侧并发压力）。**前提是单实例部署**（多实例须换分布式锁）——见登记口 B16.1。**运行态落库**（P1-3，登记口 A23）：`acquire` 成功后经 `SkillRunRepository` 旁路 `startRun`（写 RUNNING）、设 total 后 `setTotals`、每类 `finally` 内 `bumpDone`（`done_classes` 原子自增）、收尾写终态（**CANCELLED〔人取消〕/ HALTED〔预算或熔断系统自停〕/ INTERRUPTED / DONE**，见 A24）；`status()` 读最近一条为 `lastRun`。全程 `runRepo==null` 早退 + try/catch WARN——**写库失败不掀翻提炼**。**单轮预算与熔断**（P2，登记口 A24）：`mo.exoskeleton.skills.budget-ms` / `budget-tokens` / `breaker-max-failures`（**默认全 0 = 不启用，行为与从前完全一致**），三者在**类间**判（`checkStop(p)`，与取消同规矩——调用发出后无法中途打断）：时长到顶 → `budget-time`、token 到顶（`Progress.tokens` 由 `draw()` 的 sink 回调累加，**不动全局 `LlmUsageMeter`**）→ `budget-tokens`、连续失败达阈值 → `breaker`。`Progress.consecutiveFailures` 按每类结果记账（`OFFERED`/`QUIET` 清零、`FAILED` +1、`SKIPPED`/`EMPTY` **不变**——没发调用、无结论）。停下时报告面以 `stopReason` **如实报因**（`cancel` / `budget-time` / `budget-tokens` / `breaker`），未跑的类在 `reasons` 里写明「尚未处理」。**2026-10-12 类级拆分**：原 1477 行按职责抽两个无状态、不读配置的纯静态协作类（见下两行），本类收敛为**编排门面**（1092 行）——@Service、两构造器（5 参 `@Autowired` + 3 参 protected 供单测替身）、9 个 `@Value` 配置字段、`progress`/`executor` 共享状态、`runOnce`/`startAsync`/`status`/`cancel`/`board`/`draw`/`discover`/`processClass`/`reasonFor` 及命名 helper 全留本类；`@Value` 字段**必须留在门面**（单测用 `ReflectionTestUtils.setField` 在门面实例反射设值）。门面 `import static` 两类，调用点文字逐字不变（`draw()` 仍返回 `Drawn`、仍调 `parse`/`SYSTEM`） |
| `skills/SkillContentParser.java` | 技能内容**解析 + 产物质量校验**（纯静态协作类，2026-10-12 从 `SkillDiscoverer` 拆出）：`SYSTEM` 提示词文本块（「铁律一：完整闭环」）、`TOOLS_VOCAB` 工具词表、红灯/rubric 三条 `Pattern`；`parse`（JSON 必需字段 + `runtimeRedLight` 单一 runtime 绑定扫描 + `rubricViolation` 三维措辞校验）、`abbrev`/`skillId`/`tools`/`textList`/`llmReasoning`。无实例状态、不读配置，故可独立于 `@Value` 注入单测 |
| `skills/SkillBoardFormatter.java` | 看板 / 心路**格式化**（纯静态协作类，2026-10-12 从 `SkillDiscoverer` 拆出）：`labelOf`/`taskTypeOptions`/`extractable`/`duplicatesOf`/`contentHash`/`mergeReasoning`/`logFacts`/`monthDay`/`cappedSessions`/`sampleQuotes`/`clip`/`nullToEmpty` + 三个证据截断常量（`MAX_EVIDENCE_*`）。与 `SkillContentParser` 互不依赖，门面同时静态导入两者 |
| `skills/SkillRunRepository.java` | 提炼**运行态**落库（P1-3，登记口 A23）：`memory_skill_runs` 的读写。`@PostConstruct init()` 里把遗留 `RUNNING` 一律标 `INTERRUPTED`（**单实例前提**：内存 `Progress` 全丢 → 遗留 RUNNING 必为中断；多实例须换租约锁，本项不做，登记口 B22.1）；`start / setTotals / setCurrent / bumpDone（SET done_classes = done_classes + 1，禁读改写）/ finish`；`lastRun(agent)` 按 `started_at DESC` 取最近一条，空回 `null`（不造值）。它是「一次运行的生命周期」，与覆盖式的 `memory_skill_class_runs` 粒度不同，故另起一表 |
| `skills/SkillLoadRepository.java` | 装载回流的**读侧**（P0-1，登记口 A23）：只读 `bySkill()` 出报表（`skill_id` / `load_count` / `last_loaded_at`），喂给 `SkillBoard.loads`。**写侧在 mo-server**（`exoskeleton/SkillLoadRepository`，装配时直写）——外骨骼只读不写，因为装配发生在工作台 |
| `skills/SkillProposalRepository.java` | 技能提议取数：`agents()`（Agent 清单，始终全量，含该 Agent 的 pending 提议数）、`sessions(agent)`（Session 下拉的选项面）、`classDistribution(agent,session)`（分类清单，`COALESCE(task_type,'__unclassified__')`——**没量到单列，不混进任何类**；按 agent 过滤、session 可选收窄）、`classTurnTexts(agent,type)`（**P0-2 加三参重载** `(agent,type,sampleCap)`：`sampleCap<=0` 时委托两参版、SQL 逐字一致；`>0` 时在 **SQL 层**用窗口函数均匀抽样，供病态大类兜底——默认 0 不抽样，`mo.exoskeleton.skills.sample-turns` 控制，登记口 B21.5）、各查询另有 `int limit` 重载（`limit<=0` 走原 SQL、无 LIMIT；为后续虚拟滚动留口）、`listByAgent()`、`store()`（三条「少打扰」规则：同 hash 不重写、已决状态不回退、只落 PENDING）、`mark()`（只改状态、不碰内容）。**断点续跑记账**：`ClassRun` record + `findClassRun(agent,type)`（无行返回 null）+ `recordClassRun(...)`（`INSERT ... ON CONFLICT (agent_id, task_type) DO UPDATE`）。**效果验证取数**：`classContrastTurns(agent,type)` 取该类全部 turn（含 `sessionId` / `source` / `startedAt` / `corrected` / `outcome`），供服务层按会话分「前 / 后」两组 |
| `skills/SkillEffectGate.java` | **技能效果验证闸门**（纯判决）：三项判据全部「越低越好」——准·隐式纠正率、失败率（绝对容忍）、回合数（相对容忍）。三态：两组样本至少一组不足 → `UNKNOWN`、任一项越过容忍上限 → `FAIL`、三项都不劣化 → `PASS`。**判「不劣化即通过」而非「必须改善」**——技能是人点头才启用的，闸门要答的是「用了之后有没有变差」而不是「够不够格上线」（与规则线 `promote` 的「四项同时改善」口径有意不同）。`tripwires` 只把「形状很怪」的数字喊给人看（监控，不是拦截）。**不碰数据库、无随机、无时间依赖**，可脱离 Spring 单测；参数走 `mo.exoskeleton.skills.gate.*` |
| `skills/SkillEffectService.java` | 效果验证编排：取数 → **按会话分桶** → 交闸门判决。以采纳时刻 `decidedAt` 为界，一个会话按**首 turn** 归到「界前 / 界后」，该会话的 turn 全部跟着走（按会话而非单 turn 归组，「回合数」这项才成立）。**只采信 `workbench` 路径**（与判据线同纪律，导入路径不可采信，见 B5）。未采纳（无 `decidedAt`）如实判不可测。**只出结论、不改状态**（撤回采纳由人拍板） |
| `skills/EvidenceDigest.java` | **技能 / 事实两条链共用的摘要与指纹工具**（包级静态）：`render(turns, maxChars)` 把一批对话按 turn 均匀抽样拼成摘要（首尾必取；**前缀与换行也计入预算**，见 B14.4 教训）、`sha256(text)` 算证据指纹。抽出来是为了消除「技能链扣前缀、分类链没扣」的两套预算口径（B15.2） |
| `skills/FactDiscoverer.java` | **事实提炼器**（每 Agent 一份）：技能问「这类任务怎么做」（手艺，粒度 = Agent × 任务类型），事实答「这个项目长什么样」（稳定知识，粒度 = 每 Agent 一份）——两者复用同一张提议表与同一条「采纳 → 落盘」链，**零 schema 变更**。**一轮同步、单次 LLM 调用**（无进度面、无互斥，与技能链的逐类异步链刻意不同）→ `repo.agentTurnTexts(agentId)` 取该 Agent 全部对话 → 过样本门槛 → `EvidenceDigest.render` → 指纹 `sha256(agent + "\n" + __fact__ + "\n" + digest)` → **两道跳过闸门同技能链**（① 库里已有提议且指纹相同、② 上轮 `QUIET` 且指纹同）→ `LlmChatClient`（用量经 sink 记 `fact` 能力）→ 解析 → `store` + `remember`。**哨兵 `task_type = "__fact__"`、`task_label = "项目结构约定"`，`proposalId = agent:<id>:task:__fact__`**，tools 恒空——采纳后由 mo-server 把事实写成「事实片段」YAML 进全局技能库，Agent 下次会话即带上（§6.3）。**容忍模型把 `body` 回成字符串数组**（`textOrJoin` 把数组按换行拼成一段，见 B18）；解析失败 / 不合格时把模型原输出记进日志（诊断能力，B14.1 同源）。未指定 Agent 即如实停用 |
| `tasks/TaskType.java` | 任务类型封闭 8 值词表 + 中文标签 + 判据；`disambiguation()` 给三对易混（test_verify↔explain / fix_bug↔doc_write / config_env↔add_feature）的取舍反例；`fromOrOther` 脏值落 `other` 不抛异常；**`isValidValue(String)`**（P1-4，登记口 A23）判一个外部传入值是否在词表内（匹配 value / name / label 任一，`"other"` 本身合法）——供单会话纠正**校验、未命中回 400 不回落 `other`**。**`TAXONOMY_VERSION`（当前 `taxonomy-v1`）**：改 prompt 或动枚举取值时**必须 +1**，否则新旧分类混在同一列无法区分——写回时随 `task_type` 一并落 `memory_turns.task_type_version` |
| `tasks/LlmChatClient.java` | 零依赖 OpenAI 兼容 HTTP 客户端（**不引 agentscope**）：`temperature=0`，正文空时回落 `reasoning_content`；请求体可选带 `reasoning_effort`（`none` 关掉思考模型的长推理链，见参数节与登记口 B6.7）；未配置时 `isEnabled()=false`（fail-open）。**带重试与退避**（`send` → `sendWithRetry` → `sendOnce`）：**只对瞬时失败重试**（HTTP 5xx / 超时 / IO），**4xx 不重试**（重试也还是错）；退避基准 `retry-backoff-ms`、逐次 ×3，最多 `max-retries` 次。**解析刻意在重试循环之外**——响应体格式不对属非瞬时，重试几次结果一样，不该白等退避。**用量回执**：`chat(system,user,sink)` 重载在成功 / 失败后分别调 `sink.accept(true, usage)` / `sink.accept(false, null)` 并 rethrow；响应体 `usage` 缺失或 null → 如实传 `null`（**端未上报 ≠ 端报了 0，不造 0**） |
| `tasks/LlmUsage.java` + `LlmUsageSink.java` + `LlmUsageMeter.java` | LLM 用量计量（**成本可观测**）：`LlmUsage(promptTokens, completionTokens, totalTokens)` 是一次调用的用量；`LlmUsageSink` 是回执接口 `accept(boolean ok, LlmUsage usage)`（`usage=null` = 端未上报）；`LlmUsageMeter`（`@Component`）按「**Agent × 能力 × 任务类型**」记账——能力名 `task_type`（分类）/ `skill`（技能提炼）/ `fact`（事实提炼）；**失败也计一次调用但 token 记 0**（把「调了几次」与「烧了多少」分开算）。进程内内存态、重启归零；`rows()` 按 totalTokens 降序 |
| `tasks/TaskTypeRepository.java` | 按 session 取待分类素材（`array_agg(user_text)`，保留 turn 边界；`force` 时去 `HAVING task_type IS NULL`）与回写 `task_type` / `task_note` / `task_type_version` / `classified_at`；`clearTaskType` 供 force 重判前清空旧标签（**含版本与时间**）；`countStaleVersion(version)` 数出「还是旧口径」的 turn（`task_type` 有值但版本为空或 ≠ 当前版本），是改判据后「待重跑盘子」的读数 |
| `tasks/TaskClassifier.java` | 分类编排：摘要超限时**按 turn 均匀抽样**（隔 k 条取一条、首尾必取；turn 少时每条都露面、配额变小）；JSON 容错解析；**解析失败不猜、保持 NULL 下轮重试**；单条失败不影响整批；`force` 时先清空候选集旧标签。**`AtomicBoolean` 运行级互斥**（已有分类在跑时如实跳过、不重复发 LLM）；**失败留痕**——每条失败记「session 摘要 + 最后一次异常（≤200 字）」进报告 `failures`（此前只有 `log.warn`、报告面看不到原因）。**收尾回报本轮新分类涉及的 Agent 集合**（供技能提炼按 Agent 逐个触发）。**用量记账**：每次 `chat` 传 sink，按「Agent × `task_type` × （空任务类型）」记入 `LlmUsageMeter`。**遗留**：本类同构的 `renderDigest` 仍未扣前缀开销（约 3% 越界，见登记口 B15.2） |
| `model/` | `MemoryRule`（含 `demotedAt` / `hardenedAt` 生命周期印记）、`RulesReport`、`ClusterProfile`、`CriteriaReport`、`DecisionCard`（`subjects` 列表版）、`CardTemplate`（T1–T6）、`CardReport`（卡片清单/出卡/作答报告）、`TaskClassifyReport`（含本轮新分类的 `agents`、旧口径存量 `staleTurns`、失败明细 `failures`）、`SkillProposal`（含 `agentId` + PENDING 常量 + `isPending()`）/ `SkillBoard`（`AgentStat` / `SessionStat` / `ClassStat` 含 `reason` / **`LoadStat`（装载回流，P0-1）** / **`TaskTypeOption`（全词表，供单会话纠正下拉，P1-4）**）/ `SkillDiscoverReport`（含 `agentId` / **`stopReason`**——停下原因 `cancel` / `budget-time` / `budget-tokens` / `breaker` 或 `null`＝跑到底，P2 由 `boolean cancelled` 升级而来，登记口 A24；另有四个 `STOP_*` 常量）/ `SkillDiscoverStatus`（含 `tokensUsed`〔本轮累计 token，P2〕+ **`RunStat lastRun`**——最近一次运行态，P1-3，`status ∈ RUNNING / DONE / CANCELLED / HALTED / INTERRUPTED`；`running=false && lastRun.status==='INTERRUPTED'` 即「上轮被中断」）/ `SkillDiscoverAccepted` / `SkillDiscoverCancel`（异步发起、进度、取消三条人面回执）、`SkillEffectReport`（效果验证：`Bucket` 两组读数 + `Item` 三项明细 + 结论 + `tripwires`）、`GateVector` / `PromotionDecision`（晋升闸门）、`RuleAuditReport`（审计报告） |

### 现状（重要）

| 项 | 状态 |
|---|---|
| E1 schema | 归模块自持：建 `memory_rules`，并 `ALTER` 为 `memory_turns` 增 `is_holdout` 列 |
| 规则来源（`RuleDrafter`） | **已落地**（2026-10-06）：确定性替身 + 手动端点；B4 切换后实测写入 7 条 SHADOW（对应 7 个达标桶）。**真 LLM 撰写器已接**（2026-10-09）：`LlmRuleDrafter` + 装配开关 `mo.exoskeleton.drafter.mode=template\|llm`（默认仍走替身，零密钥、可复现）；实机 `mode=llm` 产出一句真 LLM 记忆策略并落 SHADOW、复跑幂等，LLM 未配置时 fail-open 不写库。**契约升级为 `Draft`（草稿 + 真因）、B20.1 已修**（2026-10-09）：报告面不再笼统；并补**起草链端到端集成测试** `RuleDraftChainIntegrationTest`（真编排 + 真撰写器，只桩 LLM 出站与两条 SQL）。详见登记口 A20 / A21 / B20 |
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
| 按 Agent × 任务类型分类 → 技能发现（观测 · 记忆提炼） | **已落地**（2026-10-08）：范式从「让人逐张答专业题」改为「记忆提炼自己学、自己调，只报结果」——`skills/SkillDiscoverer`（**先按 Agent 切开**，再在刀内按 `task_type` 聚对话 → 严格口径 LLM → 落提议）+ `SkillProposalRepository` + `SkillProposalController` + `memory_skill_proposals` 表；`classify` 收尾对「本轮新分类的 Agent」逐个接提炼；采纳落全局技能由 mo-server 的 `ExoskeletonSkillController` 编排（§6.3），`skillId` 带 Agent 前缀互不覆盖。**原「决策」页（§4.10 卡面）已从人面撤下**——后端与 39 单测原样保留、无入口。**人面叫法（2026-10-08）**：导航项从「治理 · Governance → 外骨骼」上移到「观测 · Observability → **记忆提炼**」（该组原只此一项，`治理 · Governance` 分组随之撤掉）；页标题 / 文案一并改为「记忆提炼」，**实现上仍叫 mo-exoskeleton**。人面要点：**Agent / Session 两级筛选**（未选 Agent 不出类清单、不设刷新按钮）+ 分类清单（每类 sessions/turns）+ **技能卡把详情摊开**（能干什么 / 完整流程默认展开 / 工具 / 来源）+ 重新提炼（针对所选 Agent）。**P0 稳定性四件套随后落地**（2026-10-08，见登记口 A16 / B16）：① 分类口径版本列（`task_type_version` / `classified_at`，报告报 `staleTurns`）；② 提炼与分类**运行级互斥**（同一 Agent 已在跑即如实拒绝，不重复发 LLM）；③ `LlmChatClient` **重试与退避**（仅瞬时失败）；④ 人面「重新提炼」改**异步 + 轮询 + 取消**（新增 `POST /skills/discover/async`、`GET /skills/discover/status`、`POST /skills/discover/cancel`，取消为**类间真停**），并把每类 `reason` 透出到类清单。实测 **51/51 单测**、浏览器点通「重新提炼」、取消停在第 1 类后（第 2 类未发 LLM）。详见登记口 A15 / B15（口径变更前见 A14 / B14） |
| 记忆提炼 P1（效果验证 / 断点续跑 / 用量计量） | **已落地**（2026-10-08）：① **技能效果验证闸门**——`skills/SkillEffectGate`（纯判决：三项「越低越好」、判「不劣化即通过」，三态 PASS/FAIL/UNKNOWN）+ `skills/SkillEffectService`（取数 → 按会话以采纳时刻为界分「前 / 后」两组 → 交闸门）+ `GET /skills/{id}/effect` + 前端结论展示；**只出结论、不改能力**（撤回由人拍板）。② **断点续跑与并发**——新表 `memory_skill_class_runs` 逐类记 `QUIET / FAILED / OFFERED`，**只跳过 `QUIET`**（`FAILED` 必重试、`OFFERED` 归提议闸门）；`parallelism`（默认 1）支持按类并发。实测：`sanmou-agent` 第 2 轮 `skipped=1, classes=0`、`MiaoCang-Service` 第 2 轮 `skipped=2`，两次 LLM 调用数不再增加；记账行 `add_feature/OFFERED/41`、`doc_write/QUIET/71`、`test_verify/OFFERED/217` 写入正确。③ **成本 / token 计量**——`tasks/LlmUsage(UsageSink/Meter)`，按「Agent × 能力 × 任务类型」记账，`GET /skills/usage` + 前端「LLM 用量」面板。实测真实 Ollama 用量：`sanmou-agent/add_feature` 1500 token、`MiaoCang-Service/test_verify` 7871 token（**按类记账的价值**：摘要越大烧得越多）。**测试基线 72/72**（61 基线 + 11 新增），浏览器复验 7/7。详见登记口 A17 / B17（遗留：B16.5 互斥粒度为 Agent 无并发上限、B17 无逐 turn 技能装载埋点） |
| 记忆提炼 P0+P1（装载回流 / 看板性能 / 运行态可恢复 / 单会话纠正） | **已落地**（2026-10-09，登记口 A23）：起因是五维评审「机制完整，但『是否达到提炼目标』无法被验证」。① **装载回流（P0-1）**——新表 `memory_skill_loads`，`AgentFactory` 装配成功后把动态 yaml 技能 upsert（`load_count` 原子 +1），**表建在外骨骼 schema、mo-server 直写、外骨骼出报表**（沿用 `memory_turns` 先例）；口径就叫「装载」不叫「调用」，**不伪造调用归因**。② **看板性能（P0-2）**——加索引 `idx_mt_agent_task`、`classTurnTexts` 三参抽样重载、各查询 `limit` 重载；**索引与分页无条件收益、SQL 抽样默认 0**（会改证据集 → 触发一轮重提炼）。③ **运行态可恢复（P1-3）**——新表 `memory_skill_runs` 落「一次运行的生命周期」，启动 `@PostConstruct` 把遗留 `RUNNING` 标 `INTERRUPTED`，页面如实显示「上轮被中断，可重跑」（互斥仍进程内、单实例前提）。④ **单会话纠正（P1-4）**——`POST /api/v1/exoskeleton/sessions/{sessionId}/task-type`，校验未命中 → 400 不回落 `other`、`writeBack` 整 session 覆盖，**下游重提炼由人手动触发**。实机全验（含「插假 RUNNING → 重启 → 自动标 INTERRUPTED」）**150/150 外骨骼单测 + 15/15 mo-server 单测全绿**，自然态已恢复。详见登记口 A23 / B21 / B22 |
| 记忆提炼 P2（单轮预算 / 熔断） | **已落地**（2026-10-09，登记口 A24）：给主循环加三道**类间**刹车——`budget-ms`（总墙钟时长）/ `budget-tokens`（总 token）/ `breaker-max-failures`（LLM 连续失败熔断），**默认全 0 = 不启用、行为与从前完全一致**。token 由 `draw()` 的 sink 回调累加到 `Progress.tokens`（不动全局 `LlmUsageMeter`）；`Progress.consecutiveFailures` 只认 `OFFERED`/`QUIET` 清零、`FAILED` +1，跳过类不改变（没发调用、无结论）。**停下如实报因**：`SkillDiscoverReport.cancelled` 升级为 `stopReason`（`cancel` / `budget-time` / `budget-tokens` / `breaker`），运行态加**第五终态 `HALTED`**（系统自停 ≠ 人取消）；`SkillDiscoverStatus.tokensUsed` + 前端进度条「已用 {n} token」。实机三场景全验（熔断 / token 预算 / 时长预算各得 `HALTED` 证据）、自然态已恢复。**154/154 外骨骼单测全绿**（原 150 + 4）。详见登记口 A24 / B23 |

### 参数（`mo.exoskeleton.*`）

> **注意同名不同库**：本节的 `mo.exoskeleton.*` 是 **mo-exoskeleton 自己**的参数（它的 `application.yml`）。**mo-server** 侧另有一个同前缀的 `mo.exoskeleton.base-url`（转发层基址，见 §6.3），由 `MO_EXOSKELETON_BASE_URL` 注入——compose 里默认是服务名 `http://mo-exoskeleton:8081`，本机开发时才是 `http://localhost:8081`。两者互不影响，改哪个要看改的是「外骨骼怎么判」还是「mo-server 往哪儿转发」。

`min-support`（30）、`churn-weeks`（4）、`correction-window-minutes`（10）、`correction-similarity`（0.75）、`holdout-ratio`（0.2）。

`mo.exoskeleton.drafter.mode`（§4.5 规则撰写器由谁写，**只写不生效**）：`template`（默认，或未配 = 走它）＝ 确定性替身 `TemplateRuleDrafter`（按簇键查表，零密钥、可复现）；`llm` ＝ 真 LLM 撰写器 `LlmRuleDrafter`（复用 `mo.exoskeleton.tasks.llm.*` 通道）。二者由 `@ConditionalOnProperty` **二选一**，注入点（`RuleDraftService`）永远只有一个 `RuleDrafter` bean；默认替身，是因为闭环机制成不成立与撰写器是不是 LLM 无关（见登记口 A20）。该开关**已进 compose 透传**（`docker-compose.yml` 的 `mo-exoskeleton` 服务 `MO_DRAFTER_MODE: ${MO_DRAFTER_MODE:-template}`）：不设即替身，故「默认替身」这条纪律不因透传而改变；要在容器内跑真撰写器，在 `.env` 里设 `MO_DRAFTER_MODE=llm` 即可（登记口 B20.5）。

`mo.exoskeleton.gate.*`（晋升闸门 §4.11，起点值对齐 lab05 `gate.py`）：`min-effect`（0.05，四项每项至少要改善的绝对百分点）、`churn-tol`（0.0，churn 只要求不上升）、`constraint-tol-token`（0.10，单位成本**相对**容忍；§2.6 的 20% 是「退回影子」阈值，这里 10% 是「不许上线」阈值）、`constraint-tol-fail`（0.0，失败率**绝对**容忍，不许上升）。**注意成本与失败率的容忍算法不同**——前者按比例（`before×(1+tol)`）、后者按绝对值（`before+tol`），这点有测试守着。

`mo.exoskeleton.audit.*`（规则审计 §2.6 / §2.7）：`enabled`（**false**，默认关——没配就不跑，§4.7「不能默认给生产加负载」；手动触发走 `POST /rules/audit`）、`initial-delay-minutes`（1）、`interval-minutes`（1440，一个「评估周期」= 两次相邻审计，默认一天）、`hit-share-max`（0.20，限制一 命中面上限）、`constraint-tol`（0.20，限制二 命中组约束项相对对照组的容忍，**大于**闸门的 `constraint-tol-token` 10%——退回影子门槛比不许上线门槛更宽松）、`churn-high`（3，观察窗 `churn-weeks` 内版本数达到它即撤回）。

`mo.exoskeleton.cards.*`（决策卡片 §4.10）：`cap`（5，§4.10.5 排序截断的**单轮上限**——超出的进 `queued` 排队；§4.10.6 把「卡片数」当**反向指标**，上限是给「人的负担」定的天花板）、`expired-visible`（false，清单默认隐去「已到期未答」的卡；到期只是**不再展示**、不改变卡片状态，不替你作决定）。

`mo.exoskeleton.tasks.*`（二级筛选）：`enabled`（true）、`max-sessions-per-run`（200）、`digest-max-chars`（30000：全库会话全文均能整段送入、不再抽样；超限才按 turn 均匀抽样。2026-10-07 由 4000 上调，见 B6.11）、`timeout-seconds`（180，本机 qwen3 是思考模型，长会话易超时）、`llm.base-url`（`LLM_BASE_URL`，空则分类器停用）、`llm.model`（`LLM_MODEL`）、`llm.api-key`（`LLM_API_KEY`，本机 Ollama 可空）、`llm.reasoning-effort`（`LLM_REASONING_EFFORT`，默认 `none` = 关掉思考链；Ollama 的 OpenAI 兼容端点**只认这个字段**，往 prompt 里塞 `/no_think` 无效——见登记口 B6.7）、`llm.max-retries`（2，**仅瞬时失败**重试次数；4xx 不重试）、`llm.retry-backoff-ms`（500，退避基准，逐次 ×3）。**注意 base-url 要按跑法区分**：跑在 compose 里（默认）用 `http://host.docker.internal:11434/v1`（在 `.env` 设 `LLM_BASE_URL`，compose 透传）；本机 `mvn spring-boot:run` 时才用 `http://localhost:11434/v1`（`.env` 里的 `host.docker.internal` 在宿主机不解析）。

`mo.exoskeleton.skills.*`（技能发现）：`enabled`（true）、`min-turns`（30，样本门槛——该类 turns 不够就不提炼，与 `min-support` 同口径）、`max-classes-per-run`（8，单轮最多跑几类）、`digest-max-chars`（20000，该类对话摘要的上限，超限按 turn 均匀抽样；**这个上限是真兑现的**——每条前面的 `[3/12] ` 前缀与分隔换行也计入预算，见登记口 B14.4）、`sample-turns`（0，取原文时在 **SQL 层**的抽样上限——**默认 0 = 不抽样、取全量，行为与从前完全一致**；调大只是「少读一点、跑快一点」，但会**改变证据集**（进而改指纹、可能触发一轮重提炼），故默认关着，只在看板卡顿且能接受重提炼时才设正数，对 ≤ 该值的类零影响、只兜住病态大类，见登记口 B21.5）、`parallelism`（1，提炼并发度——默认 1 = 逐类串行、行为与从前完全一致；调大只缩短墙钟时间、不改任何判据，注意模型侧并发压力）、`budget-ms`（0，**单轮总墙钟时长预算**——本轮从开始累计耗时达此毫秒数即在**类间**停下，`stopReason=budget-time`、运行态记 `HALTED`；**默认 0 = 不启用**。注意它含取数开销、非净 LLM 耗时，见登记口 B23.2）、`budget-tokens`（0，**单轮总 token 预算**——本轮累计 token 达此数即在**类间**停下，`stopReason=budget-tokens`、`HALTED`；**默认 0 = 不启用**）、`breaker-max-failures`（0，**熔断阈值**——LLM 连续失败达此数即在**类间**停发后续调用，`stopReason=breaker`、`HALTED`；成功一次（`OFFERED`/`QUIET`）即清零、跳过类不计——**默认 0 = 不启用**）。三项预算/熔断均见登记口 **A24**，遗留见 **B23**。**LLM 连接复用 `mo.exoskeleton.tasks.llm.*`**，不另设一套。

`mo.exoskeleton.skills.gate.*`（效果验证闸门）：`min-sample`（30，每组前 / 后的最小样本量，低于它判「不可测」；与 `min-turns` / `min-support` 同一精神）、`rate-tolerance`（0.05，比率类判据的**绝对**容忍——采纳后高 5 个百分点以内仍算持平）、`rounds-tolerance`（0.10，回合数的**相对**容忍——采纳后多 10% 以内仍算持平）。**注意两类容忍算法不同**：比率按绝对值（`before+tol`）、回合数按比例（`before×(1+tol)`），这点有测试守着。

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
├── mo_ingest/                      # 采集：统一接入层（纯 stdlib，零依赖）
│   ├── contract.py                 # 标准化契约唯一真相（io_kind / operation / trace_id / MemoryEvent）
│   ├── otlp.py                     # OTLP 上报（hook + log 共用）
│   ├── registry.py                 # 平台注册表（qoder / trae / workbuddy）
│   ├── hook/base.py · hook/qoder.py · hook/trae.py    # hook 方案：stdin JSON → 事件（实时旁路）
│   ├── log/base.py · log/trae.py · log/workbuddy.py  # log 方案：日志目录 → turn → 批量上报
│   └── cli.py · __main__.py        # python -m mo_ingest --platform … --mode …
│
├── examples/                       # 示例（接入层已迁至 mo_ingest/）
│   ├── QODER-HOOK-SETUP.md         # Qoder Hook 一键接入文档
│   ├── TRAE-HOOK-SETUP.md          # Trae Hook 一键接入文档
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
│       │   │   ├── security/       # ApiKeyAuthConfig（鉴权 filter 实现在 mo-support）
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
│       │   ├── api/                # RuleController · CriteriaController · TaskTypeController · PromotionController · RuleLifecycleController · CardController · SkillProposalController · SessionTaskTypeController
│       │   ├── drafter/            # RuleDrafter · TemplateRuleDrafter · LlmRuleDrafter · RuleDraftService
│       │   ├── promote/            # PromotionGate · PromotionService
│       │   ├── audit/              # RuleAuditJudge · RuleLifecycleService · RuleAuditScheduler
│       │   ├── cards/              # CardPlanner · DecisionCardService · CardActionService
│       │   ├── criteria/           # CorrectionDetector · CriteriaService
│       │   ├── skills/             # SkillDiscoverer（编排门面） · SkillContentParser · SkillBoardFormatter · FactDiscoverer · EvidenceDigest · VersionPolicy · SkillProposalRepository · SkillEffectGate · SkillEffectService · SkillRunRepository · SkillLoadRepository
│       │   ├── tasks/              # TaskType · LlmChatClient · LlmUsage · LlmUsageSink · LlmUsageMeter · TaskTypeRepository · TaskClassifier
│       │   ├── storage/            # RuleRepository · TurnCriteriaRepository · CardRepository
│       │   ├── cluster/            # ShapeBucketer · HoldoutRouter
│       │   └── model/              # MemoryRule · RulesReport · ClusterProfile · CriteriaReport · DecisionCard · CardTemplate · CardReport · TaskClassifyReport · SkillProposal · SkillVersion · SkillEvent · SkillBoard · SkillDiscoverReport · SkillDiscoverStatus · SkillDiscoverAccepted · SkillDiscoverCancel · SkillEffectReport · GateVector · PromotionDecision · RuleAuditReport
│       └── resources/
│           ├── application.yml
│           └── schema.sql          # memory_rules + memory_rule_evals + memory_draft_cache + memory_cards + memory_skill_proposals + memory_skill_versions + memory_skill_events + memory_skill_class_runs + memory_skill_loads + memory_skill_runs + 为 memory_turns 增 is_holdout / task_type / task_note / task_type_version / classified_at 列
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
