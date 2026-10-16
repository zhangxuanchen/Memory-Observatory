#!/usr/bin/env bash
# 【scripts/CI 本地同款】推送前的全量检查，与 .github/workflows/ci.yml 同构。
#
# 差别只有两点：mvn 加 -o（本机已有依赖缓存，离线更快）、默认不跑 docker 构建；
# 加 --docker 则连镜像构建冒烟一起跑（改过任一 Dockerfile/compose 时建议带上）。
#
# 用法：
#   scripts/ci-check.sh            # Java 全量测试 + Python 采集层测试
#   scripts/ci-check.sh --docker   # 再加 mo-server / mo-exoskeleton 构建冒烟
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Java 全模块测试（含 S1 词表 / S2 依赖方向 / S3 表所有权守则）"
mvn -o -B test

echo "==> Python 采集层测试（契约棘轮 + hook/log 解析器）"
python3 -m unittest discover -s mo_ingest/tests -t .

if [[ "${1:-}" == "--docker" ]]; then
    echo "==> 镜像构建冒烟（根上下文 -pl -am）"
    docker compose build mo-server mo-exoskeleton
fi

echo "==> 全部通过 ✅"
