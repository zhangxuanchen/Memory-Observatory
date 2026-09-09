#!/usr/bin/env bash
###############################################################################
# mo-static-sync.sh
# 统一把前端静态资源从源码目录同步到构建产物目录。
#
# 背景：Spring Boot 从 classpath:/static（即 target/classes/static）提供静态资源。
#       Maven 构建（mvn compile / mvn spring-boot:run / mvn package）会自动把
#       src/main/resources 复制到 target/classes，因此"正规流程"是直接用 Maven 构建。
#       本脚本仅用于给"仍在运行的旧进程"做即时热更，避免反复手动 cp。
#
# 用法：
#   ./scripts/mo-static-sync.sh          同步所有静态资源到 target/classes/static
###############################################################################
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/mo-server/src/main/resources/static"
TGT="$ROOT/mo-server/target/classes/static"

if [ ! -d "$SRC" ]; then
  echo "目录不存在: $SRC" >&2
  exit 1
fi

mkdir -p "$TGT"
cp -f "$SRC"/* "$TGT"/ 2>/dev/null || true

echo "已同步 static -> $TGT"
ls -1 "$TGT"