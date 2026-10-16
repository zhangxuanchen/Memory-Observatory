-- 记忆外骨骼（L6 能学）自有表。
-- E1 定案：本期 schema 归 mo-exoskeleton 自带（模块自持），与 mo-server 共用同一 MO_DB；
-- 与 mo-server 的 schema.sql 分开维护，避免两处改一处漏一处。
--
-- 只建一张：规则与版本。hit / review 两张属后续版本（设计文档 §4.2 定义了四张，§F 本期不做其余），
-- 现在建了也是空表，只会增加维护面。
--
-- 主键取 (rule_id, version)：旧版本行保留（停用可查），churn = 观察窗内版本数（§2.7）。
-- 若按 §4.2 的 rule_id 单列主键，旧版本会被覆盖，churn 直接算不出来——这是刻意的偏离。

CREATE TABLE IF NOT EXISTS memory_rules (
    rule_id     TEXT   NOT NULL,
    version     INT    NOT NULL DEFAULT 1,
    cluster_key TEXT   NOT NULL,           -- 行为形状的簇标识（本期为人工粗类，§E2）
    body        TEXT   NOT NULL,           -- 规则文本（注入 Agent 用，只写记忆策略）
    state       TEXT   NOT NULL,           -- SHADOW | LIVE | RETIRED
    hit_count   BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version)
);
CREATE INDEX IF NOT EXISTS idx_mr_cluster ON memory_rules(cluster_key);
CREATE INDEX IF NOT EXISTS idx_mr_created ON memory_rules(created_at);

-- ---------------------------------------------------------------------------
-- 生命周期列（§2.6 自动降级 / §2.7 版本）：降级印记与硬化印记
--
-- 用 ALTER 而不是改上面的 CREATE：已有库的 memory_rules 已建，CREATE IF NOT EXISTS 不会补列。
-- IF NOT EXISTS 使两条路径（新库 / 旧库）结果一致，重复启动无副作用。
--
-- demoted_at 是「只降不升」的落点：降级时写上时间戳，promote() 的谓词要求它为 NULL，
-- 于是被降级的规则不能再走自动晋升——回升必须走人工路径（clearDemotion）清掉印记（§2.6 / A5）。
-- 单靠 state 区分不出「从未上线的影子」与「被降级回来的影子」，这一列就是那道区分。
-- hardened_at 是 §2.6 第三条：命中面扩了但「准」没动 → 标记硬化、提示人工复核（只提示，不停用）。
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS memory_rules ADD COLUMN IF NOT EXISTS demoted_at    TIMESTAMPTZ;
ALTER TABLE IF EXISTS memory_rules ADD COLUMN IF NOT EXISTS demote_reason TEXT;
ALTER TABLE IF EXISTS memory_rules ADD COLUMN IF NOT EXISTS hardened_at   TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- 评估快照（§2.6 第三条「连续两个评估周期「准」项无改善」）
--
-- 「评估周期」= 两次相邻审计。每次审计为每条规则落一行读数，硬化判定 = 回看最近三次读数、
-- 两次相邻比较都无改善，则标记硬化。没有这张表就无从比较「这一期比上一期有没有变好」。
--
-- 各列可空，NULL = 该次审计这一项不可测（与 GateVector 同一约定：不可测不是 0）。
-- action 记录本次对该规则做了什么：NONE / DEMOTE_HIT / DEMOTE_COST / HARDEN / RETIRE_CHURN。
-- 快照只增不改：它是「当时读到了什么、据此做了什么」的账本，不因规则后来被降级而回填。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_rule_evals (
    eval_id         BIGSERIAL PRIMARY KEY,
    rule_id         TEXT NOT NULL,
    version         INT  NOT NULL,
    evaluated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    hit_share       DOUBLE PRECISION,   -- 命中面 = 该簇非 holdout turn / 全部 turn（§2.6 限制一）
    token_delta     DOUBLE PRECISION,   -- 命中组单位成本相对对照组的差（§2.6 限制二），可空
    fail_delta      DOUBLE PRECISION,   -- 命中组失败率相对对照组的差（§2.6 限制二），本期结构性不可测
    correction_rate DOUBLE PRECISION,   -- 该簇「准」（§2.6 第三条），可空
    action          TEXT NOT NULL       -- NONE | DEMOTE_HIT | DEMOTE_COST | HARDEN | RETIRE_CHURN
);
CREATE INDEX IF NOT EXISTS idx_mre_rule ON memory_rule_evals(rule_id, version, evaluated_at DESC);

-- ---------------------------------------------------------------------------
-- 判据列迁出 memory_turns（架构重构 M3 / 步骤 15，治 R2：memory_turns 双写）
--
-- 背景：memory_turns 由 owner（mo-server）建表并物化，但判据侧（本模块）原先直接 UPDATE 它的
-- 判据列（is_holdout / corrected / task_type / …），形成「一张表两个 writer」。本表把判据列
-- 移出 memory_turns，以 turn_id 关联，使 memory_turns 彻底单写（对齐蓝图 §4.4）。
--
-- 为什么以 turn_id 为键：判据天然 per-turn（is_holdout 按 turn 哈希分配；corrected 看相邻 turn）。
-- task_type 虽是 session 粒度，但为免再造一张表、也便于按 turn join，同样落这里（同 session 各 turn 同值）。
--
-- 为什么本表归本模块：写它的是判据侧（TaskTypeRepository / TurnCriteriaRepository），
-- mo-server 不读也不写它——表所有权与 writer 一致（蓝图 §4.4）。
--
-- session_id 冗余存一份：取候选（「还有 turn 未分类的 session」）与按类取原文都按 session 走，
-- 冗余一列省一次 join。写侧由 memory_turns 带出。
--
-- 迁移顺序（不可一步到位，见 §6.3 步骤 15）：本文件只建表；回填 / 双读比对 / 删列由
-- scripts/migrate-criteria-out-of-turns.sql 与 scripts/rollback-criteria-to-turns.sql 承担。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_turn_criteria (
    turn_id           TEXT PRIMARY KEY,
    session_id        TEXT,
    task_type         TEXT,
    task_note         TEXT,
    task_type_version TEXT,
    classified_at     TIMESTAMPTZ,
    task_judge_mode   TEXT,
    task_judge_votes  TEXT,
    corrected         BOOLEAN NOT NULL DEFAULT FALSE,
    is_holdout        BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mtc_session ON memory_turn_criteria(session_id);
CREATE INDEX IF NOT EXISTS idx_mtc_task    ON memory_turn_criteria(task_type);

-- ---------------------------------------------------------------------------
-- 撰写器草稿缓存（B20.3：把「同一画像同一文本」从进程内扩到跨重启）
--
-- 契约（§4.5）要求「对同一个画像产出同样的文本」。LLM 侧只有 temperature=0 是**近似**确定，
-- 严格兑现原先只靠进程内的 ConcurrentHashMap——进程一重启，缓存清空，同一画像是否逐字复现
-- 就取决于模型侧了。故把首个成功结果落这一张表：重启后先查它，命中就直接回，不再问模型。
--
-- 为什么必须另起一张表：memory_rules 存的是**规则**（簇一旦有规则就不再进候选），
-- 而这份缓存存的是**给候选簇写过的句子**（包括写入失败 / 已存在而没落规则的簇）——
-- 两者生命周期不同，塞进 memory_rules 只会把「规则的当前态」搅浑。
--
-- 这不是第二条写库路径：它只记「模型说过什么」，不进注入、不进闸门、不影响任何判据；
-- 想让某簇重新生成，删掉这一行即可（下次照常问模型）。
--
-- ON CONFLICT DO NOTHING：同一簇的句子一旦定下就不再改写，这正是确定性的落点。
-- model 只作审计（记下这句是哪次配置产出的），不参与命中判断——契约认的是画像，不是模型。
-- 失败不写：模型没产出就没得缓存，下次仍会重试（与进程内缓存同一规矩）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_draft_cache (
    cluster_key TEXT PRIMARY KEY,
    body        TEXT NOT NULL,
    model       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- memory_turns 的 DDL 归 owner（mo-server 的 schema.sql）；本模块不再 ALTER 该表、也不写它的任何列。
--
-- 理由见《架构重构-增量与目标蓝图》R1：原先两个模块各持一份该表 DDL，列是否建成取决于启动顺序
-- （外骨骼先起时 ALTER ... IF EXISTS 静默跳过）。收敛后任一启动顺序下列集合一致。
--
-- 判据列（is_holdout / corrected / task_type / …）已于步骤 15 迁出 memory_turns，落到本模块自建的
-- memory_turn_criteria（以 turn_id 关联，见上）。故 memory_turns 自此只见物化列（turn_id / agent_id /
-- session_id / user_text / source / outcome / action_seq / started_at / token_total / latency_ms /
-- io_kind_mix），本模块只读不写。启动时由 TurnSchemaGuard 自检「本模块读取 memory_turns 所需列存在」
-- 且「memory_turn_criteria 表存在」，缺任一即失败，并由 compose 的 restart: unless-stopped 待
-- mo-server 建好 memory_turns 后自愈。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 决策卡片（§4.10）：把判断变成选择题
--
-- 卡片必须落库，不能只在内存里生成——这是 §4.10.4 契约自身的要求：
--   · reject 的后果是「退出候选，同一簇不再重复提议」⇒ 需要记住「谁被驳回过」；
--   · defer 的后果是「保持影子，下个周期再报」⇒ 需要记住「谁被推迟过」；
--   · expires_in_days ⇒ 需要 created_at / expires_at 才谈得上到期。
-- 不落库的实现答不出上面任何一问，只能反复把同一张卡推给人——正是 §4.10.6 说的坏信号。
--
-- card_id 取 {template}:cluster:{簇键}（如 T1:cluster:readonly|steps:1-3），与 §4.10.4 的示例一致；
-- 合并卡（§4.10.5「合并同类」）取 {template}:merged:{键集哈希}，键集哈希对同一批簇稳定，故重复生成仍是刷新。
--
-- subjects 是该卡覆盖的簇键数组（单簇卡一个元素，合并卡多个）。用数组而不是单列 cluster_key：
-- 「同一簇不再重复提议」要按簇判，而合并卡一次覆盖多个簇——单列记不下。
--
-- payload 存 §4.10.4 契约的完整 JSON 快照：卡面（标题/证据/选项/后果）是**当时**生成的，
-- 判据随新数据重算后不该静默改写已发出的卡——否则人看到的证据和点下时的证据不是一份。
-- 故 payload 只增不改；要更新就等下一轮重新生成（status 回 OPEN）。
--
-- severity 供 §4.10.5 的「排序截断」用；单轮最多 N 张（其余排队），N 走配置。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_cards (
    card_id     TEXT PRIMARY KEY,
    template    TEXT  NOT NULL,           -- T1..T6
    subjects    TEXT[] NOT NULL DEFAULT '{}', -- 覆盖的簇键；无簇的模板（T4/T6）为空数组
    severity    INT   NOT NULL DEFAULT 0, -- 排序用：越界/硬化 > 样本不足
    payload     JSONB NOT NULL,           -- §4.10.4 契约的完整快照
    status      TEXT  NOT NULL,           -- OPEN | ANSWERED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    answered_at TIMESTAMPTZ,
    answer      TEXT                      -- 选项 key：approve/defer/reject/retire/narrow/…（详见 CardPlanner）
);
CREATE INDEX IF NOT EXISTS idx_mc_status  ON memory_cards(status);
CREATE INDEX IF NOT EXISTS idx_mc_subjects ON memory_cards USING GIN(subjects);

-- ---------------------------------------------------------------------------
-- 技能提议（技能发现）：从「一类任务」的对话里提炼出的可复用技能，等用户决定要不要用
--
-- 与 §4.10 决策卡片的分工：卡片问「记忆行为这条规则要不要上线」（退后台），
-- 本表问「这类任务沉淀出的技能要不要启用到工作台」（人面）。两者对象不同、互不替代。
--
-- 为什么提议必须落库：提议要能被「采纳 / 不用」并记住结果——
--   · 采纳 ⇒ 技能落到 mo-server 的工作台技能库，同一类不再重复提议；
--   · 不用 ⇒ 同一类的同一份证据不再反复冒出来打扰人（指纹不变则不再提）。
-- 不落库就只能每次重算、反复推同一件事——正是「自己学、少打扰」要避免的。
--
-- 一格一类：proposal_id 直接取 task_type 取值（fix_bug / add_feature / …，一个类型一条提议），
-- 采纳/不用后再提炼只更新 payload，不新增行（指纹变了才回到 PENDING）。
-- 用纯取值而非 "task:" 前缀，是为了让 URL 里不出现冒号（/skills/fix_bug/adopt）。
--
-- tools 存 JSON 数组字符串（如 ["readFile","bash"]）而不是 TEXT[]：
-- 这是外骨骼侧唯一需要写数组的列，用 JSON 串省掉 JDBC 数组绑定这一层，
-- 读时用 Jackson 解析（与 SkillLibrary 的 YAML 字段同名同义）。
--
-- digest_hash = 该类对话摘要的指纹：用来判「同一类的证据有没有变」——
-- 变了才允许把已 DISMISSED 的类重新提上来；没变就保持沉默。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_proposals (
    proposal_id TEXT PRIMARY KEY,             -- agent:<agent_id>:task:<task_type>
    agent_id    TEXT NOT NULL DEFAULT '',     -- 提炼所依据的 Agent 身份（memory_turns.agent_id）
    task_type   TEXT NOT NULL,                -- 8 值词表中的取值（fix_bug / …）
    task_label  TEXT NOT NULL,                -- 中文标签（缺陷修复 / …）
    turns       BIGINT NOT NULL DEFAULT 0,    -- 提炼时该类对话的 turn 数
    skill_id    TEXT NOT NULL,                -- 提议的技能 id（供 SkillLibrary.save 用）
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    prompt      TEXT NOT NULL,
    tools       TEXT NOT NULL DEFAULT '[]',   -- JSON 数组字符串
    status      TEXT NOT NULL,                -- PENDING | ADOPTED | DISMISSED
    digest_hash TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ
);
-- 口径变更（2026-10-08）：技能提议从「全局 task_type」改为「Agent × task_type」。
-- 旧行没有 agent_id、无法归属任何 Agent，且可由新一轮「重新提炼」在各自的 Agent 下重新生成；
-- 故先补列、再把无主的旧行清掉，避免留着与现口径对不上的陈旧提议（首启清一次，之后无副作用）。
ALTER TABLE IF EXISTS memory_skill_proposals ADD COLUMN IF NOT EXISTS agent_id TEXT NOT NULL DEFAULT '';
DELETE FROM memory_skill_proposals WHERE agent_id = '';
CREATE INDEX IF NOT EXISTS idx_msp_status ON memory_skill_proposals(status);
CREATE INDEX IF NOT EXISTS idx_msp_agent  ON memory_skill_proposals(agent_id, status);

-- ---------------------------------------------------------------------------
-- 每类「本轮跑成什么样」的记账（断点续跑的地基）
--
-- 为什么另起一张表而不塞进 memory_skill_proposals：提议表是「给人看的待办」，
-- 只有真提炼出技能才该有一行；而「判过、无技能」的类（quiet）不该出现在人面清单里。
-- 但它同样是「已完成的劳动」——不记账，下一轮就会为同一份证据再问一次模型，
-- 退出即白跑（正是「不重复劳动」要避免的）。
--
-- outcome 三态：
--   QUIET   = 判过、这类没有完整闭环技能（证据未变则下轮不再送 LLM）；
--   FAILED  = 超时 / 解析失败 / 异常（**下轮必须重试**，故指纹相同也不跳过）；
--   OFFERED = 提炼出并落了提议（与 memory_skill_proposals 呼应，留作「上轮结果」读数）。
-- 只 QUIET 参与「证据未变则跳过」——把失败也跳过，等于把错误固化下来。
--
-- 与提议表同规矩：指纹 = sha256(agent_id + task_type + 摘要)（SkillDiscoverer 计算）。
-- 一行一「Agent × 一类」（PRIMARY KEY），每轮 upsert，只留最近一次。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_class_runs (
    agent_id    TEXT NOT NULL,
    task_type   TEXT NOT NULL,
    digest_hash TEXT NOT NULL,
    outcome     TEXT NOT NULL,             -- QUIET | FAILED | OFFERED
    turns       BIGINT NOT NULL DEFAULT 0,
    reason      TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, task_type)
);

-- ---------------------------------------------------------------------------
-- 技能版本号（B18.1：已采纳技能随证据演进）
--
-- 为什么提议表要记版本号：store() 从前对 ADOPTED 直接早退，已采纳的技能**永远不会**再被
-- 重新提炼——证据长了、做法变了，库里那份 yaml 还是老的。要让它能演进，提议行就得知道自己
-- 现在是第几版；指纹变了、证据确有增长时写 v+1，旧版留档（memory_skill_versions）。
--
-- 只加一列而不新造一张「当前版本」表：提议表本就是「一格一类（Agent × task_type）的当前态」，
-- 当前版本号是它自身的属性，外挂一张表只会多一处要同步的地方（同 memory_turns 的 task_type 列）。
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS memory_skill_proposals ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

-- ---------------------------------------------------------------------------
-- 提炼思考历程（L6 反思路）：这个技能「凭什么」被提炼出来
--
-- 存一份 JSON：模型自述（patterns 规律 / preferences 用户偏好 / keyPoints 关键判断点 / evidence 一句依据）
-- 与确定性证据（sessions 依据的会话、samples 会话代表原话）分列——前者仅供理解、后者可逐条核对，
-- 人据此判断「提炼对不对」，从而决定要不要用（与 A2「模型自述不能当标签」一致：自述不作判据）。
--
-- 可空：旧行没有这份心路，卡片上就不显示「提炼思考历程」入口，行为与从前一致。
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS memory_skill_proposals ADD COLUMN IF NOT EXISTS reasoning TEXT;

-- ---------------------------------------------------------------------------
-- 版本内容快照（技能版本链）
--
-- 为什么不复用提议表存历史版本：提议表的主键是 proposal_id（一格一类，一行只留当前态），
-- 旧版本写进去会顶掉当前行。故另起一张只增不改的快照表，把每个版本的「内容」钉住。
--
-- 与 memory_skill_events 的分工（同 memory_rules vs memory_rule_evals）：
--   · versions = 内容快照（这份 vN 的 name / prompt / tools 到底是什么）；
--   · events   = 动作流水（谁在何时做了什么：提炼出 vN、被采纳、被判重复）。
-- 只有 events 时答不出「v2 的 prompt 长什么样」，只有 versions 时答不出「谁什么时候采纳的」。
--
-- status 三态：PENDING（当前待采纳）/ ADOPTED（当前已采纳）/ SUPERSEDED（被新版顶下去的旧版）。
-- skill_id 从 v1 起**钉死**：文件名即身份（SkillLibrary 落 <skill_id>.yaml），换 id 会留孤儿 yaml，
-- 故版本演进只换内容、不换 id（B18.1 定案）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_versions (
    proposal_id TEXT   NOT NULL,
    version     INT    NOT NULL,
    agent_id    TEXT   NOT NULL DEFAULT '',
    task_type   TEXT   NOT NULL,
    skill_id    TEXT   NOT NULL,
    name        TEXT   NOT NULL,
    description TEXT   NOT NULL,
    prompt      TEXT   NOT NULL,
    tools       TEXT   NOT NULL DEFAULT '[]',  -- JSON 数组字符串（与提议表同口径）
    digest_hash TEXT   NOT NULL,
    status      TEXT   NOT NULL,               -- PENDING | ADOPTED | SUPERSEDED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (proposal_id, version)
);
CREATE INDEX IF NOT EXISTS idx_msv_agent ON memory_skill_versions(agent_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 技能事件流水（操作历史 + 历史视图，合一张只增不改的表）
--
-- 为什么一张表同时记「人的操作」和「系统的提炼轮次」：两者答的是同一个问题——
-- 「谁在何时、对哪一格、做了什么、为什么」，只是 actor 不同（HUMAN / SYSTEM）。
-- 拆两张表只会让「按时间倒序看这一格发生过什么」要 union 两处，且两处字段高度重叠。
--
-- kind 取值（与 class_runs 的 outcome 不冲突，是更细的一层）：
--   OFFER  = 本轮提炼出并落了提议（含版本升级）；
--   QUIET  = 本轮判过、无完整技能（证据未变则不重复送 LLM）；
--   FAILED = 超时 / 解析失败 / 异常（下轮必须重试）；
--   SKIP   = 因指纹未变被跳过（不白花一次 LLM 调用）；
--   ADOPT  = 人采纳；DISMISS = 人不用（两者 actor=HUMAN）；
--   REVISE = 内容变化 + 证据增长，写了新版本（actor=SYSTEM）；
--   DEDUP  = 硬重复自动去重（同 Agent 同 skill_id，保留 turns 大者，弱者作废）。
--
-- 与 memory_skill_class_runs 的分工：class_runs 是**覆盖式**的断点续跑缓存（一格一行、只留最近一次，
-- 用来判「这轮要不要跳过」）；events 是**只增不改**的流水（每轮都追加一行，用来答「历史上发生过什么」）。
-- 前者可被覆盖、后者不可——正如 memory_rules（当前态）vs memory_rule_evals（历史读数）。
--
-- proposal_id 可空：事实/技能都带 proposal_id，但未来若有「不属于任何一格」的系统事件（如全局去重扫描）
-- 也可落这里，不强绑。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_events (
    event_id    BIGSERIAL PRIMARY KEY,
    proposal_id TEXT,
    agent_id    TEXT NOT NULL DEFAULT '',
    task_type   TEXT,
    kind        TEXT NOT NULL,             -- OFFER | QUIET | FAILED | SKIP | ADOPT | DISMISS | REVISE | DEDUP
    actor       TEXT NOT NULL,             -- HUMAN | SYSTEM
    version     INT,
    digest_hash TEXT,
    detail      TEXT,                      -- 一句话说明（给人看：为什么这么做）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mse_agent    ON memory_skill_events(agent_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mse_proposal ON memory_skill_events(proposal_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- memory_skill_loads（技能装载回流）已迁定 owner 为 mo-server（架构重构 M3 / 步骤 15）：
-- 装载事实只产生在 mo-server，DDL 随之收敛到 mo-server 的 schema.sql；本模块只读（SkillLoadRepository）。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 提炼运行态（P1-3）：一轮提炼的生命周期，落库以支持「进度可恢复 / 上轮被中断」
--
-- 为什么另起一张表而不扩 memory_skill_class_runs：那张表是**一格一类的覆盖式缓存**
-- （PK agent_id+task_type，一行只留该类最近一次结果，专门用于判「下轮跳不跳过」）；
-- 本表是**一次运行的生命周期**（一格一 Agent × 一次运行：开始 / 进度 / 收尾）。
-- 粒度（类 vs 次）与生命周期（覆盖 vs 只增）都不同，合并会冲掉「上轮该类结果」这条断点续跑的地基。
--
-- 为什么进度非要落库不可：原先的进度只活在进程内 ConcurrentHashMap 里，重启即全丢——
-- 页面刷新后一切归零，人只看到「什么都没发生过」，答不出「上轮是不是跑了一半就断了」。
-- 落表后，「上一轮跑到哪、是不是被中断」在重启后仍读得到，页面据此如实提示「上轮被中断，可重跑」。
--
-- 为什么**不**把运行态也做进内存互斥：互斥仍由进程内的 Progress 承担（单实例前提，见 SkillDiscoverer）。
-- 本表只做「进度与终态的落地」，不做锁——拿它当互斥锁需要跨进程租约，本项不做（见边界注释）。
--
-- status 五态：
--   RUNNING     = 正在跑（开跑先落这一行，收尾再改写终态）；
--   DONE        = 正常跑完；
--   CANCELLED   = 人点了取消、类间真停（返回的是局部结果）；
--   HALTED      = 因单轮预算（总时长 / 总 token）或熔断（LLM 连续失败）提前停下（P2）；
--   INTERRUPTED = 被打断（进程重启：启动时把遗留的 RUNNING 一律改写为此态；或者后台线程意外异常退出）。
--
-- 为什么 HALTED 要与 CANCELLED 分开：CANCELLED 的语义是「人点了取消」；预算 / 熔断是系统自停，
-- 且下一轮还会继续（剩余类不丢）。混成一个值，事后就答不出「这轮为什么没跑完」——与 DONE / INTERRUPTED 同一分法。
--
-- 为什么启动要把遗留 RUNNING 标 INTERRUPTED：单实例前提下，内存里的 Progress 会随进程一起消失，
-- 故「库里还写着 RUNNING」只可能来自上一次没跑完的进程——如实改写成 INTERRUPTED 才是真相。
-- 【边界】这一条**只在单实例部署下正确**；多实例时另一个实例的 RUNNING 会被本实例误判为中断，
-- 须换成带租约/心跳的判活（本项不做）。
--
-- done_classes 的累加**必须原子自增**（SET done_classes = done_classes + 1）：并发提炼时多类同时完成，
-- 读改写会丢更新。current_class 只作展示（跑到哪一类），收尾置 NULL。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_runs (
    run_id        TEXT PRIMARY KEY,
    agent_id      TEXT NOT NULL,
    status        TEXT NOT NULL,              -- RUNNING | DONE | CANCELLED | HALTED | INTERRUPTED
    total_classes INT  NOT NULL DEFAULT 0,    -- 本轮要处理的类数（过 min-turns 门槛后的真实数量）
    done_classes  INT  NOT NULL DEFAULT 0,    -- 已完成的类数（原子自增）
    current_class TEXT,                        -- 当前正在处理的类（task_type；空闲 / 收尾为 NULL）
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 按「Agent 看最近一轮」是唯一的读取姿势（页面恢复进度 / 显示上轮是否被中断），故索引带 started_at DESC。
CREATE INDEX IF NOT EXISTS idx_msr_agent ON memory_skill_runs(agent_id, started_at DESC);
