#!/usr/bin/env bash
###############################################################################
# mo-vendor-fetch.sh
# 拉取前端第三方库（marked / mermaid）到源码静态目录，统一纳管版本来源。
# 避免"下载脚本散落各处"或"依赖 CDN 运行时拉取"，保证离线可用 + 版本可控。
#
# 用法：
#   ./scripts/mo-vendor-fetch.sh           拉取（或更新）默认版本
#   MARKED_VERSION=12  MERMAID_VERSION=10.9.1 ./scripts/mo-vendor-fetch.sh  指定版本
#
# 说明：
#   拉取完成后，用 ./scripts/mo-static-sync.sh 同步到构建产物目录。
#   标记：mermaid@9.3.0 起为 ESM，旧全局 window.mermaid 由 10.x 提供，故默认锁 10 系。
###############################################################################
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATIC_DIR="$ROOT/mo-server/src/main/resources/static"

# 默认版本（可在调用时用环境变量覆盖）
MARKED_VERSION="${MARKED_VERSION:-5.1.2}"
MERMAID_VERSION="${MERMAID_VERSION:-10.9.1}"

[ -d "$STATIC_DIR" ] || { echo "目录不存在: $STATIC_DIR" >&2; exit 1; }

echo "==> 拉取 marked@${MARKED_VERSION}"
curl -fsSL "https://cdn.jsdelivr.net/npm/marked@${MARKED_VERSION}/marked.min.js" \
  -o "$STATIC_DIR/marked.min.js" \
  || { echo "marked 下载失败"; exit 1; }

echo "==> 拉取 mermaid@${MERMAID_VERSION}"
curl -fsSL "https://cdn.jsdelivr.net/npm/mermaid@${MERMAID_VERSION}/dist/mermaid.min.js" \
  -o "$STATIC_DIR/mermaid.min.js" \
  || { echo "mermaid 下载失败"; exit 1; }

echo "==> vendor 更新完成："
ls -la "$STATIC_DIR"/marked.min.js "$STATIC_DIR"/mermaid.min.js
echo
echo "若服务仍在运行，请执行 ./scripts/mo-static-sync.sh 同步到构建产物目录。"