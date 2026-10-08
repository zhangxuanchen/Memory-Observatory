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
-- 判据列：holdout 分组标记（§2.5 / §4.6）
--
-- 落在 memory_turns 上，但由本模块（判据侧）回写：TurnMaterializer 的 upsert 不碰这一列，
-- 故物化重跑不会覆盖判定结果。corrected 列（§2.4）已由 mo-server 建表时留出，同样留给判据侧。
--
-- 用 ALTER 而非改 mo-server 的建表语句：schema 归模块自持（E1），本模块只「增列」不改别人的表定义。
-- memory_turns 不存在时 ALTER TABLE IF EXISTS 只发 notice 不报错，配合 continue-on-error 不阻塞启动。
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS memory_turns
    ADD COLUMN IF NOT EXISTS is_holdout BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- 任务类型（二级筛选维度，session 粒度，§4.4 之外的另一维）
--
-- 由分类侧回写（TaskClassifier），TurnMaterializer 的 upsert 不碰这两列，
-- 故物化重跑不会覆盖分类结果——与 is_holdout 同一保证。
--
-- NULL 与 'other' 刻意分开：NULL = 没量到（尚未跑到 / LLM 不可达 / 解析失败），
-- 'other' = 量到了、确实归不进任何一类。与 shape 的 unknown vs none 同一逻辑。
-- 不加索引：筛选是全表扫后 Java 过滤（与 support()/loadTurns() 同形态），几千行无收益。
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS memory_turns ADD COLUMN IF NOT EXISTS task_type TEXT;
ALTER TABLE IF EXISTS memory_turns ADD COLUMN IF NOT EXISTS task_note TEXT;

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
