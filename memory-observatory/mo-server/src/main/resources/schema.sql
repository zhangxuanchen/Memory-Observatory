-- Memory Observatory 数据模型
-- 表先建为普通表；TimescaleDB 镜像可在下面 DO 块里转 hypertable，普通 PG 跳过。
-- 注意：CREATE EXTENSION 不能直接放顶层（普通 PG 无该扩展会 ERROR，导致连接事务
-- abort、后续语句失败、连接关闭）。放进 DO 块的 EXCEPTION 里吞掉。

-- 记忆操作事件表
CREATE TABLE IF NOT EXISTS memory_events (
    event_id        TEXT PRIMARY KEY,      -- span_id（16 hex chars，与 Trace 契约对齐）
    agent_id        TEXT NOT NULL,
    session_id      TEXT NOT NULL,
    operation       TEXT NOT NULL,        -- READ/WRITE/UPDATE/EXPIRE
    layer           TEXT NOT NULL,        -- prompt/session/skill/provider
    memory_key      TEXT,
    memory_summary  TEXT,                 -- 前 200 字预览，不含原始数据
    token_count     INTEGER NOT NULL,
    latency_ms      DOUBLE PRECISION,
    ts              TIMESTAMPTZ NOT NULL,
    metadata        JSONB,
    -- Trace 字段（input-contracts §6，一个 session = 一个 trace）
    trace_id        VARCHAR(32),          -- 32 hex chars（UUID4 去连字符）
    parent_span_id  VARCHAR(16)           -- 父 span_id，Turn Root Span 为 NULL
);
CREATE INDEX IF NOT EXISTS idx_me_session ON memory_events(session_id, ts);
CREATE INDEX IF NOT EXISTS idx_me_agent   ON memory_events(agent_id, ts);
CREATE INDEX IF NOT EXISTS idx_me_op      ON memory_events(operation, ts);
CREATE INDEX IF NOT EXISTS idx_me_trace   ON memory_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_me_parent  ON memory_events(parent_span_id);

-- 在线升级列（防止已经跑过旧 schema 的库缺列）
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS trace_id       VARCHAR(32);
ALTER TABLE memory_events ADD COLUMN IF NOT EXISTS parent_span_id VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_me_trace  ON memory_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_me_parent ON memory_events(parent_span_id);

-- metadata 的 GIN 索引（《分类记忆体》§4.2 零期第二件）。
-- 为什么要它：turn_* 全在 metadata 里，现有查询走 metadata->>'k' ILIKE，等于每次全表扫。
-- 用 jsonb_path_ops 而不是默认 jsonb_ops：体积更小、@> / ? 类查询更快，代价是不支持键存在性单独查询，
-- 而这里只按 key 取等值，不需要那个能力。
CREATE INDEX IF NOT EXISTS idx_me_metadata ON memory_events USING gin (metadata jsonb_path_ops);

-- 五区快照表
CREATE TABLE IF NOT EXISTS memory_snapshots (
    snapshot_id         TEXT PRIMARY KEY,
    agent_id            TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    total_tokens        INTEGER,
    system_tokens       INTEGER,
    task_tokens         INTEGER,
    memory_tokens       INTEGER,
    tool_history_tokens INTEGER,
    free_tokens         INTEGER,
    compression_count   INTEGER,
    last_compression_ratio DOUBLE PRECISION,
    ts                  TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ms_session ON memory_snapshots(session_id, ts);

-- 已导入 .log 文件指纹（文件夹 .log 导入的 MD5 去重：同内容文件不重复抽取）
CREATE TABLE IF NOT EXISTS import_log_files (
    md5          VARCHAR(32) PRIMARY KEY,
    file_name    TEXT,
    llm          BOOLEAN NOT NULL DEFAULT TRUE,
    total        INTEGER NOT NULL DEFAULT 0,
    inserted     INTEGER NOT NULL DEFAULT 0,
    skipped      INTEGER NOT NULL DEFAULT 0,
    error_count  INTEGER NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 请求用量记录（Excel 导入：RequestID/积分消耗/User Prompt/模型/客户端/时间）
-- 不对 request_id 加唯一约束：同 RequestID 出现多条本身就是「重复扣费」异常证据，需保留可查；
-- 防重复导入靠 import_usage_files 的文件 MD5 指纹。
CREATE TABLE IF NOT EXISTS request_usage (
    id            BIGSERIAL PRIMARY KEY,
    request_id    TEXT NOT NULL,
    credits       DOUBLE PRECISION NOT NULL,
    prompt        TEXT,
    model         TEXT,
    client        TEXT,
    request_time  TIMESTAMPTZ NOT NULL,
    file_name     TEXT,
    imported_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ru_time  ON request_usage(request_time);
CREATE INDEX IF NOT EXISTS idx_ru_reqid ON request_usage(request_id);
CREATE INDEX IF NOT EXISTS idx_ru_model ON request_usage(model);

-- 已导入用量 Excel 的 MD5 指纹（同内容文件不重复导入）
CREATE TABLE IF NOT EXISTS import_usage_files (
    md5          VARCHAR(32) PRIMARY KEY,
    file_name    TEXT,
    total        INTEGER NOT NULL DEFAULT 0,
    inserted     INTEGER NOT NULL DEFAULT 0,
    skipped      INTEGER NOT NULL DEFAULT 0,
    error_count  INTEGER NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 告警推送状态（L4 闭环第一步：去重、冷却、恢复）
-- dedup_key 是主键，保证同一个问题在冷却窗口内只推一次；进程重启后不会被全部重推。
-- payload 只存已脱敏内容：风险命中在 RiskScanService 内已把密钥替换为掩码，此处不含原文。
CREATE TABLE IF NOT EXISTS alert_notifications (
    dedup_key      TEXT PRIMARY KEY,
    source         TEXT NOT NULL,                   -- threshold|flow|risk
    agent_id       TEXT NOT NULL,
    severity       TEXT NOT NULL,                   -- critical|high|medium
    title          TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'active',  -- active|recovered
    payload        JSONB,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_pushed_at TIMESTAMPTZ,
    push_count     INTEGER NOT NULL DEFAULT 0,
    recovered_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_an_status ON alert_notifications(status, last_seen_at);
CREATE INDEX IF NOT EXISTS idx_an_agent  ON alert_notifications(agent_id, last_seen_at);

-- 接入来源家族清单（统一接入层 mo_ingest 的服务端归类）
-- 把「某 source 归到哪个家族」从 Java 硬编码挪到一张可运行时增行的表：
-- 新增平台只需 INSERT 一行（source, family），不必改 Java、不必重建镜像——
-- 因此也不能放 application.yml（它在 jar 内，改它必须重建镜像）。
-- family 取值：import | hook | workbench | otlp（下游按四类消费，保持兼容）。
CREATE TABLE IF NOT EXISTS event_source_family (
    source      TEXT PRIMARY KEY,
    family      TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO event_source_family(source, family) VALUES
    ('import', 'import'),
    ('trae_importer', 'import'),
    ('workbuddy_importer', 'import'),
    ('qoder_hook', 'hook'),
    ('trae_hook', 'hook')
ON CONFLICT (source) DO NOTHING;

-- turn 特征物化表：分类器的输入面（《分类记忆体：设计与实现》§4.2 零期）
-- 为什么要单独一张表：turn_* 只在工作台路径产生，且 memory_events 上没有 metadata 的 GIN 索引，
-- 按 jsonb 现算特征等于每次全表扫。把 turn 级特征一次性算好落在这张表上，之后所有分析都查它。
-- 本文件只建表；物化本身（从 memory_events 回填 / 增量）不在 schema 里做。
CREATE TABLE IF NOT EXISTS memory_turns (
    turn_id      TEXT PRIMARY KEY,                 -- 取自 metadata.turn_message_id
    agent_id     TEXT,
    session_id   TEXT NOT NULL,
    trace_id     TEXT,
    user_text    TEXT,                             -- metadata.turn_user，写入侧截断到 500 字符
    user_text_trgm TEXT GENERATED ALWAYS AS (lower(user_text)) STORED,
    action_seq   TEXT[],                           -- 工具序列，取自 metadata.turn_actions
    layer_mix    JSONB,                            -- 各 layer 事件数
    op_mix       JSONB,                            -- 各 operation 占比
    io_kind_mix  JSONB,                            -- 各 io_kind 事件数（B4：事件性质，供 §4.4 分桶取「只读未写」）
    event_count  INT,
    token_total  BIGINT,
    latency_ms   BIGINT,
    outcome      TEXT,                             -- metadata.turn_outcome
    source       TEXT NOT NULL,                    -- workbench | otlp | import | hook（家族，来自 event_source_family）
    platform     TEXT,                             -- 平台原始 source 值（qoder_hook / trae_importer / …），供按平台切分
    started_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_mt_agent_ts ON memory_turns (agent_id, started_at);

-- 在线升级列（防止已经跑过旧 schema 的库缺列）。io_kind_mix / platform 都是物化自己的产物
-- （由 TurnMaterializer 写），与判据列无关——判据列已于步骤 15 迁出本表（见下）。
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS io_kind_mix JSONB;
-- platform：把平台名落到分析入口表，按平台分析不必回 join memory_events。
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS platform TEXT;

-- ---------------------------------------------------------------------------
-- 判据列已迁出本表（架构重构 M3 / 步骤 15，治 R2：memory_turns 双写）
--
-- 原先 is_holdout / corrected / task_type / task_note / task_type_version / classified_at /
-- task_judge_mode / task_judge_votes 这 8 列挂在 memory_turns 上、由判据侧（mo-exoskeleton）UPDATE，
-- 使本表有两个 writer。步骤 15 把它们迁到外骨骼自己的表 memory_turn_criteria（以 turn_id 关联），
-- 本表从此**彻底单写**：只有 TurnMaterializer 写它（见 turn/TurnMaterializer.java）。
--
-- 因此这里不再 CREATE / ALTER 这些列。对**既有库**，这 8 列仍物理存在（CREATE TABLE IF NOT EXISTS
-- 不会删列），由迁移脚本 scripts/migrate-criteria-out-of-turns.sql 负责回填并删列；
-- 回滚脚本 scripts/rollback-criteria-to-turns.sql 负责反向恢复。不能用 schema.sql 做 DROP——
-- 那会在每次启动时执行一次破坏性操作。详见《架构重构-增量与目标蓝图》§6.3 步骤 15。
--
-- 组合索引 idx_mt_agent_task (agent_id, task_type) 随之失效（task_type 已不在本表），
-- 由迁移脚本删除；按类取原文改走 memory_turn_criteria(task_type) 的索引。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 技能装载回流（P0-1）：记录「哪个 Agent 在装配时装载了哪个已采纳技能」
--
-- 本表迁定 owner 为 **mo-server**（架构重构 M3 / 步骤 15）：装载事实只产生在 mo-server
-- （只有它读技能库并装配 Agent），写入走 SkillLoadRepository；外骨骼只读。原先它的 DDL 挂在外骨骼
-- 的 schema.sql、却由 mo-server 直写——「建表方 ≠ 写入方」，正是表所有权矩阵（蓝图 §4.4）要消除的。
-- 故把 DDL 收敛到唯一的 writer 名下。
--
-- 一格一「Agent × 技能」（PRIMARY KEY）：同一对反复装载只累加计数与刷新时间，不插多行。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_skill_loads (
    agent_id        TEXT   NOT NULL,
    skill_id        TEXT   NOT NULL,
    workspace_id    TEXT,
    load_count      BIGINT NOT NULL DEFAULT 0,
    first_loaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_loaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_session_id TEXT,
    PRIMARY KEY (agent_id, skill_id)
);
CREATE INDEX IF NOT EXISTS idx_msl_skill ON memory_skill_loads(skill_id);

-- ---------------------------------------------------------------------------
-- 物化水位持久化（owner=mo-server，唯一写者 TurnMaterializer）：
-- 重启后从库里接水位，不再 EPOCH 全量回看（架构审视弱点 5，2026-10-12）。
-- 水位只是提示不是真相：漏推进/晚到行都由「候选 turn 全量重聚合 + turn_id 幂等
-- upsert」兑底，重跑结果一致。pipeline 主键预留多条物化线（目前仅 turns）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS memory_materialize_watermark (
    pipeline     TEXT PRIMARY KEY,
    watermark_ts TIMESTAMPTZ NOT NULL,
    updated_ts   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- pg_trgm 服务相似度匹配（§4.2.1：优先于 pgvector——不需要外部调用、有索引、现在就能用）。
-- 缺扩展时降级而不是启动失败：普通 PG 无该扩展会 ERROR 并 abort 事务，故放进 DO 块的 EXCEPTION 里吞掉。
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_trgm 跳过（无扩展）: %', SQLERRM;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_mt_trgm ON memory_turns USING gin (user_text_trgm gin_trgm_ops);
    ELSE
        RAISE NOTICE 'pg_trgm 未启用，跳过 idx_mt_trgm';
    END IF;
END $$;

-- TimescaleDB 转换：启用扩展 + 转 hypertable，全部失败在此吞掉，普通 PG 不受影响
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb;
    PERFORM create_hypertable('memory_events', 'ts', if_not_exists => true);
    PERFORM create_hypertable('memory_snapshots', 'ts', if_not_exists => true);
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'TimescaleDB 跳过（普通 PG 走普通表）: %', SQLERRM;
END $$;
