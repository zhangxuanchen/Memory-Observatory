#!/usr/bin/env bash
# 为项目自带的 laya 语义过滤后端准备 Python 环境。
#
# 为什么需要这一步：mo-server 启动时若发现 laya-backend/ 下没有 venv，会**后台自举**
# 同一套动作（见 LayaBackendManager#bootstrapAsync）。本脚本只是把自举提前到部署阶段，
# 好处是首次启动就能立即就绪、不必等它装完。
#
# 幂等：已有 venv 时只同步依赖，重复执行无副作用。
# 不依赖任何外部仓库——laya 从 PyPI 装，后端脚本随本仓库分发
# （laya-backend/server.py 为本项目自有代码，按 Apache-2.0 分发；见 laya-backend/NOTICE）。
#
# 用法：
#   bash scripts/setup-laya-backend.sh              # 建 venv + 装依赖
#   bash scripts/setup-laya-backend.sh --check      # 只体检，不改动
#   PIP_INDEX_URL=... bash scripts/setup-laya-backend.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/laya-backend"
VENV="$DIR/.venv"
PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple}"
PY="${PYTHON:-python3}"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

if [[ ! -f "$DIR/server.py" ]]; then
  echo "✗ 找不到 $DIR/server.py —— 这个脚本必须在仓库内运行" >&2
  exit 1
fi

if [[ $CHECK_ONLY -eq 1 ]]; then
  echo "后端目录 : $DIR"
  if [[ -x "$VENV/bin/python" ]]; then
    echo "venv     : ✓ $VENV"
    "$VENV/bin/python" - <<'PY'
import importlib.util as u, sys
for m in ("laya", "torch", "transformers"):
    print(f"  {m:14s}: " + ("✓" if u.find_spec(m) else "✗ 缺失"))
sys.exit(0 if u.find_spec("laya") else 1)
PY
  else
    echo "venv     : ✗ 未创建（mo-server 启动时会后台自举，或执行本脚本不带 --check）"
    exit 1
  fi
  exit 0
fi

if ! command -v "$PY" >/dev/null 2>&1; then
  echo "✗ 找不到 $PY。用 PYTHON=/path/to/python3 指定解释器。" >&2
  exit 1
fi

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "==> 建 venv：$VENV"
  "$PY" -m venv "$VENV"
fi

echo "==> 装依赖（源：$PIP_INDEX_URL）"
"$VENV/bin/python" -m pip install -q -U pip -i "$PIP_INDEX_URL"
"$VENV/bin/python" -m pip install --disable-pip-version-check -i "$PIP_INDEX_URL" \
  -r "$DIR/requirements.txt"

echo
echo "✓ 完成。现有环境："
"$VENV/bin/python" - <<'PY'
import importlib.util as u
for m in ("laya", "torch", "transformers"):
    spec = u.find_spec(m)
    mod = __import__(m) if spec else None
    ver = getattr(mod, "__version__", "?") if mod else "缺失"
    print(f"  {m:14s}: {ver}")
PY
echo
echo "权重（~1.5GB）由后端首次启动时自行补下；HF 官方域名不通时走 HF_ENDPOINT 镜像。"
echo "容器方式无需执行本脚本：docker compose up -d 即可（见 docker-compose.yml）。"
