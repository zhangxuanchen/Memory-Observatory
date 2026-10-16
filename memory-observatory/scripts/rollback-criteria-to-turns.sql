-- ===========================================================================
-- 回滚：把判据列从 memory_turn_criteria 搬回 memory_turns（M3 · 步骤 15 的逆向）
--
-- 使用时机：步骤 15 迁移后需回退——无论是否已执行「第 4 段删列」，本脚本都能把 memory_turns
-- 恢复成「判据列齐备且有值、idx_mt_agent_task 在位」的旧形态，供旧版代码继续运行。
--
-- 可反复执行（ADD COLUMN IF NOT EXISTS + 全量回填）。回填以新表为准（新表是迁移后的 source of truth）。
--
-- 用法：psql "$MO_DB" -f scripts/rollback-criteria-to-turns.sql
-- ===========================================================================

BEGIN;

-- 1. 把 8 个判据列加回 memory_turns（与迁移前 mo-server/schema.sql 的定义逐字一致）
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS corrected BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS is_holdout BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS task_type TEXT;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS task_note TEXT;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS task_type_version TEXT;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS classified_at TIMESTAMPTZ;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS task_judge_mode TEXT;
ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS task_judge_votes TEXT;

-- 2. 组合索引恢复（按 Agent × task_type 取该类 turn）
CREATE INDEX IF NOT EXISTS idx_mt_agent_task ON memory_turns (agent_id, task_type);

-- 3. 从 memory_turn_criteria 回填（新表存在才有意义）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_name = 'memory_turn_criteria') THEN
        UPDATE memory_turns t
           SET task_type         = c.task_type,
               task_note         = c.task_note,
               task_type_version = c.task_type_version,
               classified_at     = c.classified_at,
               task_judge_mode   = c.task_judge_mode,
               task_judge_votes  = c.task_judge_votes,
               corrected         = COALESCE(c.corrected, false),
               is_holdout        = COALESCE(c.is_holdout, false)
          FROM memory_turn_criteria c
         WHERE c.turn_id = t.turn_id;
        RAISE NOTICE '已从 memory_turn_criteria 回填 memory_turns 判据列';
    ELSE
        RAISE NOTICE 'memory_turn_criteria 不存在，仅恢复列与索引';
    END IF;
END $$;

COMMIT;