"""依赖扫描分类器的回归测试。

第一个用例是真实事故的复现（2026-09-18）：日志里既有
`[INFO] Completed processing batch 59/198 (30%) in 429ms`，
又有真扫出来的 CVSS 9.8 漏洞。旧的正则因为那个裸的 `429` 把后者的结论盖掉了，
把「有漏洞」报成「只是限流」。**这条断言就是为了让那个 bug 回不来。**

后半部分是第二个真实事故的复现（2026-09-19）：缓存里带回了上一轮残留的更新锁，
扫描压根没跑成，而旧的保存条件会**把那份没下完的数据又存回缓存**，
让下一个运行在同一处再挂一次。断言的是「没跑成」的判定不得进入可缓存集合。
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from classify_dependency_scan import CACHEABLE_VERDICTS, MESSAGES, classify, main

# 真实日志的节选：耗时里带 429，同时确实扫出了高危依赖。
# 旧实现把它判成 data-source，这正是事故本身。
REAL_INCIDENT_LOG = """\
[INFO] Completed processing batch 59/198 (30%) in 429ms
[INFO] Updating CPE data
[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0':
[ERROR] opennlp-tools-2.5.9.jar (pkg:maven/org.apache.opennlp/opennlp-tools@2.5.9): CVE-2026-82617(10.0)
[ERROR] kotlin-stdlib-1.9.25.jar (pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@1.9.25): CVE-2026-53914(9.8)
[INFO] BUILD FAILURE
"""

# NVD 在无 API Key 时限流的真实形态。
RATE_LIMITED_LOG = """\
[WARN] Unable to continue dependency-check analysis.
[ERROR] Error updating the NVD Data; the NVD returned a 429 status code
[INFO] BUILD FAILURE
"""

# 限流但不带 HTTP 语境的写法（只有 NVD 与 429 同现）。
RATE_LIMITED_BARE_LOG = """\
[ERROR] NVD returned 429 Too Many Requests
[INFO] BUILD FAILURE
"""

# 既没有漏洞结论、也没有数据源错误：不能假装知道原因。
UNKNOWN_LOG = """\
[INFO] Generating report
[ERROR] Failed to execute goal org.owasp:dependency-check-maven:12.2.2:check
"""

# 真实事故（2026-09-19）：缓存里带回了上一轮残留的更新锁，扫描压根没跑成。
# 那一轮存下来的数据目录只有 23.2 MB（正常 114.9 MB），也就是说**存进缓存的
# 是一份没下完的库**——下一个运行恢复它会在同一个地方再失败一次。
LOCKED_DATASOURCE_LOG = """\
[INFO] Lock file found `/home/runner/.m2/dependency-check-data/odc.update.lock`
[INFO] Existing update in progress; waiting for update to complete
[WARNING] Unable to update 1 or more Cached Web DataSource, using local data instead. Results may not include recent vulnerabilities.
[ERROR] Unable to continue dependency-check analysis.
[ERROR] \tUpdateException: Unable to obtain an exclusive lock on the H2 database to perform updates
[ERROR] \tcaused by WriteLockException: Unable to obtain the update lock, skipping the database update.
[ERROR] \tNoDataException: No documents exist
[INFO] BUILD FAILURE
"""


class ClassifyTests(unittest.TestCase):
    def test_real_incident_is_classified_as_findings(self) -> None:
        """耗时里的 429 不得把「真发现漏洞」盖成「数据源挂了」。"""
        self.assertEqual(classify(REAL_INCIDENT_LOG), "findings")

    def test_rate_limit_is_classified_as_data_source(self) -> None:
        self.assertEqual(classify(RATE_LIMITED_LOG), "data-source")

    def test_bare_nvd_429_is_classified_as_data_source(self) -> None:
        self.assertEqual(classify(RATE_LIMITED_BARE_LOG), "data-source")

    def test_unknown_failure_stays_unknown(self) -> None:
        self.assertEqual(classify(UNKNOWN_LOG), "unknown")

    def test_bare_number_429_alone_is_not_rate_limit(self) -> None:
        """单独一个 429（耗时、字节数、行号）不构成限流证据。"""
        self.assertEqual(classify("[INFO] Finished in 429ms"), "unknown")

    def test_findings_wins_when_both_signals_present(self) -> None:
        """两个信号同时出现时，「有漏洞」是更该被看到的那个。"""
        both = RATE_LIMITED_LOG + "\n" + REAL_INCIDENT_LOG
        self.assertEqual(classify(both), "findings")

    def test_every_kind_has_a_message(self) -> None:
        for kind in ("findings", "data-source", "unknown"):
            self.assertIn(kind, MESSAGES)
            self.assertTrue(MESSAGES[kind].startswith("::error::"))

    def test_locked_database_is_data_source_not_findings(self) -> None:
        """残留更新锁导致「没跑成」，不得被当成「扫出漏洞」。"""
        self.assertEqual(classify(LOCKED_DATASOURCE_LOG), "data-source")


class CacheVerdictTests(unittest.TestCase):
    """`--github-output` 写出的判定，决定 NVD 数据目录能不能进缓存。

    这一组的分界线是**「扫出漏洞」要存、「没跑成」不存**：
    前者说明数据是完整可用的（漏洞判定可信），后者说明数据本身不可信。
    """

    def _run(self, log_text: str) -> dict[str, str]:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "dependency-scan.log"
            log.write_text(log_text, encoding="utf-8")
            out = Path(tmp) / "github_output"
            out.write_text("", encoding="utf-8")
            code = main(["classify_dependency_scan.py", str(log), "--github-output", str(out)])
            self.assertEqual(code, 0)
            return dict(line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line)

    def test_pass_and_findings_are_cacheable(self) -> None:
        self.assertEqual(set(CACHEABLE_VERDICTS), {"pass", "findings"})

    def test_findings_writes_a_cacheable_verdict(self) -> None:
        verdict = self._run(REAL_INCIDENT_LOG)["verdict"]
        self.assertEqual(verdict, "findings")
        self.assertIn(verdict, CACHEABLE_VERDICTS)

    def test_locked_database_writes_a_non_cacheable_verdict(self) -> None:
        """**这条是本次事故的回归用例**：带锁的坏数据绝不能进缓存。"""
        verdict = self._run(LOCKED_DATASOURCE_LOG)["verdict"]
        self.assertEqual(verdict, "data-source")
        self.assertNotIn(verdict, CACHEABLE_VERDICTS)

    def test_rate_limit_writes_a_non_cacheable_verdict(self) -> None:
        verdict = self._run(RATE_LIMITED_LOG)["verdict"]
        self.assertNotIn(verdict, CACHEABLE_VERDICTS)

    def test_unknown_writes_a_non_cacheable_verdict(self) -> None:
        verdict = self._run(UNKNOWN_LOG)["verdict"]
        self.assertNotIn(verdict, CACHEABLE_VERDICTS)

    def test_missing_log_writes_unknown(self) -> None:
        """扫描根本没启动时也要写判定——否则保存步骤会读到空值。"""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "github_output"
            out.write_text("", encoding="utf-8")
            code = main(
                [
                    "classify_dependency_scan.py",
                    str(Path(tmp) / "不存在.log"),
                    "--github-output",
                    str(out),
                ]
            )
            self.assertEqual(code, 0)
            self.assertEqual(out.read_text(encoding="utf-8").strip(), "verdict=unknown")

    def test_github_output_is_optional(self) -> None:
        """不带这个参数时行为不变（老用法不能被破坏）。"""
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "dependency-scan.log"
            log.write_text(REAL_INCIDENT_LOG, encoding="utf-8")
            self.assertEqual(main(["classify_dependency_scan.py", str(log)]), 0)

    def test_missing_value_for_github_output_is_a_usage_error(self) -> None:
        self.assertEqual(
            main(["classify_dependency_scan.py", "x.log", "--github-output"]),
            2,
        )


if __name__ == "__main__":
    unittest.main()
