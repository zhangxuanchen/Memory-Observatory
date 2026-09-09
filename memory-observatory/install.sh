#!/usr/bin/env bash
#
# MemoryObservatory 一键安装并启动脚本
#
# 本地已有 Docker 的环境，一个命令即可装好并跑起来：
#   ./install.sh
#
# 可选参数（可组合）：
#   --no-cache   重新构建，不用 Docker 缓存
#   --pull       先拉取最新基础镜像再构建
#   --reset      清空数据库卷后重新初始化（会删除已收集的数据）
#
set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; }
done1(){ echo -e "${GREEN}${BOLD}✔${NC} $*"; }

show_help() {
  cat <<'EOF'
MemoryObservatory 一键安装并启动

用法: ./install.sh [选项]

选项:
  --no-cache   重建镜像时禁用 Docker 缓存
  --pull       先拉取基础镜像再构建
  --reset      清空数据库数据卷后重新初始化
  -h, --help   显示本帮助

前置要求:
  Docker 20.10+ ，并附带 docker compose 插件（compose v2）。

启动内容:
  3 个服务 -> https://localhost:5173 (Web 界面 / 数据导入 / 接入包下载，自签名 TLS)
             http://localhost:8080 (REST 查询 + OTLP 4318 上报)

鉴权:
  导出环境变量 MO_API_KEY 后运行本脚本即启用接口鉴权（/api、/v1 需 Bearer 头）；
  不设置则为开放模式，仅限本地演示。

常见命令:
  查看状态   docker compose ps
  查看日志   docker compose logs -f
  停止服务   docker compose down
  清空数据   docker compose down -v
EOF
}

# -------- 0. 依赖检查 --------
if ! command -v docker >/dev/null 2>&1; then
  err "未检测到 Docker，请先安装: https://docs.docker.com/get-docker/ 后重试"
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  err "未检测到 docker compose v2。请升级 Docker Desktop / 安装 Compose 插件后重试"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
if [ ! -f docker-compose.yml ]; then
  err "未找到 docker-compose.yml，请在 MemoryObservatory 仓库根目录下运行本脚本"
  exit 1
fi

COMPOSE_BUILD="--build"
RESET=0
for a in "$@"; do
  case "$a" in
    --no-cache) COMPOSE_BUILD="--build --no-cache";;
    --pull)     COMPOSE_BUILD="--pull --build";;
    --reset)    RESET=1;;
    -h|--help)  show_help; exit 0;;
    *)          warn "忽略未知参数: $a";;
  esac
done

info "Docker 环境: $(docker --version) | Compose: $(docker compose version --short)"

# -------- 0.5 鉴权状态提示 --------
if [ -z "${MO_API_KEY:-}" ]; then
  warn "未设置 MO_API_KEY：接口鉴权关闭（仅适合本地演示）。"
  warn "生产部署请先执行： export MO_API_KEY=\$(openssl rand -hex 24)  然后重新运行本脚本。"
else
  info "已检测到 MO_API_KEY：/api 与 /v1 上报接口将强制鉴权（Dashboard 首次访问会弹窗录入同一个 key）。"
fi

# -------- 1. （可选）清空旧数据 --------
if [ "$RESET" -eq 1 ]; then
  warn "检测到 --reset，将删除数据库卷并重新初始化（原数据不可恢复）"
  docker compose down -v
fi

# -------- 2. 构建并启动容器 --------
info "开始一键安装并启动 MemoryObservatory (postgres + mo-server + mo-dashboard) ..."
docker compose up -d $COMPOSE_BUILD

# -------- 3. 等待后端就绪 --------
info "等待后端 (mo-server @ :8080) 就绪 ..."
BACKEND_OK=0
for i in $(seq 1 90); do
  if curl -sf --max-time 2 "http://localhost:8080/api/v1/agents" >/dev/null 2>&1; then
    BACKEND_OK=1; break
  fi
  sleep 2
done
if [ "$BACKEND_OK" -ne 1 ]; then
  err "后端在约 3 分钟内未就绪。最近日志："
  docker compose logs --tail=40 mo-server || true
  exit 1
fi

# -------- 4. 等前端就绪 --------
info "等待前端 (mo-dashboard @ https://localhost:5173) 就绪 ..."
FRONT_OK=0
for i in $(seq 1 15); do
  if curl -skf --max-time 2 "https://localhost:5173/" >/dev/null 2>&1; then
    FRONT_OK=1; break
  fi
  sleep 1
done

echo
done1 "安装并启动成功！"
echo
echo -e "  ${BOLD}Web 界面${NC}   https://localhost:5173   (自签名证书，浏览器提示\"不安全\"点继续即可)"
if [ "$FRONT_OK" -ne 1 ]; then
  warn "前端暂时未响应，稍后直接打开上面地址即可"
fi
echo -e "  ${BOLD}REST 查询${NC}  http://localhost:8080/api/v1/agents"
echo -e "  ${BOLD}OTLP 上报${NC}  http://localhost:4318/v1/traces   /   REST 上报 POST ${BOLD}/api/v1/events${NC}"
if [ -n "${MO_API_KEY:-}" ]; then
echo -e "  ${BOLD}鉴权${NC}      已启用：浏览器打开 Dashboard 后在弹窗输入 MO_API_KEY；"
echo -e "                上报脚本/SDK 需设置同一 key（MO_API_KEY 环境变量或 Authorization: Bearer 头）。"
fi
echo
echo -e "  ${BOLD}验证上报一条事件${NC}（可选，刷新页面即可在 事件详情 看到 demo-agent）："
if [ -n "${MO_API_KEY:-}" ]; then
cat <<EOF
    curl -s -X POST http://localhost:8080/api/v1/events \\
      -H 'Content-Type: application/json' \\
      -H "Authorization: Bearer \$MO_API_KEY" \\
      -d '{"agentId":"demo-agent","sessionId":"session-1","operation":"WRITE","layer":"prompt","memoryKey":"hello","memorySummary":"一键安装验证","tokenCount":10}'
EOF
else
cat <<'EOF'
    curl -s -X POST http://localhost:8080/api/v1/events \
      -H 'Content-Type: application/json' \
      -d '{"agentId":"demo-agent","sessionId":"session-1","operation":"WRITE","layer":"prompt","memoryKey":"hello","memorySummary":"一键安装验证","tokenCount":10}'
EOF
fi
echo
echo -e "  ${BOLD}停止/查看${NC}"
echo "    docker compose down        # 停止"
echo "    docker compose logs -f     # 查看日志"
echo "    docker compose ps          # 查看状态"
echo "    调用脚本上报（启用鉴权时先 export MO_API_KEY）："
echo "      python3 examples/trae_report_event.py --operation WRITE --layer prompt --memory-key demo --summary 你好 --token-count 10"