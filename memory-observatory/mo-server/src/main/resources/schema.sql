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

-- TimescaleDB 转换：启用扩展 + 转 hypertable，全部失败在此吞掉，普通 PG 不受影响
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb;
    PERFORM create_hypertable('memory_events', 'ts', if_not_exists => true);
    PERFORM create_hypertable('memory_snapshots', 'ts', if_not_exists => true);
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'TimescaleDB 跳过（普通 PG 走普通表）: %', SQLERRM;
END $$;
