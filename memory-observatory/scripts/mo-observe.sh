#!/usr/bin/env bash
# =============================================================================
# 【模块】TraeCode 会话观测 · 采集端（RunCommand + curl 包装）
# 【文件】mo-observe.sh（scripts/）
# 【核心功能】把 TraeCode 中需要观测的命令执行包装成 MemoryEvent 上报到
#            MemoryObservatory（/api/v1/events），支持 Turn 步骤链聚合：
#            run 逐步上报 action 子事件，finish 汇总上报 main turn 事件。
# 【设计要点】旁路容错：上报失败只 WARN，不改原命令退出码/输出。
#             Turn 状态存 /tmp/mo-observe/<agent>-<session>.json，跨命令共享。
#             TraeCode 内部 LLM token 拿不到，token 字段如实置 0。
# 【用法】
#   mo-observe run    --agent A --session S [--goal "目标"] [--note "AI 决策说明"] -- <cmd...>
#   mo-observe finish --agent A --session S [--outcome "结论"]
#   子命令在最前；flag 可放子命令后任意位置；`--` 之后为真实命令。
#   --note 会先上报一条 layer=model 的「AI 思考」子事件再执行命令，形成 思考→操作 链路。
# 【环境】MO_ENDPOINT 默认 http://localhost:9091
# =============================================================================
set -u

MO_ENDPOINT="${MO_ENDPOINT:-http://localhost:9091}"
STATE_DIR="/tmp/mo-observe"

log_warn() { echo "[mo-observe][WARN] $*" >&2; }

# ---- JSON 工具 ----
json_escape() {
  local s="$1" out="" c
  local i
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in
      '"') out+='\"' ;;
      '\') out+='\\\\' ;;
      $'\n') out+='\\n' ;;
      $'\r') out+='\\r' ;;
      $'\t') out+='\\t' ;;
      *) out+="$c" ;;
    esac
  done
  printf '%s' "$out"
}
json_str() { printf '"%s"' "$(json_escape "$1")"; }

# ---- 状态 ----
state_file() { printf '%s/%s-%s.json' "$STATE_DIR" "$1" "$2"; }
# 步骤摘要累积文件（每行一步，finish 时汇总为 turn_actions）
actions_file() { printf '%s/%s-%s.actions' "$STATE_DIR" "$1" "$2"; }

load_state() {
  local f; f="$(state_file "$AGENT" "$SESSION")"
  ST_TURN=""; ST_STEP=0; GOAL=""
  if [ -f "$f" ]; then
    ST_TURN="$(sed -n 's/.*"turn_id":\("[^"]*"\).*/\1/p' "$f" | head -1 | tr -d '"')"
    ST_STEP="$(sed -n 's/.*"step":\([0-9]*\).*/\1/p' "$f" | head -1)"
    [ -n "$ST_STEP" ] || ST_STEP=0
    GOAL="$(sed -n 's/.*"goal":\("[^"]*"\).*/\1/p' "$f" | head -1 | tr -d '"')"
  fi
}
save_state() {
  mkdir -p "$STATE_DIR"
  local f; f="$(state_file "$AGENT" "$SESSION")"
  printf '{"turn_id":%s,"agent":%s,"session":%s,"step":%d,"goal":%s}\n' \
    "$(json_str "$ST_TURN")" "$(json_str "$AGENT")" "$(json_str "$SESSION")" \
    "$ST_STEP" "$(json_str "$GOAL")" > "$f"
}

# ---- 通用 flag 解析：消费 --agent/--session/--goal/--note/--outcome 与 `--`；其余留在 REST ----
parse_flags() {
  AGENT=""; SESSION=""; GOAL=""; NOTE=""; OUTCOME=""
  REST=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --agent) AGENT="$2"; shift 2 ;;
      --session) SESSION="$2"; shift 2 ;;
      --goal) GOAL="$2"; shift 2 ;;
      --note) NOTE="$2"; shift 2 ;;
      --outcome) OUTCOME="$2"; shift 2 ;;
      --) shift; REST=("$@"); break ;;
      -*) echo "[mo-observe] 未知 flag: $1" >&2; return 2 ;;
      *) REST=("$@"); break ;;
    esac
  done
  [ -z "$AGENT" ] && { echo "[mo-observe] 缺少 --agent" >&2; return 2; }
  [ -z "$SESSION" ] && { echo "[mo-observe] 缺少 --session" >&2; return 2; }
  return 0
}

now_iso() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
now_ms() { echo "$(( $(date +%s%N) / 1000000 ))"; }

# 上报 action 子事件（旁路容错）；layer param: model=AI 思考 / skill=工具操作
report_action() {
  local summary="$1" lat="$2" layer="$3" full="$4" ts
  ts="$(now_iso)"
  local memory_key="tool:exec"
  [ "$layer" = "model" ] && memory_key="model:trae-code"
  local p
  p="{\"agentId\":$(json_str "$AGENT"),\"sessionId\":$(json_str "$SESSION"),"
  p+="\"operation\":\"WRITE\",\"layer\":$(json_str "$layer"),"
  p+="\"memoryKey\":$(json_str "$memory_key"),"
  p+="\"memorySummary\":$(json_str "$summary"),"
  p+="\"tokenCount\":0,\"latencyMs\":$lat,"
  p+="\"timestamp\":$(json_str "$ts"),"
  p+="\"metadata\":{\"turn_message_id\":$(json_str "$ST_TURN"),"
  p+="\"action_idx\":$(json_str "$ST_STEP"),"
  p+="\"action_full\":$(json_str "$full")}}"
  curl -s -m 5 -X POST "$MO_ENDPOINT/api/v1/events" \
    -H 'Content-Type: application/json' -d "$p" >/dev/null 2>&1 \
    || log_warn "action 上报失败: $summary"
}

# 上报 main turn 事件（旁路容错）
report_main() {
  local actions="$1" lat="$2" ts
  ts="$(now_iso)"
  local summary="$OUTCOME"; [ -z "$summary" ] && summary="Turn 结束"
  local p
  p="{\"agentId\":$(json_str "$AGENT"),\"sessionId\":$(json_str "$SESSION"),"
  p+="\"operation\":\"WRITE\",\"layer\":\"session\","
  p+="\"memoryKey\":$(json_str "turn:$SESSION"),"
  p+="\"memorySummary\":$(json_str "$summary"),"
  p+="\"tokenCount\":0,\"latencyMs\":$lat,"
  p+="\"timestamp\":$(json_str "$ts"),"
  p+="\"metadata\":{\"turn_message_id\":$(json_str "$ST_TURN"),"
  [ -n "$GOAL" ] && p+="\"turn_user\":$(json_str "$GOAL"),"
  p+="\"turn_outcome\":$(json_str "$OUTCOME"),"
  [ -n "$actions" ] && p+="\"turn_actions\":$(json_str "$actions"),"
  p+="\"action_count\":$(json_str "$ST_STEP")}}"
  curl -s -m 5 -X POST "$MO_ENDPOINT/api/v1/events" \
    -H 'Content-Type: application/json' -d "$p" >/dev/null 2>&1 \
    || log_warn "main turn 上报失败"
}

cmd_run() {
  parse_flags "$@" || exit 2
  [ ${#REST[@]} -eq 0 ] && { echo "[mo-observe] run 缺少命令，请以 -- 结尾提供" >&2; exit 2; }
  local user_goal="$GOAL"
  load_state
  # load_state 可能用文件旧值覆盖 GOAL；本次显式传的 goal 应覆盖回去
  [ -n "$user_goal" ] && GOAL="$user_goal"
  if [ -z "$ST_TURN" ]; then ST_TURN="turn-$(date -u +%s%N)"; ST_STEP=0; fi
  local start end lat rc summary
  # 先记录 AI 思考（layer=model 子事件），再执行操作（layer=skill），形成 思考→操作 链路
  if [ -n "$NOTE" ]; then
    summary="AI 思考 · $NOTE"
    report_action "$summary" 0 "model" "(dev log) $NOTE"
    mkdir -p "$STATE_DIR"
    echo "$summary" >> "$(actions_file "$AGENT" "$SESSION")"
    ST_STEP=$(( ST_STEP + 1 ))
  fi
  start="$(now_ms)"
  "${REST[@]}"
  rc=$?
  end="$(now_ms)"
  lat=$(( end - start ))
  summary="命令执行 · ${REST[0]} · exit=$rc · ${lat}ms"
  report_action "$summary" "$lat" "skill" "$(IFS=' '; echo "${REST[*]}")"
  # 累积步骤摘要，finish 时汇总成 turn_actions（供抽屉「操作步骤」渲染）
  mkdir -p "$STATE_DIR"
  echo "$summary" >> "$(actions_file "$AGENT" "$SESSION")"
  ST_STEP=$(( ST_STEP + 1 ))
  save_state
  return $rc
}

cmd_finish() {
  parse_flags "$@" || exit 2
  load_state
  if [ -z "$ST_TURN" ]; then
    echo "[mo-observe] 无进行中的 turn（先 run）" >&2
    exit 2
  fi
  local done_turn="$ST_TURN"
  # 汇总步骤摘要为 turn_actions JSON 数组字符串
  local actions_json=""
  if [ -f "$(actions_file "$AGENT" "$SESSION")" ]; then
    local first=1 line
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      if [ "$first" -eq 1 ]; then actions_json='['; first=0; else actions_json+=','; fi
      actions_json+="$(json_str "$line")"
    done < "$(actions_file "$AGENT" "$SESSION")"
    [ -n "$actions_json" ] && actions_json+=']'
  fi
  report_main "$actions_json" 0
  ST_TURN=""
  rm -f "$(state_file "$AGENT" "$SESSION")" "$(actions_file "$AGENT" "$SESSION")"
  echo "[mo-observe] turn 完成: $done_turn"
}

case "${1:-}" in
  run) shift; cmd_run "$@" ;;
  finish) shift; cmd_finish "$@" ;;
  *)
    echo "用法: mo-observe {run|finish} [--agent A] [--session S] [--goal G | --outcome O] [--] <cmd...>" >&2
    exit 2 ;;
esac