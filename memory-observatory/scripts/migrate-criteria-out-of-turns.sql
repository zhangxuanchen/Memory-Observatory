-- ===========================================================================
-- 迁移：判据列迁出 memory_turns（架构重构 M3 · 步骤 15，治 R2）
--
-- 把 memory_turns 上由判据侧（mo-exoskeleton）回写的判据列
--   is_holdout / corrected / task_type / task_note / task_type_version /
--   classified_at / task_judge_mode / task_judge_votes
-- 迁到外骨骼自有表 memory_turn_criteria（以 turn_id 关联），使 memory_turns 彻底单写。
--
-- 执行顺序（对齐蓝图 §6.3「步骤 7 与 15」——不可一步到位）：
--   0) 备份：  pg_dump -t memory_turns -t memory_turn_criteria "$MO_DB" > backup.sql
--   1) 建表 + 回填      → 本文件「第 1、2 段」（只增不改，可反复重跑）
--   2) 数据保全检查      → 本文件「第 3 段」，期望 0 行丢失
--   3) 切换读写（部署新代码：外骨骼只读写 memory_turn_criteria）—— 已随镜像完成
--   4) 删列              → 本文件「第 4 段」（破坏性，检查通过后再执行）
--
-- 回滚：scripts/rollback-criteria-to-turns.sql（把判据列加回 memory_turns 并回填）。
-- 用法：psql "$MO_DB" -f scripts/migrate-criteria-out-of-turns.sql
--      （第 4 段默认注释，人工确认第 3 段通过后单独放开执行）
--
-- 稳健性：判据列在目标库中可能**部分存在**（如本机库的 memory_turns 就没有 corrected）。
-- 故第 2、3 段一律经 to_jsonb(t)->>'col' 取值——访问不存在的键返回 NULL、不报错，
-- 从而对「哪些列还在」不做假设（删列前执行与全新库执行都安全）。
-- ===========================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 第 1 段：建表（与 mo-exoskeleton/schema.sql 同定义，保证本脚本可独立执行）
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
-- 第 2 段：回填。只搬「旧列确有判据值」的 turn（task_type 非空 / is_holdout / corrected 为真 /
-- task_note 非空）——避免为未分类的 turn 造出一堆全 NULL 的空行。
-- ON CONFLICT DO NOTHING 保证可重跑、且不覆盖新代码已写入的更新值。
-- 不回写 updated_at（保留首迁时间戳更利于审计）。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    n_before bigint;
    n_after  bigint;
BEGIN
    SELECT count(*) INTO n_before FROM memory_turn_criteria;

    INSERT INTO memory_turn_criteria
        (turn_id, session_id, task_type, task_note, task_type_version, classified_at,
         task_judge_mode, task_judge_votes, corrected, is_holdout)
    SELECT t.turn_id,
           t.session_id,
           t.j->>'task_type',
           t.j->>'task_note',
           t.j->>'task_type_version',
           (t.j->>'classified_at')::timestamptz,
           t.j->>'task_judge_mode',
           t.j->>'task_judge_votes',
           COALESCE((t.j->>'corrected')::boolean, false),
           COALESCE((t.j->>'is_holdout')::boolean, false)
    FROM (SELECT to_jsonb(mt) AS j, mt.turn_id, mt.session_id FROM memory_turns mt) t
    WHERE (t.j->>'task_type') IS NOT NULL
       OR COALESCE((t.j->>'corrected')::boolean, false)
       OR COALESCE((t.j->>'is_holdout')::boolean, false)
       OR COALESCE(t.j->>'task_note', '') <> ''
    ON CONFLICT (turn_id) DO NOTHING;

    SELECT count(*) INTO n_after FROM memory_turn_criteria;
    RAISE NOTICE '[回填] memory_turn_criteria 行数：% → %（新增 % 行）', n_before, n_after, n_after - n_before;
END $$;

COMMIT;

-- ---------------------------------------------------------------------------
-- 第 3 段：数据保全检查（只读）。语义 =「旧列的每个非空判据值，都必须已在新表里」——
-- 一旦有「旧有值、新表缺值或值不同」的 turn 即 RAISE EXCEPTION 中止，禁止进入第 4 段。
--
-- 为什么不比对「逐列完全相等」：切写后新代码仍会更新判据（如重跑分类改 task_type / 重置 holdout），
-- 新旧必然随时间背离——严格相等会误判。删列的真正前提是「没有旧值被丢」，故只查丢失。
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    n_total   bigint;
    n_loss    bigint;
BEGIN
    SELECT count(*) INTO n_total FROM memory_turns;

    SELECT count(*) INTO n_loss
    FROM (SELECT to_jsonb(mt) AS j, mt.turn_id FROM memory_turns mt) t
    LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
    WHERE ( (t.j->>'task_type') IS NOT NULL
              AND c.task_type IS DISTINCT FROM (t.j->>'task_type') )
       OR ( COALESCE((t.j->>'is_holdout')::boolean, false)
              AND c.is_holdout IS DISTINCT FROM (t.j->>'is_holdout')::boolean )
       OR ( COALESCE((t.j->>'corrected')::boolean, false)
              AND c.corrected IS DISTINCT FROM (t.j->>'corrected')::boolean )
       OR ( COALESCE(t.j->>'task_note', '') <> ''
              AND c.task_note IS DISTINCT FROM (t.j->>'task_note') )
       OR ( (t.j->>'task_type_version') IS NOT NULL
              AND c.task_type_version IS DISTINCT FROM (t.j->>'task_type_version') )
       OR ( (t.j->>'classified_at') IS NOT NULL
              AND c.classified_at IS DISTINCT FROM (t.j->>'classified_at')::timestamptz )
       OR ( (t.j->>'task_judge_mode') IS NOT NULL
              AND c.task_judge_mode IS DISTINCT FROM (t.j->>'task_judge_mode') )
       OR ( (t.j->>'task_judge_votes') IS NOT NULL
              AND c.task_judge_votes IS DISTINCT FROM (t.j->>'task_judge_votes') );

    RAISE NOTICE '[数据保全] memory_turns 共 % 行，旧判据值未落入新表/不一致 % 行（应为 0）', n_total, n_loss;
    IF n_loss > 0 THEN
        RAISE EXCEPTION '数据保全检查未通过 % 行——禁止删列，请先排查', n_loss;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 第 4 段：删列（破坏性）。默认不执行——保全检查通过、且新代码（只读写 memory_turn_criteria）
-- 已部署后，人工去掉下面整段的注释再单独执行。
-- 列名可能不全在（如本机库无 corrected），DROP COLUMN IF EXISTS 对缺失列无害。
-- ---------------------------------------------------------------------------
-- BEGIN;
-- DROP INDEX IF EXISTS idx_mt_agent_task;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS is_holdout;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS corrected;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS task_type;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS task_note;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS task_type_version;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS classified_at;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS task_judge_mode;
-- ALTER TABLE memory_turns DROP COLUMN IF EXISTS task_judge_votes;
-- COMMIT;
