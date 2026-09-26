"""依赖修复版可用性检查的回归测试。

**全部离线。** 网络那一层（NVD / Maven Central）在 `judge` 的边界上被替换掉，
这里测的是判定本身——判定错了，再准的数据也没用。

两个用例是照着真实数据写的，它们各自代表一类**看起来一样、结论相反**的情况：

* `TestExactVersionList`：NVD 对 mysql-connector-j 的两条 CVE 写的是
  「受影响版本 9.7.0、9.7.1」——**是显式列举，不是 ≤9.7.1**。
  9.6.0 不在其中。把这种写法当区间读，会得出「只能退回 9.6.0」的错误结论。
* `TestJudge.test_only_cross_line_is_available_and_is_labelled_that_way`：
  spring-framework 在 6.2.x 线上无解，而 7.x 完全绕得开。脚本**必须**把这两件事分开说
  ——那不是补丁级升级。最初那版脚本直接报「有可用修复版：7.0.9」，
  等于把一次大迁移说成一个小升级。
* `TestJudge.test_versions_older_than_current_are_never_offered_as_the_fix`：
  第二版改口径时又错到另一头——它取「所有干净版本里最小的那个」，
  于是给 spring-security 报了 **2.0.0**、给 mysql-connector-j 报了 **8.0.31**。
  它们确实不在受影响区间里（区间从 6.5 / 9.7 起），但**比当前版本还旧**。
  把降级当成修复版报出去，比不报更坏。
"""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import check_dependency_fix_availability as mod
from check_dependency_fix_availability import (
    AffectedSet,
    Unavailable,
    affected_versions,
    judge,
    line_prefix,
    main,
    parse_version,
)


def version_tuple(text: str) -> tuple:
    return parse_version(text)


class TestVersionOrdering(unittest.TestCase):
    def test_numeric_segments_compare_as_numbers(self):
        # 字符串比较会判 `9.7.0 > 26.7.0`（'9' > '2'）——正是要防的那一类错
        self.assertGreater(version_tuple("26.7.0"), version_tuple("9.7.0"))
        self.assertGreater(version_tuple("6.2.20"), version_tuple("6.2.19"))
        self.assertGreater(version_tuple("4.1.138.Final"), version_tuple("4.1.136"))

    def test_trailing_zeros_are_equal(self):
        self.assertEqual(version_tuple("1.0"), version_tuple("1.0.0"))
        self.assertEqual(version_tuple("6.2"), version_tuple("6.2.0.0"))

    def test_mixed_numeric_and_alpha_at_the_same_position_does_not_raise(self):
        # 回归：早先每一段是 `(1, int)` / `(0, str)` 的二元组，
        # 在**同一位**上一边是数字一边是字母时直接抛
        # `TypeError: '>' not supported between instances of 'int' and 'str'`。
        # 这不是假设——`4.1.138.Final` 这类版本号在 Central 上真实存在。
        for left, right in (("6.2.19", "6.2.19.Final"), ("1.0", "1.0-beta"), ("2.4.20", "2.4.19.Final")):
            with self.subTest(left=left, right=right):
                compared = version_tuple(left) > version_tuple(right)
                self.assertIsInstance(compared, bool, "比较必须给出结果，而不是抛 TypeError")


class TestPrereleaseFiltering(unittest.TestCase):
    def test_prereleases_are_recognised(self):
        for text in ("7.1.0-M1", "7.1.0-RC1", "26.7.0-SNAPSHOT", "2.0.0-alpha1", "1.0.0-beta"):
            with self.subTest(text=text):
                self.assertIsNotNone(mod.PRERELEASE.search(text))

    def test_real_releases_are_not_mistaken_for_prereleases(self):
        # `.Final` 是正式版的一部分，不能被当成预发布滤掉
        for text in ("4.1.138.Final", "6.2.19", "26.7.0", "3.5.16"):
            with self.subTest(text=text):
                self.assertIsNone(mod.PRERELEASE.search(text))


class TestLinePrefix(unittest.TestCase):
    def test_prefix_is_taken_before_the_placeholder(self):
        self.assertEqual(line_prefix("6.2.x"), "6.2.")
        self.assertEqual(line_prefix("9.x"), "9.")
        self.assertEqual(line_prefix("0.1.x"), "0.1.")


class TestExactVersionList(unittest.TestCase):
    """NVD 的显式版本列举：**不是区间，不能当区间读。**"""

    def setUp(self):
        self.mysql = AffectedSet(cve="CVE-2026-60586", exact={"9.7.0", "9.7.1"})

    def test_listed_versions_are_affected(self):
        self.assertTrue(self.mysql.contains("9.7.0"))
        self.assertTrue(self.mysql.contains("9.7.1"))

    def test_unlisted_versions_are_not_affected(self):
        # 9.6.0 比 9.7.0 低，但**不在受影响清单里**——这正是「退回 9.6.0 理论上可行」
        # 那句判断的依据。把它当区间读就会得出相反的结论。
        self.assertFalse(self.mysql.contains("9.6.0"))
        self.assertFalse(self.mysql.contains("26.7.0"))
        self.assertFalse(self.mysql.contains("9.7.2"))

    def test_describe_names_the_versions(self):
        self.assertIn("9.7.0", self.mysql.describe())
        self.assertIn("9.7.1", self.mysql.describe())


class TestRange(unittest.TestCase):
    """NVD 的区间写法：`6.2.0 ≤ v < 6.2.20`。"""

    def setUp(self):
        self.spring = AffectedSet(cve="CVE-2026-47884", ranges=[("6.2.0", None, None, "6.2.20")])

    def test_inside_the_range(self):
        for text in ("6.2.0", "6.2.19"):
            with self.subTest(text=text):
                self.assertTrue(self.spring.contains(text))

    def test_outside_the_range_on_both_sides(self):
        self.assertFalse(self.spring.contains("6.1.9"))
        self.assertFalse(self.spring.contains("6.2.20"))
        self.assertFalse(self.spring.contains("7.0.9"))

    def test_end_including_is_inclusive(self):
        inclusive = AffectedSet(cve="X", ranges=[(None, None, "6.2.19", None)])
        self.assertTrue(inclusive.contains("6.2.19"))
        self.assertFalse(inclusive.contains("6.2.20"))


class TestJudge(unittest.TestCase):
    """判定本身。网络层在这三条用例里被换掉。"""

    ENTRY = {"group": "g", "artifact": "a", "line": "6.2.x", "counts": 1, "cves": ["CVE-2026-0001"]}

    def _judge(self, versions, affected, entry=None):
        with (
            mock.patch.object(mod, "central_versions", return_value=versions),
            mock.patch.object(mod, "affected_versions", return_value=affected),
        ):
            return judge(entry or self.ENTRY, {})

    def test_same_line_fix_is_reported_as_such(self):
        # 6.2.21 已经绕得开，且它就在 6.2.x 线上 → 这是补丁级的路
        verdict = self._judge(
            ["6.2.19", "6.2.21"], AffectedSet(cve="CVE-2026-0001", ranges=[("6.2.0", None, None, "6.2.20")])
        )
        self.assertEqual(verdict.status, "upgrade-available")
        self.assertEqual(verdict.in_line, "6.2.21")

    def test_only_cross_line_is_available_and_is_labelled_that_way(self):
        # 真实情形：6.2.x 线上无解，7.0.0 是**往上第一个**绕得开的
        verdict = self._judge(
            ["6.2.19", "7.0.0", "7.0.9"],
            AffectedSet(cve="CVE-2026-0001", ranges=[("6.2.0", None, None, "6.2.20")]),
        )
        self.assertEqual(verdict.status, "cross-line-only")
        self.assertEqual(verdict.in_line, "6.2.19")
        self.assertEqual(verdict.cross_line, "7.0.0")
        # 措辞必须点明「不做判断」，否则读的人会把它当成一次小升级
        self.assertIn("大版本迁移", verdict.detail)

    def test_versions_older_than_current_are_never_offered_as_the_fix(self):
        # 回归：第一版实现取的是「所有干净版本里最小的那个」，于是
        # 给 spring-security 报了 **2.0.0**、给 mysql-connector-j 报了 **8.0.31**
        # ——它们确实不在受影响区间里（区间从 6.5 / 9.7 起），但**比当前版本还旧**。
        # 把降级当成修复版报出去，比不报更坏。
        entry = dict(self.ENTRY, group="com.mysql", artifact="mysql-connector-j", line="9.x")
        verdict = self._judge(
            ["8.0.31", "9.6.0", "9.7.0", "26.7.0"],
            AffectedSet(cve="CVE-2026-0001", exact={"9.7.0", "9.7.1"}),
            entry=entry,
        )
        self.assertEqual(verdict.cross_line, "26.7.0")

    def test_nothing_available(self):
        # 真实情形：pgvector，`0.1.6` 就是最新，且它仍在受影响区间内
        entry = dict(self.ENTRY, group="com.pgvector", artifact="pgvector", line="0.1.x")
        verdict = self._judge(
            ["0.1.6"],
            AffectedSet(cve="CVE-2026-18022", ranges=[(None, None, None, "0.8.6")]),
            entry=entry,
        )
        self.assertEqual(verdict.status, "none-available")
        self.assertEqual(verdict.in_line, "0.1.6")

    def test_line_with_no_versions_is_unknown_not_none_available(self):
        # 清单里的 `line` 写错了（或者上游把整条线改名了）时，Central 上没有任何该分支的版本。
        # 这时**不能**说「没有可用修复版」——那是拿一个根本没比过的东西下结论。
        verdict = self._judge(["9.7.0"], AffectedSet(cve="CVE-2026-0001", exact={"9.7.0"}))
        self.assertEqual(verdict.status, "unknown")
        self.assertIn("6.2.x", verdict.detail)

    def test_prerelease_does_not_count_as_a_fix(self):
        # `6.2.21-M1` 不是一条能升级的路
        verdict = self._judge(
            ["6.2.19", "6.2.21-M1"],
            AffectedSet(cve="CVE-2026-0001", ranges=[("6.2.0", None, None, "6.2.20")]),
        )
        self.assertEqual(verdict.status, "none-available")

    def test_unavailable_cve_data_is_unknown_not_no_fix(self):
        # 取不到受影响版本 → 「无法判定」。报成「没有可用修复版」会让人以为已经查过。
        with (
            mock.patch.object(mod, "central_versions", return_value=["6.2.19"]),
            mock.patch.object(mod, "affected_versions", side_effect=Unavailable("NVD 这条记录没有 configurations")),
        ):
            verdict = judge(self.ENTRY, {})
        self.assertEqual(verdict.status, "unknown")
        self.assertNotEqual(verdict.status, "none-available")

    def test_unavailable_central_is_unknown(self):
        with (
            mock.patch.object(mod, "affected_versions", return_value=AffectedSet(cve="X", exact={"1"})),
            mock.patch.object(mod, "central_versions", side_effect=Unavailable("Central 取不到")),
        ):
            verdict = judge(self.ENTRY, {})
        self.assertEqual(verdict.status, "unknown")

    def test_empty_cve_list_is_unknown(self):
        # 空清单**不是**「全部安全」——它是「什么都没检查」
        entry = dict(self.ENTRY, cves=[])
        verdict = judge(entry, {})
        self.assertEqual(verdict.status, "unknown")
        self.assertIn("空的", verdict.detail)


class TestAffectedVersionsUnavailable(unittest.TestCase):
    """`affected_versions` 必须把「公告存在但没有区间」也报成 Unavailable。"""

    def test_record_without_configurations_raises(self):
        payload = json.dumps({"vulnerabilities": [{"cve": {"id": "CVE-2026-0001"}}]}).encode()
        with mock.patch.object(mod, "fetch", return_value=payload):
            with self.assertRaises(Unavailable):
                affected_versions("CVE-2026-0001", {})

    def test_missing_cve_raises(self):
        with mock.patch.object(mod, "fetch", return_value=json.dumps({"vulnerabilities": []}).encode()):
            with self.assertRaises(Unavailable):
                affected_versions("CVE-2026-9999", {})

    def test_exact_versions_are_extracted_from_cpe(self):
        payload = json.dumps(
            {
                "vulnerabilities": [
                    {
                        "cve": {
                            "id": "CVE-2026-0001",
                            "configurations": [
                                {
                                    "nodes": [
                                        {
                                            "cpeMatch": [
                                                {
                                                    "vulnerable": True,
                                                    "criteria": "cpe:2.3:a:oracle:mysql_connector\\/j:9.7.0:*:*:*:*:*:*:*",
                                                },
                                                {
                                                    "vulnerable": True,
                                                    "criteria": "cpe:2.3:a:oracle:mysql_connector\\/j:9.7.1:*:*:*:*:*:*:*",
                                                },
                                            ],
                                        }
                                    ],
                                }
                            ],
                        },
                    }
                ],
            }
        ).encode()
        with mock.patch.object(mod, "fetch", return_value=payload):
            result = affected_versions("CVE-2026-0001", {})
        self.assertEqual(result.exact, {"9.7.0", "9.7.1"})


class TestMain(unittest.TestCase):
    def _write_watchlist(self, payload: dict) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        json.dump(payload, handle, ensure_ascii=False)
        handle.close()
        return Path(handle.name)

    def _run(self, payload: dict, argv: list[str]) -> tuple[int, str]:
        path = self._write_watchlist(payload)
        buffer = io.StringIO()
        # `main` 每条依赖之间会 sleep 一下避开 NVD 的匿名限流（5 次/30 秒）。
        # 测试里没有真请求，这个 sleep 只会让这一步白等 20 秒——**测试不该考核等待**。
        with mock.patch.object(mod.time, "sleep", return_value=None):
            with contextlib.redirect_stdout(buffer):
                code = main(["--watchlist", str(path)] + argv)
        return code, buffer.getvalue()

    def test_empty_watchlist_is_an_error_not_a_pass(self):
        # 空清单会让「没有任何依赖有可用修复版」这句话恒真
        code, output = self._run({"entries": []}, [])
        self.assertEqual(code, 1)
        self.assertIn("什么都没检查", output)

    def test_missing_watchlist_file_is_an_error(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = main(["--watchlist", str(Path(tempfile.gettempdir()) / "definitely-missing.json")])
        self.assertEqual(code, 1)

    def test_unknown_verdict_exits_two(self):
        payload = {"entries": [{"group": "g", "artifact": "a", "line": "1.x", "counts": 1, "cves": []}]}
        code, _ = self._run(payload, [])
        self.assertEqual(code, 2)

    def test_counts_mismatching_the_cve_list_is_unknown_not_silent(self):
        """
        条目里声明的阻断条数与实际列出的 CVE 数对不上时，**必须报出来**。

        清单的 `_comment` 曾声称「counts …… 供脚本与 README 对账用，脚本会说出来」，
        而代码里从来没有比对过它——脚本不扫描本项目，无从知道 README 里写的当前阻断数。
        能确凿检查的是条目**内部**的一致性，这条用例把它钉住。

        实测背景（2026-09-22）：mysql-connector-j 那条已经清掉却还留在清单里，
        于是每周的 dependency-fix-watch 会为一个已修好的依赖报「上游已发布修复版」而变红。
        """
        payload = {"entries": [{"group": "g", "artifact": "a", "line": "1.x", "counts": 3, "cves": ["CVE-2026-0001"]}]}
        code, output = self._run(payload, [])

        self.assertEqual(code, 2, output)
        self.assertIn("对不上", output)

    def test_fail_on_available_exits_three(self):
        payload = {
            "entries": [{"group": "g", "artifact": "a", "line": "6.2.x", "counts": 1, "cves": ["CVE-2026-0001"]}]
        }
        with (
            mock.patch.object(mod, "central_versions", return_value=["6.2.19", "7.0.9"]),
            mock.patch.object(
                mod,
                "affected_versions",
                return_value=AffectedSet(cve="CVE-2026-0001", ranges=[("6.2.0", None, None, "6.2.20")]),
            ),
        ):
            quiet, _ = self._run(payload, [])
            loud, output = self._run(payload, ["--fail-on-available"])
        # 默认退出码是 0（监控任务用它读报告），加了开关才用退出码喊出来
        self.assertEqual(quiet, 0)
        self.assertEqual(loud, 3)
        # 而且必须说清那是跨线，不是补丁
        self.assertIn("不是补丁级升级", output)


if __name__ == "__main__":
    unittest.main()
