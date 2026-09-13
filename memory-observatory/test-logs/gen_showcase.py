#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成宣传级状态丰富的测试数据 events-showcase-20260913.log（NDJSON，每行一个事件）。

覆盖场景（3 Agent / 6 会话 / 17 Turn / ~110 事件）：
- 问题分析 danger：skill-repeat / skill-error / tool-token / turn-token / session-token
- 问题分析 warn：skill-slow / turn-count / turn-tool / turn-latency / model-slow / mem-churn
- 内容风险 critical：云 AK(LTAI) / API Key(sk-) / GitHub Token(ghp_)
- 内容风险 high：明文密码 / 数据库连接串 / Bearer+JWT
- 内容风险 medium：api_key = 赋值
- 流程分析信号：S1 烧钱(model 节点) / S2 慢(sql_query) / S3 循环(web_search) / S7 失败(code_lint) / 遗忘风暴(EXPIRE)
- 事件详情：Turn 卡 + 操作步骤 + 热力图（turn_message_id 语义）
- Token 分析：Top 会话 / 操作×层矩阵 / 延迟分位 / Top 记忆键 / 日趋势（跨 3 天）

运行：python3 gen_showcase.py   （输出到同目录 events-showcase-20260913.log）
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "events-showcase-20260913.log")

events = []
_seq = [0]


def add(ts, agent, session, op, layer, key, summary, token, latency,
        turn=None, aidx=None, status=None, error=None,
        trace=None, parent=None, turn_user=None, turn_outcome=None, action_count=None):
    """追加一条事件。turn=turn_message_id；主事件不带 aidx，action 带 aidx。"""
    _seq[0] += 1
    eid = "evt-show-%04d" % _seq[0]
    meta = {"source": "demo"}
    if turn:
        meta["turn_message_id"] = turn
    if aidx is not None:
        meta["action_idx"] = aidx
    if turn_user is not None:
        meta["turn_user"] = turn_user
    if turn_outcome is not None:
        meta["turn_outcome"] = turn_outcome
    if action_count is not None:
        meta["action_count"] = action_count
    if status:
        meta["status"] = status
    if error:
        meta["error"] = error
    events.append({
        "event_id": eid,
        "agent_id": agent,
        "session_id": session,
        "operation": op,
        "layer": layer,
        "memory_key": key,
        "memory_summary": summary,
        "token_count": token,
        "latency_ms": latency,
        "timestamp": ts,
        "trace_id": trace or ("trace-" + turn if turn else None),
        "parent_span_id": parent,
        "metadata": meta,
    })


def sec(base, offset):
    """'2026-09-11 09:30:00' + offset 秒 → 字符串（北京时间）。"""
    import datetime
    t = datetime.datetime.strptime(base, "%Y-%m-%d %H:%M:%S") + datetime.timedelta(seconds=offset)
    return t.strftime("%Y-%m-%d %H:%M:%S")


# =====================================================================
# Agent 1: memory-copilot（主力记忆助手）
# =====================================================================
A1 = "memory-copilot"

# ---------- 会话 1：sess-copilot-weekly（09-11，4 turns，健康 + 写入抖动素材） ----------
S1 = "sess-copilot-weekly"

# turn-w-001：正常回合（READ@prompt + READ@model + WRITE@session）
add(sec("2026-09-11 09:30:00", 0), A1, S1, "write", "session", "MEMORY.md",
    "周报助手启动：写入本周记忆索引与摘要骨架", 420, 45.0, turn="turn-w-001",
    trace="trace-w-001", turn_user="帮我总结一下本周的记忆写入情况，生成周报",
    turn_outcome="已生成周报骨架 312 字", action_count=3)
add(sec("2026-09-11 09:30:00", 3), A1, S1, "read", "prompt", "system_prompt",
    "读取系统提示词模板（周报场景）", 850, 12.0, turn="turn-w-001", aidx=1, parent="evt-show-0001")
add(sec("2026-09-11 09:30:00", 6), A1, S1, "read", "model", "model:qwen3-max",
    "调用模型生成周报摘要", 1400, 1450.0, turn="turn-w-001", aidx=2, parent="evt-show-0001")
add(sec("2026-09-11 09:30:00", 9), A1, S1, "write", "session", "session_summary",
    "保存周报摘要到会话记忆", 310, 28.0, turn="turn-w-001", aidx=3, parent="evt-show-0001")

# turn-w-002：正常回合（读取 + 更新画像，开始铺垫 user_profile 写入）
add(sec("2026-09-11 09:33:10", 0), A1, S1, "write", "session", "MEMORY.md",
    "第二回合：合并昨日待办并回写主索引", 380, 40.0, turn="turn-w-002",
    trace="trace-w-002", turn_user="把昨天的三条待办合并进周报",
    turn_outcome="待办已合并，主索引更新完成", action_count=3)
add(sec("2026-09-11 09:33:10", 4), A1, S1, "read", "session", "user_profile",
    "读取用户画像（偏好周报格式）", 220, 9.0, turn="turn-w-002", aidx=1, parent="evt-show-0005")
add(sec("2026-09-11 09:33:10", 8), A1, S1, "update", "session", "user_profile",
    "更新画像：本周报偏好含图表链接", 340, 22.0, turn="turn-w-002", aidx=2, parent="evt-show-0005")
add(sec("2026-09-11 09:33:10", 12), A1, S1, "read", "model", "model:qwen3-max",
    "调用模型改写周报段落", 1500, 1500.0, turn="turn-w-002", aidx=3, parent="evt-show-0005")

# turn-w-003：写入抖动素材（同一回合内 user_profile 反复 UPDATE ×4）
add(sec("2026-09-11 09:36:20", 0), A1, S1, "write", "session", "MEMORY.md",
    "第三回合：按反馈逐项微调画像字段（暴露写放大）", 300, 38.0, turn="turn-w-003",
    trace="trace-w-003", turn_user="汇报格式改成三段式，图表放附录",
    turn_outcome="画像字段已按反馈调整", action_count=5)
for i in range(4):
    add(sec("2026-09-11 09:36:20", 4 + i * 4), A1, S1, "update", "session", "user_profile",
        "画像微调第 %d 次：周报段落模板字段更新" % (i + 1), 330 + i * 10, 18.0,
        turn="turn-w-003", aidx=i + 1, parent="evt-show-0009")
add(sec("2026-09-11 09:36:20", 22), A1, S1, "read", "model", "model:qwen3-max",
    "调用模型确认三段式结构", 1300, 1300.0, turn="turn-w-003", aidx=5, parent="evt-show-0009")

# turn-w-004：收尾回合
add(sec("2026-09-11 09:40:30", 0), A1, S1, "write", "session", "MEMORY.md",
    "周报完成：归档本周会话并写入收尾摘要", 400, 42.0, turn="turn-w-004",
    trace="trace-w-004", turn_user="收尾，把周报归档",
    turn_outcome="周报已归档，会话摘要已写入", action_count=2)
add(sec("2026-09-11 09:40:30", 5), A1, S1, "write", "session", "fact_weekly_report",
    "写入事实记忆：本周报发送时间是每周五 17:00", 280, 20.0, turn="turn-w-004", aidx=1, parent="evt-show-0014")
add(sec("2026-09-11 09:40:30", 10), A1, S1, "read", "model", "model:qwen3-max",
    "调用模型生成归档确认语", 1200, 1250.0, turn="turn-w-004", aidx=2, parent="evt-show-0014")

# ---------- 会话 2：sess-copilot-billing（09-12，3 turns，高消耗集中营） ----------
S2 = "sess-copilot-billing"

# turn-b-001：turn-token danger（单回合 >5000）
add(sec("2026-09-12 14:00:00", 0), A1, S2, "write", "session", "MEMORY.md",
    "账单对账启动：一次性载入 9 月账单全量上下文并写入对账计划", 6800, 120.0, turn="turn-b-001",
    trace="trace-b-001", turn_user="对一下 9 月的 API 账单，找出异常增量",
    turn_outcome="对账计划已写入，初步圈出 3 个可疑服务", action_count=5)
add(sec("2026-09-12 14:00:00", 6), A1, S2, "read", "model", "model:qwen3-max",
    "调用模型分析账单增量（长上下文推理）", 22000, 11000.0, turn="turn-b-001", aidx=1, parent="evt-show-0018")
add(sec("2026-09-12 14:00:00", 22), A1, S2, "read", "provider", "provider:qwen-billing-api",
    "拉取 9 月账单明细（大分页）", 1400, 2100.0, turn="turn-b-001", aidx=2, parent="evt-show-0018")
add(sec("2026-09-12 14:00:00", 30), A1, S2, "read", "provider", "provider:qwen-billing-api",
    "拉取可疑服务调用日志", 1200, 1800.0, turn="turn-b-001", aidx=3, parent="evt-show-0018")
add(sec("2026-09-12 14:00:00", 38), A1, S2, "read", "skill", "tool:sql_query",
    "执行对账 SQL：按服务聚合月度消费", 800, 900.0, turn="turn-b-001", aidx=4, parent="evt-show-0018")
add(sec("2026-09-12 14:00:00", 45), A1, S2, "read", "prompt", "task_plan",
    "读取对账任务计划模板", 600, 10.0, turn="turn-b-001", aidx=5, parent="evt-show-0018")

# turn-b-002：turn-latency（墙钟 95s）+ turn-tool（provider ×10 > 8）+ model-slow 素材
add(sec("2026-09-12 14:05:00", 0), A1, S2, "write", "session", "MEMORY.md",
    "第二回合：逐服务核对调用日志（外部接口连环拉取，回合耗时超 90 秒）", 950, 85.0, turn="turn-b-002",
    trace="trace-b-002", turn_user="把三个可疑服务的日志都拉出来核对",
    turn_outcome="确认 image-render 服务因重试风暴增量 42%", action_count=12)
for i in range(10):
    add(sec("2026-09-12 14:05:00", 5 + i * 9), A1, S2, "read", "provider", "provider:qwen-billing-api",
        "分页拉取服务调用日志第 %d 页（每页约 50 条）" % (i + 1), 1050 + i * 80, 1600.0 + i * 120,
        turn="turn-b-002", aidx=i + 1, parent="evt-show-0024")
add(sec("2026-09-12 14:05:00", 98), A1, S2, "read", "model", "model:qwen3-max",
    "调用模型归因分析（重试风暴识别）", 25600, 12800.0, turn="turn-b-002", aidx=11, parent="evt-show-0024")
add(sec("2026-09-12 14:05:00", 130), A1, S2, "read", "skill", "tool:sql_query",
    "把核对结果写临时表供下回合引用", 700, 800.0, turn="turn-b-002", aidx=12, parent="evt-show-0024")

# turn-b-003：skill-slow（sql_query 45s）+ model-slow 素材 + mem-churn 收尾（user_profile 累计 >10 次写）
add(sec("2026-09-12 14:12:30", 0), A1, S2, "write", "session", "MEMORY.md",
    "第三回合：生成对账报告；对账大表查询耗时 45 秒（慢调用素材）", 800, 76.0, turn="turn-b-003",
    trace="trace-b-003", turn_user="把结论写成对账报告，存进记忆",
    turn_outcome="报告已生成并写入记忆，附 3 条治理建议", action_count=9)
add(sec("2026-09-12 14:12:30", 4), A1, S2, "read", "skill", "tool:sql_query",
    "执行全表对账汇总 SQL（未走索引，45 秒）", 1500, 45000.0, turn="turn-b-003", aidx=1, parent="evt-show-0038")
add(sec("2026-09-12 14:12:30", 55), A1, S2, "read", "model", "model:qwen3-max",
    "调用模型撰写对账报告正文", 21000, 10200.0, turn="turn-b-003", aidx=2, parent="evt-show-0038")
for i in range(6):
    add(sec("2026-09-12 14:12:30", 70 + i * 5), A1, S2, "update", "session", "user_profile",
        "画像写入：对账偏好字段第 %d 次覆写（报告粒度/服务名单/阈值）" % (i + 1), 320 + i * 15, 16.0,
        turn="turn-b-003", aidx=3 + i, parent="evt-show-0038")
add(sec("2026-09-12 14:12:30", 105), A1, S2, "read", "provider", "provider:qwen-billing-api",
    "拉取报告引用的费率表", 1300, 1400.0, turn="turn-b-003", aidx=9, parent="evt-show-0038")
add(sec("2026-09-12 14:12:30", 112), A1, S2, "read", "model", "model:qwen3-max",
    "调用模型润色报告结论", 19800, 11600.0, turn="turn-b-003", aidx=10, parent="evt-show-0038")

# ---------- 会话 3：sess-copilot-risk（09-13，3 turns：风险内容 + 技能报错 + 遗忘风暴） ----------
S3 = "sess-copilot-risk"

# turn-k-001：critical 风险（turn_user 含连接串；摘要含云 AK / GitHub Token）
add(sec("2026-09-13 10:00:00", 0), A1, S3, "write", "session", "MEMORY.md",
    "运维助手启动：登记本轮运维背景（用户直接把测试库连接串发进对话）", 460, 40.0, turn="turn-k-001",
    trace="trace-k-001",
    turn_user="把测试库连接串记一下：postgres://admin:P@ssw0rd9@db.internal:5432/mo，一会儿配置用",
    turn_outcome="已记录连接串（风险监测应命中 db-conn）", action_count=3)
add(sec("2026-09-13 10:00:00", 5), A1, S3, "write", "session", "fact_backup_cred",
    "备份上传凭证：阿里云 AK LTAI5tDemoKeys012345678（演示假数据）", 300, 15.0,
    turn="turn-k-001", aidx=1, parent="evt-show-0053")
add(sec("2026-09-13 10:00:00", 10), A1, S3, "write", "session", "fact_ci_token",
    "CI 同步令牌：ghp_DemoToken1234567890abcdefghijklmnop（演示假数据）", 300, 15.0,
    turn="turn-k-001", aidx=2, parent="evt-show-0053")
add(sec("2026-09-13 10:00:00", 15), A1, S3, "read", "model", "model:qwen3-max",
    "调用模型解析运维意图", 1600, 1550.0, turn="turn-k-001", aidx=3, parent="evt-show-0053")

# turn-k-002：skill-error（code_lint failed ×2）+ high 风险（password / Bearer+JWT）
add(sec("2026-09-13 10:04:20", 0), A1, S3, "write", "session", "MEMORY.md",
    "第二回合：登记发布检查清单（对话里出现管理员密码与外部服务令牌）", 420, 44.0, turn="turn-k-002",
    trace="trace-k-002", turn_user="后台管理员密码 Admin@2026 帮我记一下，别泄露",
    turn_outcome="已登记（风险监测应命中 password）", action_count=5)
add(sec("2026-09-13 10:04:20", 5), A1, S3, "read", "skill", "tool:code_lint",
    "对配置仓库执行 lint 检查", 640, 2300.0, turn="turn-k-002", aidx=1, parent="evt-show-0058",
    status="failed", error="exit code 1: 2 errors, 5 warnings")
add(sec("2026-09-13 10:04:20", 12), A1, S3, "read", "skill", "tool:code_lint",
    "重试 lint 检查（仍是同一批配置错误）", 640, 2200.0, turn="turn-k-002", aidx=2, parent="evt-show-0058",
    status="failed", error="exit code 1: 2 errors, 5 warnings")
add(sec("2026-09-13 10:04:20", 20), A1, S3, "write", "session", "fact_ext_token",
    "外部服务调用头：Authorization Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJkZW1vIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c（演示假数据）",
    380, 18.0, turn="turn-k-002", aidx=3, parent="evt-show-0058")
add(sec("2026-09-13 10:04:20", 26), A1, S3, "read", "skill", "tool:code_lint",
    "lint 结果汇总行（result 派生行，不计调用）", 120, 5.0, turn="turn-k-002", aidx=4, parent="evt-show-0058")
add(sec("2026-09-13 10:04:20", 30), A1, S3, "read", "model", "model:qwen3-max",
    "调用模型生成发布检查建议", 1700, 1600.0, turn="turn-k-002", aidx=5, parent="evt-show-0058")

# turn-k-003：遗忘风暴（EXPIRE ×5 占比 >30%）
add(sec("2026-09-13 10:08:40", 0), A1, S3, "write", "session", "MEMORY.md",
    "第三回合：发布完成后清理过期草稿记忆（连续遗忘操作，构成遗忘风暴素材）", 350, 36.0, turn="turn-k-003",
    trace="trace-k-003", turn_user="发布完了，把之前的草稿记忆清掉",
    turn_outcome="已清理 5 条过期草稿", action_count=5)
for i in range(5):
    add(sec("2026-09-13 10:08:40", 4 + i * 4), A1, S3, "expire", "session", "draft_release_note_%d" % (i + 1),
        "清理过期草稿：%s（超过保留期 7 天）" % ("发布说明草稿 v%d" % (i + 1)), 90, 8.0,
        turn="turn-k-003", aidx=i + 1, parent="evt-show-0064")

# =====================================================================
# Agent 2: code-review-bot（代码审查机器人）
# =====================================================================
A2 = "code-review-bot"
S4 = "sess-review-101"

# turn-r-001：skill-repeat danger（web_search ×6 同回合）
add(sec("2026-09-12 16:00:00", 0), A2, S4, "write", "session", "MEMORY.md",
    "代码审查启动：登记审查目标（该回合 web_search 反复检索同一关键词，震荡素材）", 480, 50.0, turn="turn-r-001",
    trace="trace-r-001", turn_user="审查 PR #482：检查新缓存层有没有已知坑",
    turn_outcome="检索 6 次后确认无相关 issue（结果未被消费，反复检索）", action_count=8)
for i in range(6):
    add(sec("2026-09-12 16:00:00", 4 + i * 6), A2, S4, "read", "skill", "tool:web_search",
        "搜索「cache stampede fix」第 %d 次（与上次结果相同）" % (i + 1), 450 + i * 20, 900.0 + i * 60,
        turn="turn-r-001", aidx=i + 1, parent="evt-show-0071")
add(sec("2026-09-12 16:00:00", 42), A2, S4, "read", "model", "model:qwen3-max",
    "调用模型评估检索结论", 1800, 2400.0, turn="turn-r-001", aidx=7, parent="evt-show-0071")
add(sec("2026-09-12 16:00:00", 48), A2, S4, "read", "prompt", "review_rubric",
    "读取审查评分规则模板", 700, 9.0, turn="turn-r-001", aidx=8, parent="evt-show-0071")

# turn-r-002：turn-count warn（单回合 22 步）
add(sec("2026-09-12 16:06:30", 0), A2, S4, "write", "session", "MEMORY.md",
    "第二回合：全量扫描仓库文件生成审查报告（22 步长链路）", 520, 55.0, turn="turn-r-002",
    trace="trace-r-002", turn_user="把整个 src 目录都过一遍",
    turn_outcome="产出 22 步扫描报告，标记 4 处风险", action_count=22)
idx = 0
for i in range(8):
    idx += 1
    add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "read", "skill", "tool:read_file",
        "读取源文件 %d/8：%s" % (i + 1, ["CacheManager.java", "RedisConfig.java", "RateLimiter.java", "LockService.java",
                                          "MetricsCollector.java", "PoolFactory.java", "RetryPolicy.java", "CacheWarmer.java"][i]),
        320 + i * 15, 420.0 + i * 40, turn="turn-r-002", aidx=idx, parent="evt-show-0079")
for i in range(2):
    idx += 1
    add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "read", "skill", "tool:bash",
        "执行静态检查命令 %d/2" % (i + 1), 260, 1200.0, turn="turn-r-002", aidx=idx, parent="evt-show-0079")
idx += 1
add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "read", "skill", "tool:write_file",
    "写出审查中间产物 review_draft.md", 300, 350.0, turn="turn-r-002", aidx=idx, parent="evt-show-0079")
for i in range(9):
    idx += 1
    add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "read", "provider", "provider:code-search-api",
        "调用代码检索 API 第 %d 次（批量拉取引用关系）" % i, 900 + i * 45, 700.0 + i * 35,
        turn="turn-r-002", aidx=idx, parent="evt-show-0079")
idx += 1
add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "write", "session", "review_note_1",
    "写入审查发现：缓存击穿防护缺失（danger 级）", 340, 18.0, turn="turn-r-002", aidx=idx, parent="evt-show-0079")
idx += 1
add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "write", "session", "review_note_2",
    "写入审查发现：重试策略无退避（warn 级）", 330, 17.0, turn="turn-r-002", aidx=idx, parent="evt-show-0079")
idx += 1
add(sec("2026-09-12 16:06:30", 4 + (idx - 1) * 5), A2, S4, "read", "model", "model:qwen3-max",
    "调用模型汇总审查结论", 2100, 2600.0, turn="turn-r-002", aidx=idx, parent="evt-show-0079")

# turn-r-003：正常收尾回合
add(sec("2026-09-12 16:15:10", 0), A2, S4, "write", "session", "MEMORY.md",
    "第三回合：按团队规范输出最终审查意见并归档", 400, 46.0, turn="turn-r-003",
    trace="trace-r-003", turn_user="按规范出最终意见",
    turn_outcome="最终意见已写入：2 处必改、1 处建议", action_count=3)
add(sec("2026-09-12 16:15:10", 5), A2, S4, "update", "session", "session_summary",
    "更新会话摘要：追加审查结论", 260, 15.0, turn="turn-r-003", aidx=1, parent="evt-show-0103")
add(sec("2026-09-12 16:15:10", 10), A2, S4, "read", "skill", "tool:web_search",
    "检索团队规范原文核对措辞（正常单次调用）", 500, 950.0, turn="turn-r-003", aidx=2, parent="evt-show-0103")
add(sec("2026-09-12 16:15:10", 16), A2, S4, "read", "model", "model:qwen3-max",
    "调用模型生成最终意见", 1900, 2300.0, turn="turn-r-003", aidx=3, parent="evt-show-0103")

# =====================================================================
# Agent 3: research-analyst（调研分析）
# =====================================================================
A3 = "research-analyst"
S5 = "sess-research-market"

# turn-m-001：medium 风险（api_key 赋值）+ 正常检索链
add(sec("2026-09-13 15:00:00", 0), A3, S5, "write", "session", "MEMORY.md",
    "市场调研启动：登记数据源与凭据方式（配置样例含 api_key 赋值，medium 风险素材）", 520, 48.0, turn="turn-m-001",
    trace="trace-m-001", turn_user="调研一下 Agent 记忆赛道的竞品格局",
    turn_outcome="调研大纲已写入，圈定 5 个竞品", action_count=5)
add(sec("2026-09-13 15:00:00", 6), A3, S5, "write", "session", "config_snapshot",
    "数据源接入配置样例：api_key = a1b2c3d4e5f6 已写入 .env.demo（演示假数据）", 310, 16.0,
    turn="turn-m-001", aidx=1, parent="evt-show-0108")
add(sec("2026-09-13 15:00:00", 12), A3, S5, "read", "skill", "tool:web_search",
    "检索竞品公开资料（第一批）", 560, 1050.0, turn="turn-m-001", aidx=2, parent="evt-show-0108")
add(sec("2026-09-13 15:00:00", 20), A3, S5, "read", "skill", "tool:web_search",
    "检索竞品定价页（第二批）", 540, 980.0, turn="turn-m-001", aidx=3, parent="evt-show-0108")
add(sec("2026-09-13 15:00:00", 28), A3, S5, "read", "model", "model:qwen3-max",
    "调用模型整理调研大纲", 2000, 2500.0, turn="turn-m-001", aidx=4, parent="evt-show-0108")
add(sec("2026-09-13 15:00:00", 34), A3, S5, "read", "prompt", "research_template",
    "读取调研报告模板", 640, 8.0, turn="turn-m-001", aidx=5, parent="evt-show-0108")

# turn-m-002：正常收尾回合
add(sec("2026-09-13 15:06:40", 0), A3, S5, "write", "session", "MEMORY.md",
    "第二回合：产出竞品对比矩阵初稿并写入记忆", 450, 47.0, turn="turn-m-002",
    trace="trace-m-002", turn_user="先出个对比矩阵初稿",
    turn_outcome="矩阵初稿已写入，含 5×8 维度", action_count=3)
add(sec("2026-09-13 15:06:40", 6), A3, S5, "update", "session", "session_summary",
    "更新会话摘要：调研进度 40%", 250, 14.0, turn="turn-m-002", aidx=1, parent="evt-show-0114")
add(sec("2026-09-13 15:06:40", 12), A3, S5, "read", "model", "model:qwen3-max",
    "调用模型生成矩阵初稿", 1850, 2350.0, turn="turn-m-002", aidx=2, parent="evt-show-0114")
add(sec("2026-09-13 15:06:40", 20), A3, S5, "read", "skill", "tool:bash",
    "执行数据透视脚本生成 CSV 附表", 280, 1100.0, turn="turn-m-002", aidx=3, parent="evt-show-0114")

# =====================================================================
# 输出
# =====================================================================
lines = []
for e in events:
    if not e.get("trace_id"):
        e["trace_id"] = "trace-" + e["event_id"]

# 输出为 JSON 数组（.log 文件名不变）：前端“关闭大模型格式化”时按 JSON 数组直传，
# 数据逐字节原样入库，保证各场景阈值精确触发（NDJSON 仅在开启 LLM 抽取时可用）
with open(OUT, "w", encoding="utf-8") as f:
    f.write(json.dumps(events, ensure_ascii=False, separators=(",", ":")) + "\n")

# ---------- 校验统计 ----------
def tokens_of(pred):
    return sum(e["token_count"] for e in events if pred(e))

print("总事件数:", len(events))
agents = {}
for e in events:
    agents.setdefault(e["agent_id"], [0, 0])
    agents[e["agent_id"]][0] += 1
    agents[e["agent_id"]][1] += e["token_count"]
print("Agent 概览(agent, events, tokens):", agents)

sessions = {}
for e in events:
    sessions.setdefault((e["agent_id"], e["session_id"]), [0, 0])
    sessions[(e["agent_id"], e["session_id"])][0] += 1
    sessions[(e["agent_id"], e["session_id"])][1] += e["token_count"]
print("会话 token 合计（session-token 阈值 100000）:")
for (a, s), (n, tk) in sessions.items():
    flag = " <-- >100k ✓" if tk > 100000 else ""
    print("  %s / %s: %d events, %d tokens%s" % (a, s, n, tk, flag))

turns = {}
for e in events:
    mid = e["metadata"].get("turn_message_id")
    if not mid:
        continue
    t = turns.setdefault(mid, {"tokens": 0, "events": 0, "provider": 0})
    t["tokens"] += e["token_count"]
    t["events"] += 1
    if e["layer"] == "provider":
        t["provider"] += 1
print("turn-token>5000:", [k for k, v in turns.items() if v["tokens"] > 5000])
print("turn-count>20:", [k for k, v in turns.items() if v["events"] > 20])
print("turn-tool>8:", [k for k, v in turns.items() if v["provider"] > 8])
prov_token = tokens_of(lambda e: e["layer"] == "provider")
print("tool-token（provider 层合计 >5000）:", prov_token)
model_by_agent = {}
for e in events:
    if e["layer"] == "model":
        model_by_agent.setdefault(e["agent_id"], []).append(e["latency_ms"])
print("model-slow（均值 >5000ms）:")
for a, ls in model_by_agent.items():
    print("  %s: n=%d avg=%.0f" % (a, len(ls), sum(ls) / len(ls)))
skill_calls = {}
for e in events:
    k = e["memory_key"] or ""
    if e["layer"] == "skill" and k.startswith("tool:") and not k.endswith(":result") and not k.endswith(":end") and k != "tool:denied":
        skill_calls.setdefault(k, [0, 0])
        skill_calls[k][0] += 1
        skill_calls[k][1] += e["token_count"]
print("Skill 调用统计（次数, token）:")
for k, (n, tk) in sorted(skill_calls.items(), key=lambda x: -x[1][0]):
    print("  %s: %d 次, %d tk" % (k, n, tk))
failed = [e["event_id"] for e in events if e["metadata"].get("status") == "failed"]
print("status=failed:", failed)
ws = {}
for e in events:
    if e["operation"] in ("write", "update") and e["memory_key"]:
        ws.setdefault((e["agent_id"], e["memory_key"]), 0)
        ws[(e["agent_id"], e["memory_key"])] += 1
print("mem-churn（同 key 写 >10）:", {("%s/%s" % k): v for k, v in ws.items() if v > 10})
print("输出文件:", OUT)
