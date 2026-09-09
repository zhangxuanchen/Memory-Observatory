# Third-Party Notices

Memory Observatory（本项目）基于 MIT License 发布。本项目使用了以下开源软件，
在此向各项目及其贡献者致谢。所有依赖均为宽松型许可证（MIT / Apache-2.0 / BSD / PostgreSQL
License），与本项目的 MIT 许可证兼容；本项目不包含任何 GPL/AGPL/LGPL 等传染性许可证的依赖。

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
