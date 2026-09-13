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

## 密钥优先级

模型调用密钥的取值顺序：**界面「模型密钥」配置（加密落盘）> `DASHSCOPE_API_KEY` 环境变量**。

## 安全约定

- 任何真实密钥只存在于 `.env`（本机）或界面配置（加密落盘 `~/.workbench/model-keys.json`），两者均不入库
- 提交前可用 `git status` 确认 `.env` 不在暂存区
