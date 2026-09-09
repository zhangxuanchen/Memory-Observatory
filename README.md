# Memory Observatory（MindSprout）

> 面向 AI Agent 记忆系统的一体化可观测性平台与智能工作台：**采集 → 存储 → 观测分析 → 编排运行** 全链路闭环。

Memory Observatory 在 Agent 运行时旁路采集记忆操作事件（读 / 写 / 更新 / 遗忘）与上下文 Token 快照，按 OpenTelemetry 协议落库，Web 端提供记忆观测、多维分析、数据导入和 Agent 工作台四大功能区；内置多 Agent 编排引擎与角色化工作流，可直接在平台内创建工作区、派发任务、实时跟踪执行过程。

---

## 功能全景

Web 界面（侧边栏四大功能区）：

| 分区 | 页面 | 能力 |
|---|---|---|
| **观测** | 总览 | Agent / 事件 / 会话 KPI 概览、问题告警聚合、待办入口 |
| | 事件详情 | 记忆事件流（操作/层级/Token/延迟）、会话 Turn 时间线、五区 Token 热力图、事件详情抽屉、Trace 链路树 |
| **分析** | Token 分析 | 分层/操作/会话/时间趋势等 8 维度聚合、延迟分位、Top10 记忆键、五区预算分布 |
| | Agent 分析 | 多 Agent 横向对比、会话对比、综合评分 |
| | Skill 分析 | Skill 调用次数 Top10、Skill Token 消耗 Top10、按 Agent 下钻 |
| | 问题分析 | 慢调用、循环震荡、频繁压缩、失败重试、遗忘风暴等异常模式自动识别与阈值判定 |
| | 流程分析 | 会话执行流程图（mermaid/G6）、关键路径、问题节点标注与详情滑块 |
| | 内容风险监测 | 记忆/对话内容风险规则扫描与命中详情 |
| **数据导入** | 数据导入 | xlsx/json 批量导入（模板下载、行级校验报告）、文件夹 `.log` 导入（支持大模型格式化抽取）、MCP/Skill 上报接入包下载 |
| **Agent · Playground** | Agent 对话 | 多子 Agent 工作区（工作区经理 + 员工），ReAct 工具链，SSE 实时气泡流 + 思考树，任务拆解并行派发、逐步验收 |
| | Skill 管理 | 工作区级 / Agent 级技能的增删改查与下载 |
| | MCP 管理 | MCP Server 配置、连通性测试、工具列表查看与在线调用 |

侧边栏底部提供 **🔑 API Key** 全局设置入口（状态点灰=未配置 / 绿=已配置），统一维护接口鉴权密钥。

---

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│  Agent 进程（Python / Java / 任意语言）                         │
│  mo_sdk 拦截器 · trae_report_event.py CLI · MCP 上报包          │
└───────────────┬──────────────────────────────────────────────┘
                │ OTLP/HTTP JSON（/v1/traces）· REST（/api/v1/events）
                ▼
┌──────────────────────────────────────────────────────────────┐
│  mo-server（Spring Boot 3.4，单进程）                           │
│  ┌────────────┐  ┌─────────────┐  ┌────────────────────────┐  │
│  │ OTLP 接收   │→│ IngestQueue │→│ EventRepository        │  │
│  │ OtlpParser │  │ 有界队列削峰 │  │ 批量写库 + 30+ 查询接口 │  │
│  └────────────┘  └─────────────┘  └───────────┬────────────┘  │
│  REST API（观测/分析查询 · 数据导入 · 风险扫描）                 │
│  Agent 工作台（workbench 多Agent编排 · workflow 角色工作流）     │
│  工作区管理（workspaces · skills · mcp · 文件浏览）             │
│  安全：ApiKeyAuthFilter（Bearer 校验）                          │
└───────────────┬───────────────────────────────┬──────────────┘
                │ JDBC                           │ /api 反代
                ▼                                ▼
┌──────────────────────────┐      ┌──────────────────────────────┐
│  PostgreSQL 16（持久卷）   │      │  mo-dashboard（nginx）         │
│  memory_events            │      │  443 TLS（自签/正式证书）       │
│  memory_snapshots         │      │  80 → 443 跳转 · 静态单页应用   │
│  import_log_files（去重）  │      │  前端统一从 mo-server static 取 │
└──────────────────────────┘      └──────────────────────────────┘
```

**四层分工**：

| 层 | 目录 | 技术 | 职责 |
|---|---|---|---|
| 采集 | `mo_sdk/` `examples/` | Python（零依赖可选） | 拦截记忆操作、归一化为 READ/WRITE/UPDATE/EXPIRE、OTLP 上报 |
| 服务 | `mo-server/` | Java 17+ / Spring Boot 3.4 | 接收、削峰、落库、查询分析、Agent 编排、工作区管理 |
| 存储 | docker volume | PostgreSQL 16 | 事件/快照/导入指纹持久化 |
| 前端 | `mo-server/src/main/resources/static/` | 原生 HTML/CSS/JS（内联 mermaid/marked/G6） | 单页应用，nginx 托管并 HTTPS 反代 |

---

## 快速开始

### 前置要求

- Docker 20.10+ 且带 docker compose v2 插件（`docker compose version` 可输出）

### 一键启动

```bash
# 本地演示（开放模式，无鉴权）
./install.sh

# 生产模式（启用 API Key 鉴权）
export MO_API_KEY=$(openssl rand -hex 24)
./install.sh
```

脚本自动完成：环境预检 → 构建镜像 → 启动 postgres / mo-server / mo-dashboard → 健康检查轮询 → 输出访问地址。三个容器均配置 `restart: unless-stopped`，Docker/开机重启后自动恢复。

启动后：

| 服务 | 地址 | 说明 |
|---|---|---|
| **Web 界面** | https://localhost:5173 | 自签名证书，浏览器提示"不安全"点继续即可 |
| REST API | http://localhost:8080 | 查询 + 工作台接口 |
| OTLP 上报 | http://localhost:4318/v1/traces | SDK / 上报脚本入口 |

启用鉴权后，浏览器首次打开会弹窗一次要求输入 API Key（侧边栏「API Key」可随时修改/清除）；SDK 与上报脚本需携带同一个 key。

### 验证一条上报

```bash
curl -s -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MO_API_KEY" \   # 开放模式可省略此行
  -d '{"agentId":"demo-agent","sessionId":"s1","operation":"WRITE",
       "layer":"prompt","memoryKey":"hello","memorySummary":"安装验证","tokenCount":10}'
```

刷新 Web 界面，在总览/事件详情选择 `demo-agent` 即可看到。

---

## 数据上报与导入

平台提供四种数据接入方式，均可在「数据导入」页操作或下载：

### 1. Python SDK（mo_sdk）

框架无关的核心库 + 拦截器，旁路采集、异常不影响主流程：

```python
from mo_sdk import MemoryObserver, OTelSpanExporter

observer = MemoryObserver(agent_id="my-agent")
observer.add_exporter(OTelSpanExporter(
    endpoint="http://localhost:4318/v1/traces",
    api_key="<MO_API_KEY>",        # 服务端启用鉴权时必填
    service_name="my-agent",
))
observer.record_event(...); observer.export()
```

属性对齐 OpenTelemetry GenAI 语义约定（`memory.*` 命名空间）。Hermes 框架可直接用 `hermes_instrumentation.py` 一键猴子补丁插桩。

### 2. 零依赖 CLI 上报

`examples/trae_report_event.py`（仅用标准库），适合 Agent 以 RunCommand 方式逐事件上报：

```bash
export MO_API_KEY=<key>            # 启用鉴权时
python3 examples/trae_report_event.py \
  --operation WRITE --layer prompt --memory-key MEMORY.md \
  --summary "读取项目记忆" --token-count 1200 --session-id my-session
# 经 nginx HTTPS 自签证书上报时加 --insecure 或 MO_INSECURE=1
```

支持 `--trace-id` / `--parent-span-id` / `--meta k=v` 链式构建 Trace。

### 3. MCP / Skill 接入包

「数据导入」页可下载针对各平台（Trae/Cursor/Cline 等）的接入包 zip，内含：`report_mcp_server.py`（stdio MCP Server，暴露 `report_memory_event` 工具）、`skill.md`（技能提示词）、MCP 配置片段与安装 README。目标 Agent 配置环境变量 `MO_SERVER` 与 `MO_API_KEY` 后，每次记忆操作自动上报。

### 4. 文件批量导入

| 方式 | 格式 | 说明 |
|---|---|---|
| 表格/JSON 导入 | `.xlsx` / `.json` | 页面下载模板填写后上传；行级校验，返回 inserted/skipped/errors 报告；`ON CONFLICT` 幂等 |
| 文件夹 `.log` 导入 | 整个目录的 `*.log` | 选文件夹批量上传，MD5 指纹去重；可勾选「大模型格式化解析」（DashScope）把自由文本运行日志抽取为结构化事件；不勾选则按 JSON 数组直传 |

`test-logs/` 目录提供了三组测试日志（正常流 / 异常循环流 / JSON 直传含坏行），可直接选该文件夹验证导入效果。

---

## 页面功能导览

- **总览**：Agent、事件、会话等核心 KPI；问题分析告警卡片（红=异常 / 绿=正常）一键跳转。
- **事件详情**：事件流分页表格，胶囊筛选组（Agent / Session / 时间窗口）；点击事件打开详情抽屉（基本信息、记忆摘要、同 Turn 事件、原始 JSON）；会话时间线按记忆层分泳道；Token 热力图按时间桶展示；Trace 视图渲染 Span 树（关键路径、慢 Span、孤儿 Span、记忆有效性统计）。
- **Token 分析**：Top 会话、24h 分布、操作×层级矩阵、延迟分位数、Top10 记忆键、读写比率、直方图、日趋势；五区快照（System/Task/Memory/Tool History/Free）预算分布。
- **Agent 分析**：多 Agent 综合对比（事件量、Token、延迟、失败率）、同 Agent 会话间横向对比。
- **Skill 分析**：Skill 调用次数 Top10 与 Token 消耗 Top10 卡片，支持全局 / 单 Agent 视角。
- **问题分析**：基于阈值自动识别慢调用、技能循环震荡、上下文压缩过频、调用失败、遗忘风暴等模式；问题节点详情含时间倒序红点锚点条，点击红点直达事件位置。
- **流程分析**：把会话重建为流程图（正常 ✅ / 异常 ⚠️ / 恢复 🔄 / 放弃 ❔ 图标标注），问题节点红色高亮，支持 mermaid 与 G6 两种渲染。
- **内容风险监测**：按规则扫描记忆与对话内容，输出风险命中列表与详情。
- **Agent 对话（工作台）**：工作区式目录（`.workbench` manifest），经理 Agent 将需求拆解为子任务并行派发给员工 Agent；SSE 流式推送气泡对话与可拖拽/收起的思考树；阶段完成后弹出评审反馈（优点/不足/结论）与产物预览，支持「重新生成」「进入下一阶段」。
- **Skill / MCP 管理**：技能与 MCP Server 的工作区级/Agent 级 CRUD；MCP 支持连通性测试、工具枚举与在线试调。

---

## 界面预览

### 观测

**总览** — Agent / 事件 / 会话 KPI 与问题告警聚合

![总览](https://github.com/user-attachments/assets/0bc7d2c7-0969-4d4b-aea0-85442a2cdc58)

**事件详情** — 记忆事件流、会话时间线、胶囊筛选与详情抽屉

![事件详情](https://github.com/user-attachments/assets/3c41b7ab-96f3-42e5-934a-16ca83650916)

![事件详情 · Trace 链路与记忆分层](https://github.com/user-attachments/assets/50821800-58a7-4f23-9ed1-ea72f89a36bc)

**记忆层级** — 五层记忆结构与 Token 分布

![记忆层级](https://github.com/user-attachments/assets/4cf0bb32-4510-4e82-8960-4ff9ab61789b)

### 分析

**Token 分析** — 多维度聚合、延迟分位、Top10 记忆键与五区预算

![Token 分析](https://github.com/user-attachments/assets/aef145f7-b04b-4a35-94cf-a6bc4dcff03c)

![Token 分析 · 五区快照与趋势](https://github.com/user-attachments/assets/fb2315af-ea3d-4230-a572-64f7c1ac4710)

**Agent 分析** — 多 Agent 横向对比与综合评分

![Agent 分析](https://github.com/user-attachments/assets/a92257ee-bf21-4869-b433-40d0837d9195)

**Skill 分析** — Skill 调用次数与 Token 消耗 Top10

![Skill 分析](https://github.com/user-attachments/assets/d5ab213b-d806-4731-b380-380d7792b4bf)

**问题分析** — 慢调用 / 循环震荡 / 频繁压缩 / 遗忘风暴自动识别

![问题分析](https://github.com/user-attachments/assets/6079f410-2910-4b5e-8176-5d5d9a90b04c)

![问题分析 · 问题节点详情与红点锚点](https://github.com/user-attachments/assets/1893e8ea-1129-40db-adb5-9ec5cfe5576d)

**流程分析** — 会话执行流程图与关键路径标注

![流程分析](https://github.com/user-attachments/assets/0f3b23ec-f957-42b8-b561-3605eca1f1bd)

![流程分析 · 问题节点高亮](https://github.com/user-attachments/assets/2af2bdb1-a232-49e3-ae76-12b8b6e6f8b6)

**内容风险监测** — 记忆/对话内容风险规则扫描与命中详情

![内容风险监测](https://github.com/user-attachments/assets/36949e7a-8bdd-4bcd-aea2-605e00ce914a)

### 数据导入

**数据导入** — xlsx/json 批量导入、文件夹 `.log` 导入与接入包下载

![数据导入](https://github.com/user-attachments/assets/e6dff735-62cd-4ac7-b4c6-0f802b70eb17)

### Agent · Playground

**Agent 工作台** — 多子 Agent 工作区、SSE 气泡流与思考树

![工作区](https://github.com/user-attachments/assets/6a7abc51-2755-487c-a558-3575f82bdf8a)

![工作区 · 任务派发与阶段验收](https://github.com/user-attachments/assets/c15d293d-6598-4ff6-bbb3-7d10bb33d0e3)

**Skill 管理** — 工作区级 / Agent 级技能维护

![Skill 管理](https://github.com/user-attachments/assets/a6bbbe86-f7ef-4f54-a94e-195588fa8d63)

---

## 配置与环境变量

| 变量 | 作用于 | 默认 | 说明 |
|---|---|---|---|
| `MO_API_KEY` | mo-server | 空 | 设置后 `/api/**`、`/v1/**` 强制 `Authorization: Bearer <key>`；空为开放模式（仅本地演示） |
| `DASHSCOPE_API_KEY` | mo-server | 空 | `.log` 导入「大模型格式化解析」所需；不配置时该模式不可用 |
| `MO_DB_URL` / `MO_DB_USER` / `MO_DB_PASSWORD` | mo-server | compose 内已设 | PostgreSQL 连接 |
| `MO_AGENT_CRYPTO_SECRET` | mo-server | 内置回退 | 工作台 Agent Key 加密密钥（AES），生产建议显式设置 |

**宿主机文件访问（工作台选目录）**：compose 默认把宿主机家目录（`$HOME`）挂载到容器内同路径，并通过 `JAVA_TOOL_OPTIONS=-Duser.home=$HOME` 让「选择工作区」文件夹浏览器默认从宿主机家目录（如 `/Users/zxc`，含 Documents/Desktop 等）开始浏览；工作台配置 `~/.workbench` 也落在宿主机、容器重建不丢。需要让 Agent 访问其他盘/目录时，在 `docker-compose.yml` 的 mo-server `volumes` 中按 `- "/宿主路径:/容器同路径"` 追加（宿主路径必须存在，否则启动报错）。

**TLS 证书**：mo-dashboard 首次启动在 `mo-certs` 卷中自动生成自签名证书（CN=localhost，10 年有效）；生产环境把正式证书挂载为该卷下的 `tls.crt` / `tls.key` 即可，无需改镜像。

> 安全建议：生产部署时安全组仅放行 443 端口，8080/4318/5432 不对公网暴露；上报流量统一经 nginx HTTPS。

---

## 目录结构

```
memory-observatory/
├── install.sh                     # 一键安装启动（预检/构建/健康检查/输出指引）
├── docker-compose.yml             # postgres + mo-server + mo-dashboard 编排
├── mo_sdk/                        # Python 采集 SDK（core/collector/exporter/interceptors）
├── examples/
│   ├── otel_demo.py               # OTLP 上报演示
│   ├── trae_report_event.py       # 零依赖事件上报 CLI
│   └── trae_importer.py           # 历史会话日志批量导入
├── test-logs/                     # .log 导入测试样本
├── hermes_instrumentation.py      # Hermes 框架一键插桩示例
├── mo-server/                     # Java 服务端
│   └── src/main/
│       ├── java/io/memobservatory/
│       │   ├── server/            # 观测域：api / receiver / ingest / storage
│       │   │                      #   model / excel / flow（流程分析）/ risk / security
│       │   └── agentloop/         # 工作台域：
│       │       ├── workbench/     #   多 Agent 编排（经理/员工、SSE 对话、思考树）
│       │       ├── workflow/      #   角色化工作流引擎（门禁验收）
│       │       └── workspace/     #   工作区/技能/MCP/文件浏览管理
│       └── resources/
│           ├── application.yml    # 服务配置
│           ├── schema.sql         # 建表脚本
│           └── static/index.html  # 前端单页应用（唯一来源，nginx 与 Spring 共用）
├── mo-dashboard/                  # nginx 镜像：Dockerfile / nginx.conf(HTTPS反代) / entrypoint.sh
└── ARCHITECTURE.md                # 分层架构、数据流、Trace 契约、数据库 Schema 详解
```

---

## 本地开发（不用 Docker）

```bash
# 1. 启动 PostgreSQL（或复用 compose 里的 postgres 容器）
# 2. 运行服务端
cd mo-server
mvn -s settings.xml spring-boot:run
#    http://localhost:8080 提供 API 与前端静态页（热更静态资源可用 scripts/mo-static-sync.sh）

# 3. 产生演示数据
python examples/otel_demo.py
```

常用命令：`docker compose logs -f`（看日志）、`docker compose down`（停止，数据保留）、`./install.sh --reset`（清库重建）。

---

## 文档

- [ARCHITECTURE.md](./ARCHITECTURE.md) — 分层架构、数据流、Trace 契约、数据库 Schema 详解
- [docs/](./docs) — 数据模型 V2 设计（五层架构 / 输入契约 / 集成计划）等
- 数据契约规范：`docs/spec/mospec.md`（MOSpec v0.1）

## License

MIT License
