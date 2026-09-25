#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MemoryObservatory 的 laya 推理后端。

归属与来源
----------

   源文件   : java/backend/server.py
              同一作者在 laya 工作副本中编写的后端脚本。**该文件从未提交到上游**，
              也不存在于上游仓库中（`git ls-files` 无此文件，`git status` 显示
              `?? java/`）。因此本文件是本项目自有代码，非第三方作品的衍生。

   关联项目 : laya — Apache License 2.0，权利人 Convai Innovations
              https://huggingface.co/convaiinnovations/laya
              本文件调用了 laya 的公开 API（Router / Agent / questions 契约），
              接口与事实性信息不受版权保护；laya 包本身作为运行依赖另行声明
              （见 THIRD_PARTY_NOTICES.md）。

   本文件许可 : Apache License 2.0（全文见同目录 LICENSE）。
              与 laya 生态保持一致，也避免日后把 java/ 上游化时再改一次。
              仓库其余部分为 MIT——许可边界见根 LICENSE 与 README。

   修改内容（相对 java/backend/server.py）：

   1. 只保留 mo-server 实际调用的两个端点（``GET /health``、``POST /api/predict``），
      删除测试台界面（``ui.html``）、PRESETS / SAMPLES、``/v1/predict``、
      ``/v1/preload``、``/api/route``、``/api/meta``、``GET /`` 等端点；
   2. 新增启动时的权重预检与补下（``ensure_weights``），使后端可独立部署；
   3. 默认设置 ``HF_ENDPOINT`` 镜像端点；
   4. 请求体校验与错误结构统一为 ``{"error": "<Type>: <msg>"}``。

名称使用："laya" 与 "Convai Innovations" 是其各自权利人的名称/商标，本文件使用
这些名称**仅为指明技术依赖与来源**。本项目与 laya 官方不存在隶属、赞助或背书关系，
发布或宣传时不得暗示其官方认可。

laya 本体作为 PyPI 依赖安装（见同目录 requirements.txt），不随本仓库分发。

端点（与 mo-server 的 `LayaClient` 严格对应）：

    GET  /health         -> {"status","loaded","version"}
    POST /api/predict    -> {"model","answers","usage","routing","latency_ms","state"}

`/api/predict` 请求体：

    {"state": {...}, "questions": {"<id>": {"type": "...", ...}}, "model": "auto"}

`model` 为 `auto` 或缺省时，由 laya Router 在**前向之前**决定用哪个 checkpoint。
这一点很重要：非拉丁文本送进 english checkpoint 会崩到接近随机却仍报高置信，
所以路由必须发生在前向之前，不能靠事后置信度补救。

用法：

    python server.py                                   # 127.0.0.1:8770
    python server.py --host 0.0.0.0 --port 8770        # 容器内
    python server.py --preload english,multilingual    # 预加载（默认值）
    python server.py --preload ""                      # 全懒加载（不建议）

权重：默认从 HuggingFace 镜像下载（可用 HF_ENDPOINT 覆盖）。已在本地缓存时
预检是纯本地操作，不联网。
"""
import argparse
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# HuggingFace 官方在部分网络下不可达，默认走镜像；已被显式设置时尊重原值。
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("USE_TORCH", "1")

import laya  # noqa: E402

# 一个 checkpoint 完整所需的文件，对齐 laya.agent 加载时的 allow_patterns。
CHECKPOINT_FILES = ("rl_agent_config.json", "model.safetensors", "tokenizer/*", "encoder/*")

ROUTER = None


# --------------------------------------------------------------------- 权重预检

def _checkpoint_patterns(subfolder):
    prefix = (subfolder + "/") if subfolder else ""
    return [prefix + name for name in CHECKPOINT_FILES]


def ensure_weights(names, log=print):
    """确保所需 checkpoint 已在本地缓存，缺失则补齐。返回缺失后仍失败的名单。

    探测走 `snapshot_download(..., local_files_only=True)`：纯本地、毫秒级、不联网，
    且与 laya 自己的加载语义完全一致（同一个 API、同一套 allow_patterns）。
    自己拼 HF 缓存路径是行不通的——布局受 HF_HOME / HF_HUB_CACHE 影响，
    猜错会把「已就绪」误判成「缺失」，导致每次启动都做无谓下载。

    失败不抛出：后端照常启动，缺失的 checkpoint 会在首次请求时报错，
    而调用方（mo-server）是 fail-open 的，不会因此丢告警。
    """
    from huggingface_hub import snapshot_download

    specs = dict(laya.DEFAULT_MODELS)
    missing = []
    for name in names:
        if name not in specs:
            log("[laya-backend] 未知 checkpoint %r，跳过预检" % name)
            continue
        repo, sub = (list(specs[name]) + [None])[:2]
        try:
            snapshot_download(repo, allow_patterns=_checkpoint_patterns(sub),
                              local_files_only=True)
        except Exception:
            missing.append((name, repo, sub))

    if not missing:
        log("[laya-backend] 权重预检通过：%s 均已在本地缓存" % ", ".join(names))
        return []

    log("[laya-backend] 需补下 %d 个 checkpoint（首次约 1~2 分钟，端点 %s）"
        % (len(missing), os.environ.get("HF_ENDPOINT")))
    failed = []
    for name, repo, sub in missing:
        try:
            snapshot_download(repo, allow_patterns=_checkpoint_patterns(sub))
            log("[laya-backend] 已补下 %s" % name)
        except Exception as exc:
            failed.append(name)
            log("[laya-backend] ⚠️ 补下 %s 失败：%s" % (name, str(exc)[:200]))

    if failed:
        log("[laya-backend] ⚠️ %s 仍缺失。中文内容会被路由到 multilingual，"
            "它缺失时判定会报错，而 mo-server 侧是 fail-open 的——"
            "表现为「看起来没有假阳性」，实际上根本没过滤。"
            % ", ".join(failed))
    return failed


# --------------------------------------------------------------------- HTTP 服务

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass                                  # 静音逐请求日志，避免淹没 mo-server 日志

    def _send(self, code, body, ctype):
        raw = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False),
                   "application/json; charset=utf-8")

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}") if length else {}

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            self._json(200, {"status": "ok", "loaded": ROUTER.loaded,
                             "version": laya.__version__})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            if path != "/api/predict":
                self._json(404, {"error": "not found"})
                return
            payload = self._read_json()

            questions = payload.get("questions")
            if not isinstance(questions, dict) or not questions:
                raise ValueError("questions must be a non-empty JSON object of id -> definition")
            state = payload.get("state")
            if state is None or (isinstance(state, dict) and not state):
                raise ValueError("state is required (JSON object)")

            model = payload.get("model")
            if model in (None, "", "auto"):
                model = None                  # None = 交给 Router 自动路由

            # lang：显式指定语言族，优先于 Router 的脚本探测。
            # 必要性：本项目状态里混有大量英文标识符（skill 名 / 文件名 / 字段名），
            # 当 ASCII 字母占比过半时 Router 会把整个状态判成 latin → 送进 english
            # checkpoint，而它读不了其中的中文（script_profile 里 han 占 28% 时就会发生）。
            # 注意由调用方按「被判内容本身的语言」决定是否声明（见 Java 侧 ConfirmKind.langHint）：
            # 中文叙述（问题分析 / 流程分析）要传 lang="zh"，而被判内容是代码片段的场景
            # （内容风险）不传——那类片段交给 english 更准，且中文只出现在短标签里。
            # 不传则维持自动探测。
            lang = payload.get("lang")
            if lang in (None, "", "auto"):
                lang = None

            t0 = time.time()
            result = ROUTER.predict(state, questions, model=model, lang=lang)
            result["latency_ms"] = round((time.time() - t0) * 1000, 2)
            result["state"] = state
            self._json(200, result)
        except Exception as exc:              # 统一结构，Java 侧与排障都能解析
            self._json(400, {"error": "%s: %s" % (type(exc).__name__, exc)})


def _device_of():
    for name in ROUTER.loaded:
        try:
            return str(ROUTER.load(name).device)
        except Exception:
            pass
    return "auto"


def main():
    global ROUTER
    parser = argparse.ArgumentParser(description="MemoryObservatory laya 推理后端")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--device", default=None, help="cpu / mps / cuda，默认自动")
    parser.add_argument("--token", default=None, help="HuggingFace token（私有模型才需要）")
    parser.add_argument("--preload", default="english,multilingual",
                        help='逗号分隔；默认 "english,multilingual"')
    parser.add_argument("--skip-weight-check", action="store_true",
                        help="跳过权重预检（权重由外部保证时使用）")
    args = parser.parse_args()

    names = [n.strip() for n in (args.preload or "").split(",") if n.strip()]
    if names and not args.skip_weight_check:
        ensure_weights(names)

    ROUTER = laya.Router(device=args.device,
                         token=args.token or os.environ.get("HF_TOKEN"),
                         max_loaded=len(laya.DEFAULT_MODELS))
    if names:
        ROUTER.preload(names)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("[laya-backend] 就绪 → http://%s:%d · preload=%s · device=%s"
          % (args.host, args.port, names or "lazy", _device_of()), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[laya-backend] 已停止", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
