#!/bin/bash
# Token 用量助手 · Hook 原始 stdin 探针
# 目的：无损捕获 IDE hook 喂给脚本的原始 JSON，用于确认是否携带 token / 积分 / prompt。
# 纪律：stdout 恒输出合法空 HookOutput（{}）、异常全部吞掉、始终 exit 0 —— 绝不影响 IDE。
# 调用：dump-hook.sh <source-label>   （label 由 hook 配置传入，如 qoder）
SRC="${1:-unknown}"
OUT_DIR="$HOME/.tua-probe/captures"
mkdir -p "$OUT_DIR" 2>/dev/null
TS="$(date +%Y%m%d-%H%M%S)-$$"
RAW="$OUT_DIR/${TS}--${SRC}.json"
cat > "$RAW" 2>/dev/null
{
  echo "captured_at=$(date -Iseconds)"
  echo "source=$SRC"
  echo "argv=$*"
  echo "pwd=$(pwd)"
  echo "bytes=$(wc -c < "$RAW" 2>/dev/null | tr -d ' ')"
} > "$OUT_DIR/${TS}--${SRC}.meta" 2>/dev/null
printf '{}'
exit 0