# 环境变量配置说明

对应模板：[.env.example](.env.example)（与本文件同目录）。

## 两个文件的关系

| 文件 | 作用 | 是否提交到 git |
|---|---|---|
| `.env.example` | 配置项清单（模板）：列出项目支持的环境变量与默认值，**不含任何真实密钥** | 是，随仓库提交，供使用者参考 |
| `.env` | 实际生效的配置文件，**含真实密钥** | 否，已被 `.gitignore` 忽略，永不提交 |

## 生成与生效

通常无需手动创建——首次运行 `./install.sh` 会自动生成含随机鉴权密钥的 `.env`。

也可手动生成：

```bash
cp .env.example .env
# 编辑 .env，按需填写
docker compose up -d   # 修改 .env 后需重建容器才能生效
```

## 变量说明

### `MO_API_KEY` —— 接口鉴权密钥

所有 API 请求的口令，三处使用方需保持一致：

- **浏览器访问**：`http://localhost:8080` 与 `https://localhost:5173` 本机访问已由 nginx 自动注入 `.env` 中的值，零配置；从其他设备/客户端访问时，在侧边栏「访问密钥」弹窗填入同一值
- **上报脚本 / SDK**：请求头 `Authorization: Bearer <MO_API_KEY>`
- **后端直连**（`http://localhost:4318`，OTLP/SDK 专用）：需自行携带请求头，不经 nginx 注入

置空（`MO_API_KEY=`）为开放模式，仅适合本地演示。

### `MO_DB_PASSWORD` —— 数据库口令

PostgreSQL 口令，默认 `mo`，生产环境建议改为强口令。

### `DASHSCOPE_API_KEY` —— 大模型 API Key

`.log` 大模型格式化与 Agent 对话使用。留空则该模式提示未配置，可改走 JSON 直传。

也可在工作区侧边栏「模型密钥」中配置（界面配置加密落盘，**优先于本环境变量**）。

### `MO_HOST_MOUNT_SRC` / `MO_HOST_MOUNT_DST` —— 收窄宿主机挂载

默认挂载整个家目录到容器，安全要求高时可通过这两个变量收窄到具体目录（如 `~/Documents`），详见 `docker-compose.yml` 注释。

## 语义过滤层（laya）相关

这一层默认开启，且设计目标是**零配置可用**：laya 推理后端的源码随本仓库分发
（`laya-backend/server.py`，本项目自有代码，按 **Apache-2.0** 分发——来源与名称使用说明见
`laya-backend/NOTICE`），laya 本体只作为 PyPI 依赖安装——**不依赖任何外部仓库**。

| 形态 | 谁负责准备后端 |
|---|---|
| 容器（`docker compose up -d`） | compose 里的 `laya-backend` 服务，Dockerfile 自带 `pip install`，首次自动补权重 |
| 本机（`mvn spring-boot:run`） | mo-server 自己拉起；项目内没有 venv 时**后台自举**（建 venv + 装依赖），装完自动接上 |

正常情况下不需要设置任何变量。可调项见 `application.yml` 的 `mo.semantic.*`。

| 变量 | 默认 | 说明 |
|---|---|---|
| `MO_SEMANTIC_ENABLED` | `true` | 总开关。置 `false` 则完全回到纯规则行为（不加语义过滤） |
| `MO_LAYA_BACKEND_DIR` | 自动探测 | 项目内后端目录（含 `server.py`）。留空按 `../laya-backend` → `laya-backend` 探测 |
| `MO_LAYA_AUTO_BOOTSTRAP` | `true` | 本机无 venv 时后台自举（建 venv + `pip install -r requirements.txt`），**不阻塞启动** |
| `MO_PIP_INDEX_URL` | 清华源 | 自举时 pip 的 index-url；换网络环境时改这里 |
| `LAYA_BASE_URL` | `http://127.0.0.1:8770` | 后端地址。容器内由 compose 注入 `http://laya-backend:8770`；已在运行的后端会被直接复用 |
| `HF_ENDPOINT` | `https://hf-mirror.com` | 权重下载端点。官方 `huggingface.co` 在部分网络下不可达，故默认走镜像；权重已在本地时该项不生效 |

> 旧的 `LAYA_HOME`（指向 laya 源码仓库）**已移除**——这正是「不依赖其他项目」的关键改动。

**怎么确认它真的生效**：`GET /api/v1/semantic/status` 返回
`{"enabled":true,"backendOwned":true,"backendReady":true,"backendUsable":true,"multilingualReady":true}`。
其中 `backendUsable` / `multilingualReady` 最关键——本项目内容以中文为主，它为 `false` 时语义过滤
**实际并未生效**（fail-open 会保留全部候选，看起来像「没有假阳性」）。
容器首次启动有一段「`/health` 已通但仍在补权重」的窗口，此时 `backendReady=true` 而
`backendUsable=false`，属正常，等它转 true 即可。

**手工准备本机环境**（可选，只是把自举提前，省掉首次启动的等待；幂等）：

```bash
bash scripts/setup-laya-backend.sh          # 建 venv + 装依赖
bash scripts/setup-laya-backend.sh --check  # 只体检
```

## 密钥优先级

模型调用密钥的取值顺序：**界面「模型密钥」配置（加密落盘）> `DASHSCOPE_API_KEY` 环境变量**。

## 安全约定

- 任何真实密钥只存在于 `.env`（本机）或界面配置（加密落盘 `~/.workbench/model-keys.json`），两者均不入库
- 提交前可用 `git status` 确认 `.env` 不在暂存区
