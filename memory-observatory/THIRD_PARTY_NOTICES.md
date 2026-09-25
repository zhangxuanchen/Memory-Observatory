# Third-Party Notices

Memory Observatory（本项目）基于 MIT License 发布。本项目使用了以下开源软件，
在此向各项目及其贡献者致谢。所有依赖均为宽松型许可证（MIT / Apache-2.0 / BSD / PostgreSQL
License），与本项目的 MIT 许可证兼容；本项目不包含任何 GPL/AGPL/LGPL 等传染性许可证的依赖。

> **许可边界提示**：仓库整体的 MIT 许可有**一处例外**——`laya-backend/` 目录按
> **Apache License 2.0** 分发（与 laya 生态保持一致）。两许可证均为宽松型、互相兼容。
> 详见下方「许可边界」一节。

## 许可边界（License Scope）

| 范围 | 许可证 |
|---|---|
| 仓库整体（其余全部文件） | MIT（根目录 [LICENSE](./LICENSE)） |
| `laya-backend/` 目录（含 `server.py`） | Apache License 2.0（[laya-backend/LICENSE](./laya-backend/LICENSE)、[laya-backend/NOTICE](./laya-backend/NOTICE)） |

**`laya-backend/server.py` 是本项目自有代码**，改编自同一作者在 laya 工作副本中编写的
`java/backend/server.py`（该文件从未提交到上游，上游仓库 `git ls-files` 中不存在
`java/`）。因此它**不是**第三方作品的衍生代码，不存在需要保留的第三方版权声明；
本目录选择 Apache-2.0 是为与 laya 生态一致。

**名称与商标**：laya 与 Convai Innovations 是其各自权利人的名称/商标。本仓库对
这些名称的使用**仅为指明技术依赖与来源**。本项目与 laya 官方不存在隶属、赞助或
背书关系（Apache-2.0 §6 不授予商标许可）；不得表述为「官方 SDK」「官方后端」。

## 后端 / Java（mo-server）

| 项目 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| [Spring Boot](https://spring.io/projects/spring-boot) | 3.4.1 | Apache-2.0 | Web 框架（spring-boot-starter-web / -jdbc / -test） |
| [AgentScope](https://github.com/agentscope-ai/agentscope-java) | 2.0.0 | Apache-2.0 | Agent 工作台核心：ReActAgent、DashScope / OpenAI 模型接入、harness（Copyright (c) Alibaba / AgentScope Team） |
| [Apache POI](https://poi.apache.org/) | 5.2.5 | Apache-2.0 | Excel 模板生成与 xlsx 批量导入解析 |
| [Jackson](https://github.com/FasterXML/jackson) | 随 Spring Boot 托管 | Apache-2.0 | JSON / YAML（jackson-dataformat-yaml，解析 .workbench 技能包） |
| [PostgreSQL JDBC Driver](https://jdbc.postgresql.org/) | 随 Spring Boot 托管 | BSD-2-Clause | PostgreSQL 数据库驱动 |
| [jsoup](https://jsoup.org/) | 1.22.1 | MIT | 网页抓取与 CSS 选择器解析 |
| [Lombok](https://projectlombok.org/) | 1.18.44 | MIT | 编译期样板代码生成 |

## 前端 / 浏览器（随 mo-server 静态资源分发）

| 项目 | 许可证 | 说明 |
|---|---|---|
| [Mermaid](https://github.com/mermaid-js/mermaid) | MIT | 会话执行流程图渲染（`static/mermaid.min.js`） |
| [AntV G6](https://github.com/antvis/G6) | MIT | 关系图 / 流程图画布（`static/vendor/g6.min.js`，Copyright (c) AntV） |
| [marked](https://github.com/markedjs/marked) | MIT | Markdown 渲染（`static/marked.min.js`，Copyright (c) 2011-2023 Christopher Jeffrey） |

## Python 运行时依赖（laya 语义过滤后端）

`laya-backend/requirements.txt` 声明的依赖在**构建时或首次启动时**按需安装，
**不随本仓库分发**（含下方的模型权重）。

| 包 | 许可证 | 用途 |
|---|---|---|
| [laya](https://pypi.org/project/laya/) | Apache-2.0 | 非自回归语义判定引擎（`choice` / `score` / `noul` 三种原语）。权利人 Convai Innovations。本项目调用其公开 API（`Router` / `Agent` / questions 契约） |
| [torch](https://pytorch.org/) | BSD-3-Clause | 推理运行时（容器内为 CPU 版） |
| [transformers](https://github.com/huggingface/transformers) | Apache-2.0 | 模型加载 |
| [huggingface_hub](https://github.com/huggingface/huggingface_hub) | Apache-2.0 | 权重下载与本地预检 |

**模型权重**：从 HuggingFace 仓库 [`convaiinnovations/laya`](https://huggingface.co/convaiinnovations/laya)
按需下载（约 1.5GB），**不随本仓库分发**。该仓库以 **Apache License 2.0** 授权，
模型卡标注 `commercial-use`（可商用）。

## Python（mo_sdk / examples）

Python 采集 SDK（`mo_sdk/`）、上报 CLI 与示例脚本（`examples/`、`hermes_instrumentation.py`）
**仅使用 Python 标准库**（urllib / json / hashlib / threading 等），无第三方依赖。

## 容器基础镜像

| 镜像 | 许可证 | 用途 |
|---|---|---|
| [Eclipse Temurin (OpenJDK 21)](https://adoptium.net/) | GPLv2 + Classpath Exception | mo-server 构建与运行时。Classpath Exception 明确不将许可证义务延伸至本应用 |
| [nginx](https://nginx.org/)（alpine 镜像） | BSD-2-Clause | mo-dashboard HTTPS 反向代理 |
| [PostgreSQL](https://www.postgresql.org/)（16-alpine） | PostgreSQL License（类 BSD/MIT） | 数据库 |

## 许可证全文

- MIT License: <https://opensource.org/licenses/MIT>
- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- BSD 2-Clause License: <https://opensource.org/licenses/BSD-2-Clause>
- PostgreSQL License: <https://www.postgresql.org/about/licence/>
- GPL v2 + Classpath Exception: <https://openjdk.org/legal/gplv2+ce.html>

各组件的版权头与许可证文本随其二进制产物（jar 内 `META-INF/`、`legal/`，及前端 `*.min.js`
文件头）一并分发。
