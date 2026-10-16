"""接入层契约与凭据无关的单测（纯 stdlib unittest）。

覆盖四件事：
1. 映射表（operation 归一 / io_kind 工具名与关键词）命中；
2. OTLP payload 属性齐全、trace/span 长度对齐契约；
3. Hook 路径**零依赖**——整层不 import mo_sdk（子进程校验）；
4. 跨语言契约一致——与 Java 侧读同一份 contract/vocabulary.json。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from mo_ingest import contract, get_adapter, otlp
from mo_ingest.registry import PLATFORMS

REPO_ROOT = str(Path(__file__).resolve().parents[2])


class OperationTest(unittest.TestCase):
    def test_归一映射(self):
        self.assertEqual(contract.normalize_operation("store"), "WRITE")
        self.assertEqual(contract.normalize_operation("retrieve"), "READ")
        self.assertEqual(contract.normalize_operation("forget"), "EXPIRE")
        self.assertEqual(contract.normalize_operation("consolidate"), "UPDATE")
        self.assertEqual(contract.normalize_operation("delete"), "EXPIRE")
        self.assertEqual(contract.normalize_operation("compact"), "UPDATE")
        self.assertEqual(contract.normalize_operation("READ"), "READ")
        self.assertEqual(contract.normalize_operation(""), "WRITE")
        self.assertEqual(contract.normalize_operation("没见过的"), "WRITE")


class IoKindTest(unittest.TestCase):
    def test_真工具名映射(self):
        self.assertEqual(contract.io_kind_of_tool("read_file"), "file_read")
        self.assertEqual(contract.io_kind_of_tool("Grep"), "content_search")
        self.assertEqual(contract.io_kind_of_tool("Write"), "file_write")
        self.assertEqual(contract.io_kind_of_tool("search_memory"), "memory_read")
        self.assertEqual(contract.io_kind_of_tool("update_memory"), "memory_write")
        self.assertIsNone(contract.io_kind_of_tool("unknown_tool"))

    def test_关键词回退(self):
        self.assertEqual(contract.io_kind_of_text("检索记忆"), "memory_read")
        self.assertEqual(contract.io_kind_of_text("修改 read_file 说明"), "file_write")
        self.assertEqual(contract.io_kind_of_text("grep -n x src"), "content_search")
        self.assertEqual(contract.io_kind_of_text("cat a.txt"), "file_read")
        self.assertEqual(contract.io_kind_of_text("随便写点啥"), "tool_other")

    def test_shell落盘与箭头不误判(self):
        self.assertEqual(contract.io_kind_of_text("echo hi > out.txt"), "file_write")
        self.assertEqual(contract.io_kind_of_text("grep -n x > out.txt"), "file_write")
        self.assertEqual(contract.io_kind_of_text("a => { x }"), "tool_other")
        self.assertEqual(contract.io_kind_of_text("echo hi > /dev/null"), "tool_other")

    def test_default_none_可退回(self):
        self.assertIsNone(contract.io_kind_of_text("随便写点啥", default=None))
        self.assertEqual(contract.io_kind_of_text("grep x", default=None), "content_search")


class OtlpTest(unittest.TestCase):
    def _ev(self):
        return contract.MemoryEvent(
            agent_id="qoder", session_id="s1", operation="store", layer="session",
            memory_key="turn:s1", memory_summary="探针", token_count=12,
            metadata={"source": "qoder_hook", "io_kind": "lifecycle",
                      "turn_message_id": "r1", "turn_user": "探针", "action_count": "0"},
        )

    def test_span属性齐全(self):
        ev = self._ev()
        span = otlp.event_to_span(ev)
        attrs = {a["key"]: a["value"] for a in span["attributes"]}
        self.assertEqual(len(span["traceId"]), 32)
        self.assertEqual(span["traceId"], contract.trace_id_for("s1"))
        self.assertEqual(len(span["spanId"]), 16)
        self.assertEqual(attrs["memory.op_normalized"], {"stringValue": "WRITE"})
        self.assertEqual(attrs["memory.operation"], {"stringValue": "add"})
        self.assertEqual(attrs["memory.trace_id"], {"stringValue": contract.trace_id_for("s1")})
        self.assertEqual(attrs["gen_ai.system"], {"stringValue": "qoder_hook"})
        # metadata 展开为 memory.meta.*（服务端去前缀落进 metadata jsonb）
        self.assertEqual(attrs["memory.meta.source"], {"stringValue": "qoder_hook"})
        self.assertEqual(attrs["memory.meta.io_kind"], {"stringValue": "lifecycle"})
        self.assertEqual(attrs["memory.meta.action_count"], {"stringValue": "0"})
        self.assertEqual(attrs["memory.key"], {"stringValue": "turn:s1"})

    def test_payload按agent分组(self):
        a = self._ev()
        b = contract.MemoryEvent(agent_id="trae", session_id="s2", operation="WRITE")
        payload = otlp.build_payload([a, b])
        self.assertEqual(len(payload["resourceSpans"]), 2)
        for rs in payload["resourceSpans"]:
            self.assertEqual(len(rs["scopeSpans"][0]["spans"]), 1)

    def test_dry_run不联网(self):
        seen = []
        r = otlp.post("http://127.0.0.1:1/v1/traces", {"x": 1}, dry_run=True, log=seen.append)
        self.assertIsNone(r)
        self.assertTrue(seen and "DRY_RUN" in seen[0])


class RegistryTest(unittest.TestCase):
    def test_平台元数据(self):
        self.assertEqual(PLATFORMS["qoder"].modes, ("hook",))
        self.assertEqual(PLATFORMS["qoder"].source, "qoder_hook")
        # trae 一平台两模式：log 在首位（默认模式不变），hook 需显式指定
        self.assertEqual(PLATFORMS["trae"].modes, ("log", "hook"))
        self.assertEqual(PLATFORMS["trae"].source, "trae_importer")
        self.assertEqual(PLATFORMS["trae"].hook_source, "trae_hook")
        self.assertEqual(PLATFORMS["workbuddy"].modes, ("log",))
        self.assertEqual(PLATFORMS["workbuddy"].source, "workbuddy_importer")

    def test_get_adapter(self):
        from mo_ingest.hook.qoder import QoderHookAdapter
        from mo_ingest.hook.trae import TraeHookAdapter
        from mo_ingest.log.trae import TraeLogImporter
        self.assertIs(get_adapter("qoder"), QoderHookAdapter)
        self.assertIs(get_adapter("qoder", "hook"), QoderHookAdapter)
        # trae 缺省取 modes[0]（log），与显式指定各自命中
        self.assertIs(get_adapter("trae"), TraeLogImporter)
        self.assertIs(get_adapter("trae", "log"), TraeLogImporter)
        self.assertIs(get_adapter("trae", "hook"), TraeHookAdapter)
        with self.assertRaises(ValueError):
            get_adapter("qoder", "log")


class TraeHookTurnTest(unittest.TestCase):
    """Trae hook 的 turn 切分回归（核心）：无 request_set_id，靠 UserPromptSubmit/Stop 配对。"""

    @staticmethod
    def _adapter(tmp):
        from mo_ingest.hook.trae import TraeHookAdapter
        a = TraeHookAdapter(dry_run=True, state_dir=tmp)
        collected = []
        a.emit = collected.append          # 拦下上报，直接看标准化事件
        return a, collected

    def test_按UserPromptSubmit切turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, events = self._adapter(tmp)
            a.handle({"session_id": "s", "cwd": "/tmp/proj",
                      "hook_event_name": "UserPromptSubmit", "prompt": "第一问"}, "UserPromptSubmit")
            a.handle({"session_id": "s", "cwd": "/tmp/proj", "hook_event_name": "PostToolUse",
                      "tool_name": "Read", "tool_input": {"file_path": "a.py"},
                      "tool_response": {}}, "PostToolUse")
            a.handle({"session_id": "s", "cwd": "/tmp/proj", "hook_event_name": "PostToolUse",
                      "tool_name": "Write", "tool_input": {"file_path": "b.py"},
                      "tool_response": {}}, "PostToolUse")
            a.handle({"session_id": "s", "hook_event_name": "Stop",
                      "last_assistant_message": "完成"}, "Stop")
            a.handle({"session_id": "s", "cwd": "/tmp/proj",
                      "hook_event_name": "UserPromptSubmit", "prompt": "第二问"}, "UserPromptSubmit")
            a.handle({"session_id": "s", "cwd": "/tmp/proj", "hook_event_name": "PostToolUse",
                      "tool_name": "Grep", "tool_input": {"pattern": "x"},
                      "tool_response": {}}, "PostToolUse")
            a.handle({"session_id": "s", "hook_event_name": "Stop",
                      "last_assistant_message": "完成2"}, "Stop")

            mains = [e for e in events if "turn_user" in e.metadata]
            stops = [e for e in events if "turn_actions" in e.metadata]
            # 两轮必须切成两个不同 turn（并成一坨是本功能要防的回归）
            self.assertEqual([e.metadata["turn_message_id"] for e in mains], ["s#1", "s#2"])
            self.assertEqual([e.metadata["turn_user"] for e in mains], ["第一问", "第二问"])
            self.assertEqual(stops[0].metadata["turn_message_id"], "s#1")
            self.assertEqual(stops[0].metadata["action_count"], "2")
            self.assertEqual(stops[1].metadata["turn_message_id"], "s#2")
            self.assertEqual(stops[1].metadata["action_count"], "1")
            # source 为平台原始值，家族由服务端归类
            self.assertTrue(all(e.metadata["source"] == "trae_hook" for e in events))
            # agent_id 取 cwd basename（对齐 log 导入的项目粒度）
            self.assertTrue(all(e.agent_id == "proj" for e in events))

    def test_工具io_kind与失败标记(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, events = self._adapter(tmp)
            a.handle({"session_id": "s", "cwd": "/tmp/p", "hook_event_name": "UserPromptSubmit",
                      "prompt": "问"}, "UserPromptSubmit")
            a.handle({"session_id": "s", "hook_event_name": "PostToolUse", "tool_name": "Read",
                      "tool_input": {"file_path": "a.py"}, "tool_response": {}}, "PostToolUse")
            a.handle({"session_id": "s", "hook_event_name": "PostToolUse",
                      "tool_name": "RunCommand", "tool_input": {"command": "ls"},
                      "tool_response": {"exit_code": 1}}, "PostToolUse")
            subs = [e for e in events if "action_full" in e.metadata]
            self.assertEqual(subs[0].metadata["io_kind"], "file_read")
            # RunCommand 不在映射表内 → 认不出不猜
            self.assertEqual(subs[1].metadata["io_kind"], "tool_other")
            self.assertIn("（失败）", subs[1].memory_summary)

    def test_无前置提问时跳过(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, events = self._adapter(tmp)
            # 中途启用：没见过 UserPromptSubmit，不硬凑
            a.handle({"session_id": "s", "hook_event_name": "PostToolUse", "tool_name": "Read",
                      "tool_input": {}, "tool_response": {}}, "PostToolUse")
            a.handle({"session_id": "s", "hook_event_name": "Stop",
                      "last_assistant_message": "x"}, "Stop")
            self.assertEqual(events, [])


class ZeroDependencyTest(unittest.TestCase):
    """接入层必须零依赖：整层不 import mo_sdk（Hook 端整目录拷走即可跑）。"""

    def test_整层不导入mo_sdk(self):
        code = (
            "import sys;"
            "import mo_ingest, mo_ingest.cli, mo_ingest.otlp, mo_ingest.registry,"
            "mo_ingest.hook.qoder, mo_ingest.hook.trae,"
            "mo_ingest.log.trae, mo_ingest.log.workbuddy;"
            "bad=[m for m in sys.modules if m=='mo_sdk' or m.startswith('mo_sdk.')];"
            "assert not bad, 'mo_sdk 被导入了: %s' % bad;"
            "print('ok')"
        )
        r = subprocess.run([sys.executable, "-c", code], cwd=REPO_ROOT,
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("ok", r.stdout)

    def test_hook干跑stdout恒为空JSON(self):
        # Qoder 与 Trae 都要满足：宿主解析 stdout 当 HookOutput，回显文本会被误当决策
        cases = [
            ("qoder", '{"session_id":"probe-s1","request_set_id":"probe-r1",'
                      '"hook_event_name":"UserPromptSubmit","prompt":"探针"}'),
            ("trae", '{"session_id":"probe-s2","cwd":"/tmp/probe",'
                     '"hook_event_name":"UserPromptSubmit","prompt":"探针"}'),
        ]
        for platform, event in cases:
            with self.subTest(platform=platform), tempfile.TemporaryDirectory() as tmp:
                env = dict(os.environ, MO_HOOK_DRY_RUN="1")
                env.pop("MO_API_KEY", None)
                r = subprocess.run(
                    [sys.executable, "-m", "mo_ingest", "--platform", platform,
                     "--mode", "hook", "--path", tmp],
                    input=event, capture_output=True, text=True, cwd=REPO_ROOT, env=env,
                )
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(r.stdout, "{}")
                self.assertIn("DRY_RUN", r.stderr)


class VocabularyJsonTest(unittest.TestCase):
    """跨语言契约一致性（架构重构 M0 / 步骤 3）。

    与 Java 侧 ``ContractVocabularyTest`` 读**同一份** ``contract/vocabulary.json``：
    本类断言 Python 常量与之一致，那一侧断言 Java 常量与之一致。
    任一侧漂移，两处测试中必有一处变红——这就是「逐字一致」的机器保证。
    """

    _CONTRACT = Path(REPO_ROOT) / "contract" / "vocabulary.json"

    @classmethod
    def setUpClass(cls):
        with open(cls._CONTRACT, encoding="utf-8") as f:
            cls.spec = json.load(f)

    def test_契约文件可被定位(self):
        self.assertTrue(self._CONTRACT.is_file(),
                        "找不到 %s（跨语言契约的唯一真相源）" % self._CONTRACT)

    def test_io_kind词表与契约文件逐字一致(self):
        # 用 list 比对：顺序也是契约的一部分（Java 侧 VOCABULARY 注明「顺序即文档顺序」）
        self.assertEqual(list(contract.IO_KIND_VOCABULARY), self.spec["io_kind_vocabulary"])

    def test_io_kind生命周期常量一致(self):
        self.assertEqual(contract.IO_KIND_LIFECYCLE, self.spec["io_kind_lifecycle"])

    def test_operation归一值与契约文件一致(self):
        self.assertEqual(set(contract.VALID_OPERATIONS), set(self.spec["operation_canonical"]))

    def test_operation别名归一与契约文件一致(self):
        """R7：别名 → 归一值的映射。任一侧的 operation 归一表漂移，这里立即变红。"""
        aliases = self.spec.get("operation_aliases")
        self.assertIsInstance(aliases, dict)
        self.assertTrue(aliases, "契约文件必须含非空 operation_aliases")
        default = self.spec["operation_default"]
        for alias, expected in aliases.items():
            self.assertEqual(contract.normalize_operation(alias), expected,
                             "normalize_operation(%r) 与契约文件不一致" % alias)
            self.assertEqual(contract.normalize_operation(alias.upper()), expected,
                             "normalize_operation 应大小写不敏感：%r" % alias)
        self.assertEqual(contract.normalize_operation("没见过的"), default)
        self.assertEqual(contract.normalize_operation(None), default)
        self.assertEqual(contract.normalize_operation(""), default)
        for op in self.spec["operation_canonical"]:
            self.assertEqual(contract.normalize_operation(op), op, "归一值应幂等：%r" % op)

    def test_mo_sdk归一表与契约文件一致(self):
        """mo_sdk 是 op_normalized 的**主生产者**，其 ``_OP_MAP`` 是同一契约的第二份 Python 拷贝。

        旧实现用 ``raw.upper()`` 兜底，会把 compact/delete 原样吐成 "COMPACT"/"DELETE"
        这类非 canonical 串——这条断言把「三份拷贝逐字一致」变成机器可验的事实。
        """
        from mo_sdk.otel_exporter import _normalize_op

        aliases = self.spec["operation_aliases"]
        default = self.spec["operation_default"]
        for alias, expected in aliases.items():
            self.assertEqual(
                _normalize_op({"operation": alias, "metadata": {}}), expected,
                "mo_sdk _normalize_op(%r) 与契约文件不一致" % alias)
        self.assertEqual(_normalize_op({"operation": "没见过的"}), default)
        self.assertEqual(_normalize_op({}), default)
        # sdk 专有覆写：store + is_update=True → UPDATE（契约表之外的额外规则）
        self.assertEqual(
            _normalize_op({"operation": "store", "metadata": {"is_update": True}}), "UPDATE")


if __name__ == "__main__":
    unittest.main()
