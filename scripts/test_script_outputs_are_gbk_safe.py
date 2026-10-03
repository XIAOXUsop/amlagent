"""仓库里所有 Python 脚本的输出都必须能在 GBK 终端里打出来。

── 为什么要有它 ──────────────────────────────────────────────────

2026-09-19 踩了两次同一个坑：

1. `classify_dependency_scan.py` 的 `FINDINGS_MESSAGE` 用了「警告三角」emoji，
   在没有 `PYTHONIOENCODING=utf-8` 的 Windows 默认终端下抛 `UnicodeEncodeError`。
   **那句话只在真的扫出漏洞时才打印**——最该被看到的结论，恰好被编码错误顶掉。
2. 修完之后我又在**新写的** `check_readme_test_numbers.py` 里用了个叉号字符，
   于是「数字不一致」这条本该报出来的错，自己先崩了。

两次都不是"某个字符写错"，而是**"这个字符能不能进 GBK"从来没被检查过**。
所以这里不再逐个盯字符，而是把 `scripts/` 下的每个 `.py` 扫一遍——
**新增脚本自动纳入**，不需要有人记得来加一条。

── 判据 ──────────────────────────────────────────────────────────

字符本身**能不能被 GBK 编码**（与终端一致）。中文字符合法；被挡的是 U+26A0（警告三角）、
U+2717（叉号）、U+2713（对号）这类 ASCII 之外的符号与 emoji。

**这不限制源码里出现中文**——只限制"会被打印/可能被打印"的内容。
检查覆盖整个文件而不只是字符串字面量：注释里的 emoji 不会崩，
但它会让"扫全文件找不可编码字符"的下一处检查产生假告警，一并挡掉更省事。

**本文件自己的夹具用 `chr(0x26A0)` 这样的码点写**，源码是纯 ASCII，
不需要给自己开例外——一个会给自己开例外的检查，离被关掉就不远了。
"""

from __future__ import annotations

import pathlib
import unittest

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent

# 允许含 GBK 编不出字符的文件 —— 当前为空。加进来必须写明理由。
ALLOWED: frozenset[str] = frozenset()


def _encodable_in_gbk(ch: str) -> bool:
    try:
        ch.encode("gbk")
        return True
    except UnicodeEncodeError:
        return False


def _offending_chars(path: pathlib.Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return {c for c in text if not _encodable_in_gbk(c)}


class ScriptEncodingTests(unittest.TestCase):
    def test_scripts_directory_is_not_empty(self) -> None:
        """防止「一个文件都没扫到」被当成「全都合格」——空集也会让下面的断言恒真。"""
        scripts = list(SCRIPTS_DIR.glob("*.py"))
        self.assertGreater(len(scripts), 5, f"scripts/ 下只找到 {len(scripts)} 个 .py，扫描范围不对")
        self.assertIn("classify_dependency_scan.py", {p.name for p in scripts})

    def test_every_script_is_gbk_safe(self) -> None:
        offenders: dict[str, list[str]] = {}
        for path in sorted(SCRIPTS_DIR.glob("*.py")):
            if path.name in ALLOWED:
                continue
            bad = _offending_chars(path)
            if bad:
                offenders[path.name] = sorted(f"U+{ord(c):04X}" for c in bad)
        self.assertEqual(
            offenders,
            {},
            f"以下脚本含 GBK 编不出的字符，在 Windows 默认终端下打印会抛 UnicodeEncodeError：{offenders}",
        )

    def test_the_check_itself_would_catch_a_known_bad_char(self) -> None:
        """反向保证：判据真的会把符号判成不可编码，而不是恒返回"没问题"。

        夹具用 `chr(0x26A0)` 这样的码点写，**不写出字符本身**——否则这个文件自己
        会被上面的全仓扫描抓到，就不得不给它开例外；而一个靠例外活着的检查，
        下次遇到同类问题多半是加例外而不是修代码。
        """
        self.assertFalse(_encodable_in_gbk(chr(0x26A0)))  # 警告三角
        self.assertFalse(_encodable_in_gbk(chr(0x2717)))  # 叉号
        self.assertFalse(_encodable_in_gbk(chr(0x2713)))  # 对号
        self.assertTrue(_encodable_in_gbk("a"))
        self.assertTrue(_encodable_in_gbk("中"))  # 中文在 GBK 里，必须放行


if __name__ == "__main__":
    unittest.main()
