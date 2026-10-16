"""【mo_ingest/解析器】hook 适配器与 log 导入器的行为单测（纯 stdlib unittest，零网络）。

既有 21 条全围绕契约（词表/归一）；本文件补上此前无测试的解析本体：
1. `hook/base.summarize_tool`——各平台共用的工具调用一行摘要；
2. `hook/qoder.QoderHookAdapter`——turn 分组、事件分发、跨进程状态、Stop 汇总；
3. `log/workbuddy` 纯函数——io_kind/operation 分类、动作短句、toolCallId 合并、项目路径提取。
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from mo_ingest.contract import io_kind_of_text, io_kind_of_tool
from mo_ingest.hook.base import summarize_tool
from mo_ingest.hook.qoder import QoderHookAdapter
from mo_ingest.log import workbuddy as wb


class SummarizeToolTest(unittest.TestCase):
    """工具摘要：常见参数键优先，取不到退化为 JSON，再取不到只留工具名。"""

    def test_常见键按优先级命中(self):
        self.assertEqual(summarize_tool("Bash", {"command": "ls -la"}), "Bash: ls -la")
        self.assertEqual(summarize_tool("Read", {"path": "/a", "file_path": "/b"}),
                         "Read: /b")  # command 缺失时 file_path 先于 path

    def test_无已知键退化JSON(self):
        s = summarize_tool("Foo", {"xyz": 1})
        self.assertTrue(s.startswith("Foo: {"))

    def test_字符串入参与空入参(self):
        self.assertEqual(summarize_tool("Bar", "hello"), "Bar: hello")
        self.assertEqual(summarize_tool("Bar", None), "Bar")
        self.assertEqual(summarize_tool("", {"a": 1}).split(":")[0], "tool")


class QoderHookAdapterTest(unittest.TestCase):
    """Qoder hook：UserPromptSubmit→工具→Stop 的最小闭环（emit 替身收集，不发网络）。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.events = []
        self.ad = QoderHookAdapter(dry_run=True, state_dir=self.tmp.name)
        self.ad.emit = lambda ev: self.events.append(ev)

    def tearDown(self):
        self.tmp.cleanup()

    def _ev(self) -> dict:
        return self.events[-1]

    def test_turn_key_优先request_set_id缺省退session(self):
        self.assertEqual(self.ad._turn_key({"request_set_id": "r1", "session_id": "s1"}), "r1")
        self.assertEqual(self.ad._turn_key({"session_id": "s1"}), "s1:noset")
        self.assertEqual(self.ad._turn_key({}), "qoder-session:noset")

    def test_用户提问落主事件并存状态(self):
        self.ad.handle({"hook_event_name": "UserPromptSubmit", "session_id": "s1",
                        "request_set_id": "r1", "prompt": "改个bug"}, "UserPromptSubmit")
        ev = self._ev()
        self.assertEqual(ev.metadata["turn_user"], "改个bug")
        self.assertEqual(ev.metadata["io_kind"], "lifecycle")
        self.assertEqual(ev.metadata["turn_message_id"], "r1")
        self.assertEqual(ev.operation, "store")

    def test_工具事件带io_kind与递增action_idx(self):
        self.ad.handle({"session_id": "s1", "request_set_id": "r1", "prompt": "p"},
                       "UserPromptSubmit")
        self.ad.handle({"session_id": "s1", "request_set_id": "r1",
                        "tool_name": "Read", "tool_input": {"file_path": "/a.py"}},
                       "PostToolUse")
        ev = self._ev()
        self.assertEqual(ev.metadata["io_kind"], "file_read")  # 经 TOOL_IO_KIND 真名映射
        self.assertEqual(ev.metadata["action_idx"], "0")
        # 失败变体：摘要带（失败）标记
        self.ad.handle({"session_id": "s1", "request_set_id": "r1",
                        "tool_name": "Write", "tool_input": {"file_path": "/b"}},
                       "PostToolUseFailure")
        self.assertIn("（失败）", self._ev().memory_summary)
        self.assertEqual(self._ev().metadata["action_idx"], "1")

    def test_stop汇总并清状态(self):
        self.ad.handle({"session_id": "s1", "request_set_id": "r9", "prompt": "p"},
                       "UserPromptSubmit")
        self.ad.handle({"session_id": "s1", "request_set_id": "r9",
                        "tool_name": "search_memory", "tool_input": {"query": "x"}},
                       "PostToolUse")
        self.ad.handle({"session_id": "s1", "request_set_id": "r9", "outcome": "done"}, "Stop")
        ev = self._ev()
        self.assertEqual(ev.metadata["action_count"], "1")
        self.assertEqual(json.loads(ev.metadata["turn_actions"]),
                         ["search_memory: x"])
        self.assertEqual(ev.metadata["turn_outcome"], "done")
        # 状态已被 drop：再来一次 Stop 不再产生事件
        n = len(self.events)
        self.ad.handle({"session_id": "s1", "request_set_id": "r9"}, "Stop")
        self.assertEqual(len(self.events), n)

    def test_未见提问的Stop不硬凑(self):
        self.ad.handle({"session_id": "s1", "request_set_id": "rX"}, "Stop")
        self.assertEqual(self.events, [])

    def test_未注册事件名被忽略(self):
        # run() 的 HANDLED 分发之外：直接 handle 未支持的名字不应抛
        self.ad.handle({}, "NoSuchEvent")
        self.assertEqual(self.events, [])


class WorkBuddyClassifyTest(unittest.TestCase):
    """审计事件分类纯函数：命令文本优先 → messageKey 回退；词表外的值不许出现。"""

    def test_io_kind_命令文本优先(self):
        ev = {"messageParams": {"command": "Grep \"/x.js\""}}
        self.assertEqual(wb.classify_io_kind(ev), "content_search")

    def test_io_kind_回退messageKey(self):
        self.assertEqual(wb.classify_io_kind({"messageKey": "tool.readWithRule"}), "file_read")
        self.assertEqual(wb.classify_io_kind({"messageKey": "tool.writeWithRule"}), "file_write")
        self.assertEqual(wb.classify_io_kind({"messageKey": "file.delete"}), "file_write")
        self.assertEqual(wb.classify_io_kind({"eventType": "WebFetch"}), "tool_other")
        self.assertEqual(wb.classify_io_kind({}), "tool_other")

    def test_operation四值(self):
        self.assertEqual(wb.classify_operation({"messageKey": "file.delete"}), "EXPIRE")
        self.assertEqual(wb.classify_operation({"messageKey": "tool.readWithRule"}), "READ")
        self.assertEqual(wb.classify_operation({"messageKey": "tool.writeWithRule"}), "UPDATE")
        self.assertEqual(wb.classify_operation({"eventType": "WebFetch"}), "WRITE")

    def test_动作短句各分支(self):
        self.assertEqual(wb.action_text({"messageParams": {"command": "ls -la\nsecond"}}),
                         "运行命令：ls -la")
        self.assertEqual(wb.action_text({"eventType": "WebFetch",
                                         "messageParams": {"target": "https://a.b"}}),
                         "抓取网页：https://a.b")
        s = wb.action_text({"messageKey": "tool.readWithRule",
                            "messageParams": {"paths": "/a.py, /b.py"}})
        self.assertEqual(s, "读文件：/a.py 等 2 个")
        self.assertEqual(wb.action_text({"eventType": "Mystery"}), "Mystery")

    def test_命令文本取值优先级(self):
        ev = {"messageParams": {"command": "  "}, "commandPreview": "rg x"}
        self.assertEqual(wb._command_text(ev), "rg x")  # 全空白不算数

    def test_toolcall合并成一条(self):
        # 一次调用落两条：命令沙箱一条 + 文件放行一条（paths 在后者）
        evs = [
            {"toolCallId": "t1", "messageParams": {"command": "cat a.py"},
             "decision": "allowed"},
            {"toolCallId": "t1", "messageParams": {"paths": "/a.py"},
             "decision": "blocked"},
        ]
        out = wb.collapse_by_toolcall(evs)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["decision"], "blocked")  # 取最定案
        self.assertEqual(out[0]["messageParams"]["paths"], "/a.py")  # 兄弟借 paths
        self.assertEqual(out[0]["_facet_count"], 2)
        # 不同 toolCallId 不合并；无 toolCallId 退回 id
        out2 = wb.collapse_by_toolcall([{"id": "a"}, {"id": "b"}])
        self.assertEqual(len(out2), 2)


class WorkBuddyProjectOfTest(unittest.TestCase):
    """项目路径提取：正则取 /Users/x/{Documents,…}/ 后 1~2 段，噪声段跳过。"""

    def test_常规项目两段(self):
        ev = {"messageParams": {"p": "编辑 /Users/zxc/Documents/ai/proj/f.md"}}
        self.assertEqual(wb.project_of(ev, 2), "ai/proj")

    def test_噪声段不认作项目(self):
        ev = {"messageParams": {"p": "/Users/zxc/Documents/WorkBuddy/stuff"}}
        self.assertIsNone(wb.project_of(ev, 2))

    def test_无路径返回None且commandPreview兜底(self):
        self.assertIsNone(wb.project_of({"messageParams": {"p": "没有路径"}}, 2))
        ev = {"commandPreview": "cat /home/dev/Code/demo/main.py"}
        self.assertEqual(wb.project_of(ev, 1), "demo")


class ContractVocabularyAnchorTest(unittest.TestCase):
    """解析器产出的 io_kind 必须落在封闭词表内——把解析层与 S1 契约焊在一起。"""

    VOCAB_IO_KINDS = None

    def setUp(self):
        vocab = json.loads((Path(__file__).resolve().parents[2]
                            / "contract" / "vocabulary.json").read_text(encoding="utf-8"))
        self.VOCAB_IO_KINDS = set(vocab["io_kind_vocabulary"])

    def test_工具映射与文本猜测都在词表内(self):
        for kind in list(__import__("mo_ingest.contract", fromlist=["TOOL_IO_KIND"])
                         .TOOL_IO_KIND.values()):
            self.assertIn(kind, self.VOCAB_IO_KINDS, f"TOOL_IO_KIND 产出越表: {kind}")
        for text in ("cat f", "write something", "Grep x", "ls", "记住这个"):
            k = io_kind_of_text(text, default=None)
            if k:
                self.assertIn(k, self.VOCAB_IO_KINDS)
        self.assertEqual(io_kind_of_tool("Read"), "file_read")
        self.assertIsNone(io_kind_of_tool("NoSuchTool"))


if __name__ == "__main__":
    unittest.main()
