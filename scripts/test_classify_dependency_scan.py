"""依赖扫描分类器的回归测试。

第一个用例是真实事故的复现（2026-09-18）：日志里既有
`[INFO] Completed processing batch 59/198 (30%) in 429ms`，
又有真扫出来的 CVSS 9.8 漏洞。旧的正则因为那个裸的 `429` 把后者的结论盖掉了，
把「有漏洞」报成「只是限流」。**这条断言就是为了让那个 bug 回不来。**
"""

from __future__ import annotations

import unittest

from classify_dependency_scan import MESSAGES, classify

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


if __name__ == "__main__":
    unittest.main()
