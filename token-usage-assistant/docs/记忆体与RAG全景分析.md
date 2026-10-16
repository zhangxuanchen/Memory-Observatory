# 记忆体与 RAG 全景分析（TUA 落地版）

> 调研时间：2026-10-14 ｜ 检索范围：公开网络（基准数据 / 框架文档 / 工程实践复盘）
> 用途：为 TUA（Token 用量助手）设计「记录用户的项目结构、使用习惯偏好、常犯错误、正确示范、可归纳的方法论 + 工作流」提供选型依据与落地方案
> 关联文档：`DESIGN.md`（既有数据模型）、`策划方案-实时护航与用法教练.md`（PreFlight/PostFlight 框架）

---

## 0. 一页结论

**先给判决，后面全是证据。**

### 0.1 第一性原理：分界线只有一条——谁写数据

| | RAG | 记忆体 |
|---|---|---|
| 数据谁写 | **你**，预先Ingest | **Agent 自己**，跑的过程中写 |
| 关于什么 | 外部知识语料（文档、代码、工单） | 这个用户、这个任务、这段历史 |
| 回答什么 | 「知识库里怎么说？」 | 「我已经知道这家伙什么？」 |
| 方向 | 只读 | 读写 + 遗忘 |

同一个向量库，相反的数据流向。混淆这两者是 2026 年最常见的架构 bug（多个来源一致结论）。

### 0.2 对 TUA 五类记录对象的判决

| 你要记的 | 本质 | 归属 | 存哪 |
|---|---|---|---|
| 项目结构 | 外部事实，随仓库变，可重建 | **RAG（档案）** | `project_profiles`，从文件/git 派生 |
| 使用习惯偏好 | 关于用户的演化状态 | **记忆体** | `memories`（kind=preference） |
| 常犯错误 | 重复事件 + 用户纠正 | **记忆体** | `memories`（kind=error），与 `habits` 联动 |
| 正确示范 | 正面样例，可提炼 | **记忆体** | `memories`（kind=exemplar） |
| 方法论 + 工作流 | 跨项目程序性知识 | **记忆体最高层** | `memories`（kind=playbook），可导出 AGENTS.md 片段 |

### 0.3 选型结论

**自建轻量记忆体：SQLite + FTS5，不引入任何外部记忆框架。**

- 不引 Mem0 / Zep / Letta：TUA 的记忆是**结构化事实**（一句人话 + 理由 + 证据），不是对话抽取；且用户纪律要求「记忆必须人可读可改可删」（D7 的延伸），这三个框架存的是向量或不透明状态块。
- 不引向量数据库：单用户、万级条记忆规模，FTS5 关键词检索足够。引 pgvector/Milvus 是拿火箭筒打蚊子。
- 不建知识图谱：TUA 的查询分布 95% 是点查（「这个项目的构建命令是什么」），GraphRAG 的多跳能力没有用武之地（判据见 §3.3）。
- 写入必须旁路 + 用户确认：TUA 是观测 Agent 的 Agent，它的写入不能打断被观测的会话——这是它与 Letta/Mem0 等「圈内 Agent」的本质区别，决定了整套设计形状（见 §5.4）。

**逐层技术选型总表**（含落选方案与改选型触发条件）见 §5.0。

### 0.4 三条最反直觉的

1. **「记忆越多越好」是错的。**  stale 记忆比没有记忆更糟——Agent 会信它。所有来源一致强调 supersede（标记失效）优于静默覆盖。
2. **「百万上下文窗口让 RAG 死了」是错的。** Chroma 2026 年跨 18 个模型实测：答案位于 20 文档第 5–15 位时准确率掉 30+ 分；某推理任务输入涨到约 3000 token 就从 0.92 掉到 0.68。标称百万，可信带宽 200–400K。
3. **「把规则写进 CLAUDE.md 让 Agent 记住」是最弱的手段。** 前沿模型可靠遵循约 150–200 条指令，系统提示已占约 50 条；每条能下沉成 lint / hook / 检查的规则，都绝不写成 prose（prose 是最后手段）。

---

## 1. 第一性原理：为什么「谁写数据」是唯一分界线

### 1.1 数据流向对比

```python
# RAG：你预先写，它只读
q = embed(question)
chunks = vector_db.search(q, top_k=5, collection="docs")
answer = llm(build_prompt(context=chunks, question=question))

# 记忆体：它自己写，自己读
note = summarize(task_outcome)                      # 从经验中提炼
vector_db.upsert(embed(note), collection="memory") # 写
recall = vector_db.search(embed(question))         # 读
```

同一个向量库，写入方向相反。**「记忆就是对着聊天记录的 RAG」是错的**——把历史消息灌进向量库再检索，只是 RAG 指向了 transcript；真正的记忆要抽取显著事实、合并重复、追踪变化、遗忘过期。生命周期才是重点。

### 1.2 CoALA 四类型框架（2026 年多数框架的实际分类）

| 类型 | 装什么 | 例子 |
|---|---|---|
| Working（工作） | 上下文窗口 + 草稿纸，会话结束即弃 | 当前轮的推理 |
| Episodic（情景） | 带时间戳的具体事件 | 「上周二用户为计费问题升级投诉」 |
| Semantic（语义） | 一般事实 | 「用户公司在 200 人的金融科技公司」 |
| Procedural（程序性） | 技能、规则、工作流 | 「退款前必须先确认发票号」 |

RAG 基本只覆盖 Semantic 型的外部文档查询；记忆体覆盖全部四型，且**从经验中写就**而非从固定语料摄取。

### 1.3 三条常见误区

| 误区 | 事实 |
|---|---|
| 「RAG 是一种记忆」 | RAG 是对语料的检索，是外部知识；记忆是 Agent 状态。实现都借用向量库，设计意图不同。 |
| 「必须二选一」 | 严肃助手两个都用：RAG 管「世界是什么」，记忆管「这家伙是谁」。客服 Agent 用 RAG 答退款政策，用记忆回忆「这客户本周已退两单、偏好邮件沟通」。 |
| 「上下文窗口大了就不需要记忆」 | 长上下文每次调用都贵、不跨会话持久、长了还掉质量。**上下文是工作台，记忆是档案柜。** |

---

## 2. 记忆体阵营

### 2.1 共同实现原理：五步生命周期

所有框架（Mem0 / Zep / Letta / LangMem / Cognee）都是这五步的不同取舍：

```
① 写入捕获    ──►  从对话/事件中判定什么值得记（不是全记）
② 存储组织    ──►  事实 + 向量 / 时序知识图 / 内存块 三种形态
③ 检索召回    ──►  按当前问题取相关记忆（top-k + 相关性下限）
④ 巩固整合    ──►  去重、合并、更新、supersede 旧事实
⑤ 遗忘衰减    ──►  长期未确认的降权/归档；边失效不删除
```

差异全在「哪步重哪步轻」：Mem0 重 ①④（抽取式事实），Zep 重 ②⑤（时序图 + 失效链），Letta 重 ③（OS 式三层分页 + 睡眠期整理），Cognee 重 ②④（ECL 管线 + 四动词 remember/recall/forget/improve）。

### 2.2 四大流派对比

| 框架 | 存储形态 | 核心机制 | 优势 | 代价 |
|---|---|---|---|---|
| **Mem0** | 抽取式事实 + 向量/图 | 对话→事实抽取→去重合并 | 开箱即用；token 压缩狠（<7K vs 全上下文 25K+） | 事实质量取决于抽取 prompt；库格式不透明 |
| **Zep / Graphiti** | 双时态知识图 | episode/semantic/community 三层子图；valid time + transaction time；**边失效不删除**；cosine+BM25+BFS 混合检索 + RRF/MMR | 时序推理强；检索 <100ms；115K token → 1.6K | 图维护重；实体抽取有错（行业口径漏 30–40%） |
| **Letta**（原 MemGPT） | core / recall / archival 三层 + memory blocks | OS 式内存分页（block_append/replace/read）；**sleep-time compute**（空闲期后台 Agent 整理，可用更强模型） | 长任务状态管理鼻祖；睡眠期整理思路被广泛借鉴 | 自建 Agent OS 复杂度高；四大失败模式（见 §2.4） |
| **约定文件派**（CLAUDE.md / AGENTS.md） | 纯 Markdown | 每次会话全量加载；跨工具靠 AGENTS.md 收敛（Linux Foundation 背书，多工具已读） | 零基础设施；人可读可改可 git 管；最可靠的持久化机制 | 全量进上下文，膨胀即失效；静态，不自动更新 |
| **LangMem** | SDK 形态 | 语义/情景/程序性记忆 + 遗忘曲线（ACT-R 衰减模型） | 与 LangGraph 生态集成 | 绑定 LangChain 生态 |

### 2.3 基准数据（LoCoMo / LongMemEval / DMR）

**原始 LOCOMO 对比**（长对话多跳问答）：

| 方案 | 准确率 | Token 量 | p95 延迟 |
|---|---|---|---|
| Full context（全量灌） | 72.9% | 26K | 17s |
| Mem0（图版） | 68.4% | — | — |
| Mem0（标准版） | 66.9% | 1.8K | 1.4s |
| RAG（朴素） | 61.0% | — | — |
| Baseline（无检索） | 52.9% | — | — |

注意这个表的两面性：全量上下文准确率最高，但 token 是 Mem0 的 14 倍、延迟是 12 倍——**这正是「记忆体的价值不在准确率上限，在单位成本下的准确率」**。

**Mem0 2026 新算法自报**：LoCoMo 92.5 / LongMemEval 94.4，<7K tokens（对比全上下文 25K+）。

**Zep 自报**：DMR 94.8% vs MemGPT 93.4%；LongMemEval 63.8% vs MemGPT 55.4%，延迟 3.2s vs 31.3s（约 10 倍）。

> ⚠️ 基准数字全是各家自报，口径不统一，只能当量级参考不能当选型依据。2025 年一项 meta 分析指出：控制位置偏差、长度偏差、试次偏差后，此前报告的 GraphRAG 增益被显著高估——同类警惕适用于所有记忆体基准。

### 2.4 失败模式（比优势更重要）

| 失败模式 | 机理 | 对策 |
|---|---|---|
| **Stale 记忆** | 六个月前的决策现在已错，Agent 照信 | supersede 优于静默覆盖；召回时暴露年龄；月度 prune、90 天审计 |
| **Over-recall** | 二十条记忆全量灌进 prompt，浪费上下文还混淆模型 | top-k + 相关性下限；不够让 Agent 再要 |
| **Chat as memory** | 持久化完整 transcript，看着全、用着废 | 只抽决策，其余留在会话里 |
| **空 human block**（Letta） | 用户画像块从未写入，个性化全靠猜 | 写入路径必须显式，不能等 Agent 自觉 |
| **块膨胀** | core memory 无限增长，最后什么都装不下 | 硬行数上限（对标 CLAUDE.md <200 行） |
| **归档垃圾抽屉** | archival 只进不出，检索永远捞不中 | 归档即设 TTL，到期真删 |
| **跨会话连续性断裂** | 会话内记得挺好，跨会话全忘 | 诊断测试：开新会话问「你记得什么」，答「不记得」就是持久层没接上 |
| **Schema drift** | 六个月前写的条目格式，现在的读取器理解不同 | 写入时带口径版本；定期审计 |
| **错层** | 密钥、PII、客户全量数据进记忆 | 这是策略 bug 不是功能；记忆只放工程决策与项目约定 |

---

## 3. RAG 阵营

### 3.1 实现原理：标准管线

```
Ingest  切块(chunk) → 嵌入(embed) → 入向量/关键词索引
Retrieve 查询嵌入 → ANN top-K（+ BM25 关键词）→ RRF 融合
Rerank  交叉编码器对 top-50 重排 → top-5
Augment 组装上下文（带元数据 + 引用）
Generate 生成（强制带引用）→ 事后 faithfulness 检查
```

### 3.2 RAG vs 长上下文：窗口大了，检索没死

**2026 年的现实**：前沿家族都标称约 1M 窗口，但：

| 证据 | 数字 |
|---|---|
| Context rot（Chroma，18 模型） | 答案在 20 文档第 5–15 位，准确率掉 30+ 分；纯输入长度效应 7.9%；某推理任务 0.92→0.68（约 3000 token） |
| Anthropic contextual retrieval 基准 | 即便给了宽松窗口，标准检索仍漏掉正确 chunk 5.7% |
| 有效上下文带 | 200–400K，与标称百万无关（多源一致） |
| 经济账 | 500K token 语料、1 万查询/天：全量灌 ≈ $12,500/天；RAG 检索 4K token ≈ $100/天（约 125 倍） |
| Long-context tax | Gemini 3.1 Pro 超 200K 后 $2→$4/M；超阈值请求换模型打包就是纯亏 |

**判决**：RAG 在小而静态的语料上确实被长上下文取代了（<200K、缓存友好、单文档）；但凡语料大、变得快、要引用、要权限控制、要按查询算账，检索全胜。2026 主流是混合：**宽松检索 + 长上下文模型在短名单上推理**。

### 3.3 三条路线：向量 / 图 / Agentic

| 路线 | 什么时候用 | 什么时候别用 |
|---|---|---|
| **向量 RAG**（hybrid + rerank 是底线） | 默认选项。语义相似查询、点查、无 schema 需求的非结构化语料 | 精确术语/标识符单独用会漏（所以必须配 BM25） |
| **GraphRAG** | 查询需要显式关系遍历：引用链、监管交叉引用、供应链路径；3+ 跳推理；合规/医疗/法律域；要可解释审计链 | 简单事实查询（过度设计）；小语料（索引成本不值）；高变更语料（重复抽取不经济）；**<20% 查询需多跳就别建图** |
| **Agentic RAG** | 多源顺序检索、首查可能失败需自适应重试、SQL+向量+Web 混合、要逐步审计链 | 单源问答、简单查询、成本敏感。成本约 10 倍、延迟 1–2s → 4–15s p95 |

**Agentic RAG 的实测收益**（值得记，因为 TUA 的 PreFlight 就是同类思路）：
- DSPy 复杂任务：24% → 51% 准确率
- CMU 金融合规（9000 问）：幻觉率 14.1% → 4.9%，仅增约 220ms，完整性 +27 分
- Adaptive RAG 路由：60–70% 生产查询是简单的，跳过 agentic 路径直接省大钱

**GraphRAG 的隐性成本**（决定 TUA 不碰它）：
- 索引期 LLM 实体抽取 $20–50/M token 语料；LazyGraphRAG 压到约 0.1% 但仍有策略失败模式
- 实体抽取在典型企业语料上漏 30–40%，同名合并错误会静默污染全图
- 图遍历 200–300ms vs 向量 ANN <50ms；实时路径直接出局
- Meta 分析：通用查询分布增益常 <10%（宣传的 3–4 倍只在合规/医疗等多跳域成立）

### 3.4 chunking / reranking / hybrid：生产配方

按优先级排序（多数团队到第三步就该停）：

1. **chunking 先修**。语义单元优先于字符数；512 token 是经验默认；⚠️ overlap 有分歧：2026-01 一项系统研究称 overlap 无可测收益且抬高索引成本，Weaviate 指南仍建议 50–100 token——**在自己语料上测，别信默认值**。表格千万别进通用切分器（值丢了列头就废了）。
2. **hybrid 检索**（dense + BM25，RRF 融合，k=60）。纯向量漏精确术语（错误码、SKU、人名、标识符），纯关键词漏语义改写。这是标配不是优化。
3. **交叉编码器 rerank**：retrieve 50 → rerank → 5。切块修对之后，这是单项增益最大的一步（跳过 rerank 质量掉约 20%，单一来源口径，当量级看）。
4. 之后才考虑：query rewriting / HyDE、metadata 预过滤、ColBERT 迟交互、query routing、GraphRAG——**每一步都必须有评估集证明它动了数字**。

### 3.5 RAG 优劣总表

| 优势 | 代价 |
|---|---|
| 答案可溯源、可引用 | 检索漏了就是静默错误（5.7% 漏 chunk 率） |
| 语料可远超任何窗口 | 切块/嵌入/索引一套基础设施 |
| 按查询付费，规模经济 | 每次语料变更要重嵌入 |
| 支持权限与审计 | 检索质量需持续评估（无评估=信仰） |
| 减少幻觉（有据可依） | reranker/judge 都是额外成本与故障点 |

---

## 4. 正面对决：决策规则

```
知识在别人维护的文档里，对所有用户答案相同？  ──是──► RAG
        │否
需要跨会话记住这个用户/这个任务并自己更新？  ──是──► 记忆体
        │否
两者都要（严肃助手的常态）               ──►  分层混合：RAG 管知识，记忆管状态
```

三条纪律（多来源一致）：

1. **同一个向量库也要分 namespace**。用户记忆和语料 chunk 混存是隐私泄漏和检索混乱的快车道。
2. **记忆层必须可被人读写**。藏在数据库或隐藏目录里的记忆，你只在它出错时才发现它「知道」什么——那是它最弱的点。
3. **检索与记忆走不同路径，编排层强制边界**。硬塞进单一存储又无归属规则，语义错配和归因困难是必然。

---

## 5. TUA 落地设计

### 5.0 技术选型总表（先看这张）

选型三原则：**规模匹配**（单用户万级记忆，不上分布式）｜**本地优先**（D2 数据不出本机）｜**不新增故障面**（能用既有表和既有通道的，绝不新建）。

| # | 层 | 选型 | 落选方案与理由 |
|---|---|---|---|
| 1 | 记忆存储 | **SQLite（既有库）+ FTS5 虚拟表** | pgvector / Milvus / Chroma：规模不匹配，且白添一个故障源；Mem0 / Zep / Letta：存的是向量和不透明状态块，违反「人可读可改可删」 |
| 2 | 语料 / 档案存储 | **同一 SQLite，独立表 `project_profiles`** | 独立向量库：档案是结构化字段（stack / layout / commands / landmines），关系表比向量更准也更好维护 |
| 3 | 检索 | **FTS5 关键词 + scope / kind / 新鲜度三重过滤** | 向量 ANN：记忆是短句结构化事实，关键词 + 元数据过滤已够；混合 RRF：在当前规模属多余 |
| 4 | 嵌入 | **不做嵌入** | text-embedding-3-large / bge-m3：引入嵌入服务 = 新增依赖 + 成本 + 延迟，当前规模下收益为零 |
| 5 | 提炼模型 | **用户自带 Key 的 OpenAI 兼容端点**（D9：火山方舟 / 阿里云百炼） | 本地 Ollama：质量自担，列备选；专用更强模型：只留给「确认记忆」的 judge 场景（judge 能力 ≥ 被测系统） |
| 6 | 注入通道 | **Qoder `UserPromptSubmit` → `additionalContext`**（既有通道，与事前干预卡合并为一张） | 新增 hook：多一个失败面；PreToolUse `ask`：已定性为体验差（见《策划方案》§2） |
| 7 | 写入通道 | **Stop hook 旁路 + 空闲期批量提炼** | 会话内直接写：会打断被观测会话，破坏旁路 fail-open 纪律 |
| 8 | 编排框架 | **无框架，Swift 侧直接 SQL + GRDB 既有薄封装** | LangGraph / LangMem：Python 生态 + 跨进程，单机产品属杀鸡用牛刀 |
| 9 | 评估 | **自建四指标，指标定义沿用 RAGAS** | 引 ragas 包：评估数据在 Swift 侧，跨语言调用成本大于收益 |
| 10 | 导出 | **AGENTS.md 片段，用户手动复制** | 自动写仓库：D7 红线，建议只读只复制 |

> ⚠️ **已知技术风险（选型表未解决项）**：FTS5 默认的 `unicode61` 分词器把连续中文当作单个 token，「构建命令」这类词切不开，检索会退化成整句匹配。两条解法：① 改用 `trigram` 分词器（需 SQLite ≥ 3.34，**落地前必须先验证 App 运行时打包的 SQLite 版本**）；② 编译带 ICU 的 SQLite。退化兜底是 LIKE 子串匹配（精度差但不会漏）。此项列为 M15 前置验证任务，验证不通过则第 3 行检索选型降级为「自建分词表 + LIKE」。

**改选型的触发条件**——选型是规模的函数，不是信仰。出现下列信号才回头改，不预支复杂度：

| 信号 | 达到时改什么 |
|---|---|
| 记忆条数 > 5 万，或 FTS5 查询 p95 > 200ms | 检索层加向量 ANN，改 hybrid + RRF |
| 关键词检索开始因语义歧义漏中（同义改写查不到） | 上嵌入层，检索改 hybrid |
| 出现跨设备 / 多人共享需求 | 存储层换 Postgres + pgvector，接入层重新设计 |
| 多 Agent 并发观测同一项目，写入出现竞争 | 加写入队列 + 冲突合并（supersede 链已有基础） |

### 5.1 TUA 的特殊性：它是「观测 Agent 的 Agent」

TUA 自己不干活，它观测 Qoder 里的 Agent 会话。这决定了它的记忆有**双重性**，正好落在 §1.1 的分界线两侧：

| 层 | 语义 | 数据流 | 例子 |
|---|---|---|---|
| 语料层 | **RAG**：外部事实 | TUA 只读 | transcript 原文、仓库文件、git 历史 |
| 记忆层 | **记忆体**：自己写的 | TUA 读写 + 遗忘 | 「这用户禁半角标点」「这项目 make test 不是 pytest」 |

**由此推出最重要的架构约束：TUA 的写入不能打断被观测会话。** Letta 可以在 ReAct 循环里直接调 `block_append`，TUA 不能——它的写入必须是钩子旁路 + 事后确认。这条约束塑造了下面全部设计。

### 5.2 三层架构

```
L1 指令层（RAM，每次都进上下文，硬上限 ~200 行）
   ├─ TUA 自身规则（口径版本、干预纪律）
   └─ 用户的 AGENTS.md / CLAUDE.md 镜像（只读缓存，按 workspace 隔离）
        ▲ 对标：CLAUDE.md <200 行；路径级规则放 .claude/rules/ 按需加载

L2 记忆层（DISK，按需检索）
   └─ memories 表（FTS5 检索，top-3，带相关性下限与新鲜度门槛）

L3 语料层（外部事实，可重建）
   ├─ transcript 原文（events.turns 已存锚点）
   └─ project_profiles（从文件/git 派生，hash 校验新鲜度）
```

对标 RAM/disk 框架：L1 是开机加载的内存，L2 是按需换入的磁盘，L3 是不怕丢的源数据。**任何东西进 L1 都要问「它真的需要每次都在场吗」。**

### 5.3 数据模型（新增三表，全部对齐既有纪律）

既有表：`workspaces` / `events` / `turns` / `findings` / `suggestions` / `habits`，规划中 `predictions` / `guard_log`。
新增：

```sql
-- 记忆体主表：一句人话 + 为什么 + 证据（D7 延伸：人可读可改可删）
memories(
  id                INTEGER PK,
  scope             TEXT,      -- 'global' | 'workspace:<id>' | 'project:<path>'
  kind              TEXT,      -- preference / error / exemplar / playbook / project_fact
  statement         TEXT,      -- 一句人话，主面板可直接编辑
  reason            TEXT,      -- 为什么（防 Agent 重新提议你已否决的事）
  evidence_ids      JSON,      -- 支撑证据：turn_id / event_id 列表
  confidence        REAL,      -- 0-1，命中次数 × 时间衰减
  confirmed_count   INTEGER,   -- 被再次确认的次数
  status            TEXT,      -- active / pending / superseded / archived
  superseded_by     INTEGER,   -- 失效链：supersede 优于覆盖
  source            TEXT,      -- rule / llm / manual
  created_at        INTEGER,
  last_confirmed_at INTEGER
)

-- 项目档案（RAG 语义：外部事实，可重建，不进记忆层）
project_profiles(
  id           INTEGER PK,
  workspace_id TEXT,
  section      TEXT,      -- stack / layout / commands / conventions / landmines
  content      TEXT,
  source       TEXT,      -- file_scan / git / llm / manual
  source_ref   TEXT,      -- 文件路径或 commit
  content_hash TEXT,      -- staleness 检测：hash 变了即标记待刷新
  refreshed_at INTEGER
)

-- 审计：记忆的每次生死都留痕（对齐 guard_log 思路）
memory_audit(
  id        INTEGER PK,
  ts        INTEGER,
  action    TEXT,      -- create / confirm / supersede / prune / export
  memory_id INTEGER,
  actor     TEXT,      -- rule / llm / user
  note      TEXT
)
```

**与既有表的分工**（不重复造）：

| 表 | 角色 | 与 memories 的关系 |
|---|---|---|
| `habits` | 统计型习惯（跨轮聚合，kind 封闭词表，带 wasted_credit_est） | memories 的**统计聚合**：一条 error 记忆多次被确认 → 可提炼成 habit |
| `findings` | 单次异常/机会 | 可作 evidence_ids 的来源 |
| `suggestions` | 一次性建议卡片 | 被采纳的建议可升级为 preference 记忆 |
| `predictions` | 事前预测 vs 事后（校准源） | 预测失准的模式可提炼为 error 记忆 |

### 5.4 写入路径：旁路 + 确认（核心设计）

四条通道，按成本从低到高：

| # | 通道 | 触发 | LLM 成本 | 落库状态 |
|---|---|---|---|---|
| 1 | **被动确认** | Stop hook 后，既有规则命中（loop_thrash 复现、违禁模式再犯等） | 零 | active 记忆 `confirmed_count++`，刷新 `last_confirmed_at` |
| 2 | **修正语捕获** | UserPromptSubmit 的 prompt 原文含修正信号（「不对」「别」「以后都」「记住」） | 零 | `pending`（**不进注入池**） |
| 3 | **Sleep-time 本地版** | 每周/每晚空闲期：把本周 turns 批量送 LLM 提炼 | 一次批量 | 产出 `pending` → 主面板「记忆收件箱」，用户一键确认/改/删 |
| 4 | **手动** | 「记住这个」按钮 + 主面板直接编辑 statement | 零 | 直接 `active` |

通道 3 是 Letta sleep-time compute 的本地平替：**整理发生在用户不看 IDE 的时候，且不占用被观测会话的注意力**。产物一律先 `pending`——这不是妥协，是架构：LLM 提炼必然有幻觉（RAGAS 的 faithfulness <0.7 即视为明显幻觉），用户点头才 `active`，与 D7「建议只读只复制」同源。

写入格式统一用最小可存形态（跨来源一致的最优实践）：

```
statement: 这项目测试跑 make test，不是 pytest
reason:    直接 pytest 会漏 build tag 阶段的用例（2026-09-30 踩过）
scope:      project:/Users/zxc/.../token-usage-assistant
evidence:   [turn#812, turn#847]
```

### 5.5 检索与注入：复用 PreFlight 通道

**不新增干预通道**——记忆注入走既有 `UserPromptSubmit` 的 `additionalContext`，与事前干预卡合并为一张卡（`策划方案` §3.4 已定此通道）。

注入时的四道闸：

1. **scope 过滤**：当前 workspace/project 的记忆 + global，其余不取
2. **状态过滤**：只取 `active`；`pending` / `superseded` / `archived` 一律不注
3. **新鲜度门槛**：`last_confirmed_at` 在 30 天内（防 stale，比 confidence 绝对值更重要）
4. **top-3 + 相关性下限**：FTS5 命中分低于阈值就一个都不注（防 over-recall；宁缺勿滥，让用户下次说得更具体）

检索 query = 本次 prompt 原文 + 当前 scope 标识。不做 embedding——见 §0.3。

### 5.6 巩固与遗忘

| 机制 | 规则 |
|---|---|
| **supersede** | 新记忆与旧的冲突 → 旧记忆 `status=superseded` + `superseded_by` 指向新的，**永不删除、永不静默覆盖**，audit 表留痕 |
| **月度 prune** | 90 天未确认且无新证据 → `archived`；`pending` 超 30 天无人处理 → 自动废弃（不收垃圾抽屉） |
| **staleness 检测** | `project_profiles.content_hash` 与仓库现状不符 → 标记待刷新，暂停注入（faithfulness：宁可不说，不可说错） |
| **衰减模型** | confidence 随时间衰减（ACT-R 思路：不访问就弱化，被确认就强化），但衰减只影响排序不影响存亡 |
| **导出** | `kind=playbook` 的记忆一键生成 AGENTS.md 片段，**用户复制走**，TUA 绝不写用户的仓库（D7 红线） |

### 5.7 与既有机制的咬合图

```
PostToolUse/Stop ──► events/turns ──┬─► AnalysisEngine ──► findings/habits（已有）
                                     │
UserPromptSubmit ────────────────────┼─► 修正语捕获 ──► memories(pending)
                                     │
空闲期批量 LLM ─────────────────────┘─► memories(pending) ──► 用户确认 ──► memories(active)
                                                                              │
下一次 UserPromptSubmit ◄──────────── PreFlight 注入（top-3 active + 30天内）◄┘
                                     │
                                     └─► guard_log 记录本次注入了什么、用户随后的反应
```

「事后收集信息 → 判断本次提交可能有什么问题 → 下次干预」这条用户已拍板的闭环，正好就是记忆体的写-读循环。**PreFlight 是记忆的读端，PostFlight 是记忆的写端，两者本来就是一套东西。**

---

## 6. 评估：RAGAS 四指标移植

RAGAS 原版指标：faithfulness（答案对上下文的事实一致性，<0.7 视为明显幻觉）、context precision（检索信噪比）、context recall（该捞的捞到没有）、answer relevancy（答没答到点上），总分取调和平均（惩罚极端值）。

| RAGAS 原指标 | TUA 移植版 | 算法 |
|---|---|---|
| context precision | **注入命中率** | 注入的 top-3 中，后续被证明相关的比例（用户说「对/就按这个」、或该错误本轮未再犯） |
| context recall | **漏忆率** | `habits` 检出的重复错误中，`memories` 无对应条目的比例（反复犯的错记忆库居然没有） |
| faithfulness | **复述失真率** | 复述的项目结构与仓库现状的一致率（content_hash 比对 + 抽样人工/LLM 判） |
| answer relevancy | **建议采纳率** | `suggestions.status=acknowledged` 占比（已有数据，零新增成本） |
| （RAGAS 无对应） | **预测校准** | `predictions` 表事前 vs 事后偏差（策划方案 §3.3 已有） |

三条评估纪律（RAGAS 的已知脆弱点，直接继承）：

1. **judge 非确定性**：同一管线两次跑分不同，小波动是噪声不是回归。指标只看趋势，不看绝对值。
2. **judge 能力 ≥ 被测系统**：用来判「这条记忆提取得准不准」的模型，不能弱于提炼它的模型。
3. **先建评估集再改管线**：50–200 条「问题 + 期望记忆」对起步，没有评估集的一切调参都是信仰。

---

## 7. 诚实边界与不做清单

### 7.1 边界（写进文档而不是藏在心里）

1. **老数据积分维度为空**。transcript 回灌侦察结论：`usage.credits` 仅 4/2928 条携带，老数据积分维度为空。记忆提炼的历史深度因此受限，以近期数据为主，UI 需标注「自 X 日起」。
2. **LLM 提炼有幻觉**。所以一切提炼产物先 `pending`，用户确认才 `active`。这不是产品不完整，是产品形态。
3. **隐私有张力，必须显式 trade-off**：D2 说「数据不出本机」，但通道 3（sleep-time 提炼）要把 prompt 原文送给 LLM（用户自带 key，火山方舟/阿里云百炼）。**默认关，opt-in，开启前明示「哪些数据会离开本机」**。纯本地 Ollama 是另一个选项但质量自担。
4. **基准数字全是自报**。§2.3 的表只能当量级参考；选型不基于 benchmark 而基于 §0.2 的归属判决。

### 7.2 不做清单（以及为什么）

| 不做 | 理由 |
|---|---|
| 不引 Mem0 / Zep / Letta | 要本地可读可编辑 + 结构化事实 + 已有一套 SQLite；三框架存的是向量或不透明块 |
| 不引向量数据库 | 单用户万级记忆，FTS5 够。引入 = 运维成本 + 一个新故障源 |
| 不建知识图谱 | 查询分布 95% 点查；建图成本（抽取错 30–40%、遍历 200–300ms）无对应收益 |
| 不做全量记忆注入 | over-recall 是确定的失败模式；top-3 + 新鲜度门槛是解 |
| 不自动写用户仓库 | D7 红线：建议只读只复制 |
| 不做跨 Agent 记忆归一化 | D14：只分不并。各 Agent 的「常犯错误」各记各的 |

---

## 8. 来源清单

均于 2026-10-14 检索：

**记忆体**
- Mem0 官方基准自报（LoCoMo 92.5 / LongMemEval 94.4 / <7K tokens）；原始 LOCOMO 对比表（full context 72.9% vs Mem0 graph 68.4% vs Mem0 66.9% vs RAG 61.0% vs baseline 52.9%）
- Zep / Graphiti 文档（三层子图、双时态、边失效不删除、DMR 94.8% vs MemGPT 93.4%、LongMemEval 63.8% vs 55.4% 且延迟 3.2s vs 31.3s、115K→1.6K tokens）
- Letta / MemGPT 文档（core/recall/archival、memory blocks、sleep-time compute、四大失败模式）
- Cognee 文档（ECL 管线、remember/recall/forget/improve 四动词）
- plur.ai《RAG vs Agent Memory》（CoALA 四型、ACT-R 衰减、向量库是存储不是记忆框架）

**RAG**
- Chroma Context Rot 研究（18 模型、位置 5–15 掉 30+ 分、7.9% 长度效应、0.92→0.68）
- Anthropic contextual retrieval 基准（标准检索漏 chunk 5.7%）
- howaiworks.ai / dataaspirant.com / daily.dev（长上下文 vs RAG 决策、200–400K 有效带、$12,500/天 vs $100/天 125 倍、long-context tax）
- casys.ai / tianpan.co / tunicyberlabs.com（GraphRAG vs 向量 RAG 选型、抽取漏 30–40%、遍历 200–300ms、LazyGraphRAG 0.1% 成本、meta 分析增益高估）
- bitontree.com / blog.saaro.net / nomadx.ae（agentic RAG：DSPy 24%→51%、CMU 幻觉 14.1%→4.9%、成本 10 倍、Adaptive RAG 60–70% 简单查询）
- buildrlabs.ai / jacar.es / rajatgautam.com / callmissed.com（chunking 512 默认、overlap 分歧、RRF k=60、retrieve 50 rerank 5、跳过 rerank 掉约 20%）

**编码 Agent 记忆实践**
- Anthropic Claude Code 官方 memory 文档（CLAUDE.md vs AGENTS.md、auto memory 四型 user/feedback/project/reference）
- thepromptshelf.dev（AGENTS.md 3–7 条目、>10 稀释、与 CLAUDE.md 互补）
- typefire.app（三层记忆：指令文件 / Agent 自记 / 可检索 vault；指令文件保持短）
- radar.zurb.com（CLAUDE.md 是 RAM 不是垃圾抽屉、150–200 指令上限、Nick Porter 砍到 81 行、能下沉 lint 的绝不写 prose）
- clawdbytes.com（compaction 是主动遗忘、四类衰减、85–90% 触发优于 95%）
- dev.to/icegaming（decision/reason/scope/provenance 四件套、supersede 优于覆盖、over-recall、chat as memory 最差）
- ninadpathak.com（跨会话连续性诊断测试、schema drift、月度 prune + 90 天审计）
- abstractalgorithms.dev（四层记忆 tier、约束必须放系统提示否则被压缩吃掉）

**评估**
- RAGAS 官方文档与 benchmarkingagents.com 指标详解（faithfulness <0.7 警戒、调和平均、三大脆弱点：judge 非确定性 / judge 能力 / 成本）
