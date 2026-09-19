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

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

from classify_dependency_scan import (
    CACHEABLE_VERDICTS,
    MESSAGES,
    blocking_findings,
    classify,
    findings_summary,
    main,
    message_for,
)

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

# 真实日志（2026-09-19）：没配 NVD_API_KEY 时**从零**下载漏洞库的完整形态。
# 39 万条记录、匿名访问被限流、重试 31 次后放弃。这一轮 4 分 37 秒就结束了。
NO_KEY_RATE_LIMIT_LOG = """\
[WARNING] An NVD API Key was not provided - it is highly recommended to use an NVD API key as the update can take a VERY long time without an API Key
[INFO] NVD API has 395,446 records in this update
[WARNING] NVD API request failures are occurring; retrying request for the 31st time
[ERROR] Error updating the NVD Data
org.owasp.dependencycheck.data.update.exception.UpdateException: Error updating the NVD Data
    Caused by: io.github.jeremylong.openvulnerability.client.nvd.NvdApiException: NVD Returned Status Code: 429
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

    def test_no_key_rate_limit_is_data_source(self) -> None:
        """匿名访问下不完那 39 万条记录——这是「没跑成」，不是「扫过了没问题」。"""
        self.assertEqual(classify(NO_KEY_RATE_LIMIT_LOG), "data-source")


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


class MessageTests(unittest.TestCase):
    """`data-source` 要分两种说法：没配密钥 / 配了还失败。处理方式完全不同。"""

    def test_no_key_log_tells_you_to_get_a_key(self) -> None:
        message = message_for("data-source", NO_KEY_RATE_LIMIT_LOG)
        self.assertIn("NVD_API_KEY", message)
        self.assertIn("nvd.nist.gov/developers/request-an-api-key", message)

    def test_keyed_log_does_not_blame_a_missing_key(self) -> None:
        """密钥明明配了，还说「去申请一个」会把人引到错误的排查方向。"""
        message = message_for("data-source", RATE_LIMITED_LOG)
        self.assertNotIn("/request-an-api-key", message)

    def test_other_kinds_fall_through_to_the_plain_message(self) -> None:
        self.assertEqual(message_for("findings", NO_KEY_RATE_LIMIT_LOG), MESSAGES["findings"])
        self.assertEqual(message_for("unknown", RATE_LIMITED_LOG), MESSAGES["unknown"])

    def test_every_message_says_this_run_checked_nothing(self) -> None:
        """数据源失败最容易被误读成「扫过了，没问题」——两种说法都要堵住。"""
        for log in (NO_KEY_RATE_LIMIT_LOG, RATE_LIMITED_LOG):
            self.assertIn("没有检查任何依赖", message_for("data-source", log))


# 节选自 2026-09-19 的真实 job 日志（apply 之后同一份格式）。
# 关键在于它**同时**含同一个依赖的两种形态：
#   · 上半部分的 `Identified` 行——只有 CVE 号、**没有分数**，不是阻断项
#   · 下半部分的 `[ERROR]` 行——带 `(9.8)` 这样的分数，才是阻断项
# 只按「含 CVE 且含 pkg:maven」去数，会把前者也数进去，条数直接翻倍。
REAL_FINDINGS_LOG = """\
[INFO] spring-core-6.2.19.jar (pkg:maven/org.springframework/spring-core@6.2.19, cpe:2.3:a:vmware:spring_framework:6.2.19:*:*:*:*:*:*:*): CVE-2026-47884, CVE-2026-47890, CVE-2026-47885
[INFO] spring-data-jpa-3.5.13.jar (pkg:maven/org.springframework.data/spring-data-jpa@3.5.13, cpe:2.3:a:vmware:spring_data_jpa:3.5.13:*:*:*:*:*:*:*): CVE-2026-47834
[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0':
[ERROR]
[ERROR] mysql-connector-j-9.7.0.jar (pkg:maven/com.mysql/mysql-connector-j@9.7.0, cpe:2.3:a:oracle:mysql_connector\\/j:9.7.0:*:*:*:*:*:*:*): CVE-2026-60586(7.7), CVE-2026-60623(7.1)
[ERROR] spring-core-6.2.19.jar (pkg:maven/org.springframework/spring-core@6.2.19, cpe:2.3:a:vmware:spring_framework:6.2.19:*:*:*:*:*:*:*): CVE-2026-47884(9.8), CVE-2026-47890(9.8), CVE-2026-47885(7.5)
[ERROR]
[ERROR] See the dependency-check report for more details.
[INFO] BUILD FAILURE
"""


class BlockingFindingsTests(unittest.TestCase):
    """把「在阻塞什么」从日志里数出来，喂给 CI 的摘要输出。"""

    def test_only_scored_entries_count(self) -> None:
        """这条是最要紧的：同依赖的无分数行不是阻断项，不能数进去。"""
        rows = blocking_findings(REAL_FINDINGS_LOG)
        total = sum(len(cves) for _, _, cves in rows)
        self.assertEqual(total, 5, "3 + 2，而不是把上半部分那 3 条无分数行也算一遍")

    def test_sub_threshold_only_dependency_is_not_blocking(self) -> None:
        """spring-data-jpa 只出现在「Identified」里、分数低于阈值——它不是阻断项。

        这条是拿 2026-09-19 的真实日志核过的：那份日志里
        `spring-data-jpa-3.5.13.jar … : CVE-2026-47834` 确实没有分数。
        把它数进去会凭空多报一个"受影响依赖"，而 README 明确记着
        这 8 条分数低于阈值、不阻断。
        """
        self.assertNotIn(
            "spring-data-jpa",
            "\n".join(findings_summary(REAL_FINDINGS_LOG)),
        )

    def test_grouped_by_coordinate_and_version(self) -> None:
        rows = blocking_findings(REAL_FINDINGS_LOG)
        self.assertEqual(
            [(ga, ver) for ga, ver, _ in rows],
            [("org.springframework/spring-core", "6.2.19"), ("com.mysql/mysql-connector-j", "9.7.0")],
        )

    def test_sorted_by_highest_score(self) -> None:
        rows = blocking_findings(REAL_FINDINGS_LOG)
        self.assertEqual(rows[0][2][0], ("CVE-2026-47884", 9.8))
        scores = [cves[0][1] for _, _, cves in rows]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_duplicate_lines_do_not_double_count(self) -> None:
        """同一 (坐标,版本) 重复出现时按 CVE 去重，且分数取最大值。"""
        twice = REAL_FINDINGS_LOG + "\n".join(
            [
                "[ERROR] spring-core-6.2.19.jar (pkg:maven/org.springframework/spring-core@6.2.19):"
                " CVE-2026-47884(1.0), CVE-2026-99999(8.0)",
            ]
        )
        rows = dict(((ga, ver), cves) for ga, ver, cves in blocking_findings(twice))
        cves = dict(rows[("org.springframework/spring-core", "6.2.19")])
        self.assertEqual(cves["CVE-2026-47884"], 9.8, "重复项里的低分不得覆盖高分")
        self.assertIn("CVE-2026-99999", cves)

    def test_clean_log_yields_no_summary_at_all(self) -> None:
        """解析不出条目时返回空——**不打印「阻塞 0 条」**，那会被读成「没有阻断项」。"""
        self.assertEqual(findings_summary(RATE_LIMITED_LOG), [])
        self.assertEqual(findings_summary(UNKNOWN_LOG), [])

    def test_summary_states_the_total_and_the_top_score(self) -> None:
        text = "\n".join(findings_summary(REAL_FINDINGS_LOG))
        self.assertIn("阻塞 5 条", text)
        self.assertIn("org.springframework/spring-core@6.2.19 — 3 条（最高 9.8）", text)
        self.assertIn("com.mysql/mysql-connector-j@9.7.0 — 2 条（最高 7.7）", text)

    def test_short_cve_lists_are_listed_in_full(self) -> None:
        """3 条就全列出来——截断只在真的长的时候用。"""
        text = "\n".join(findings_summary(REAL_FINDINGS_LOG))
        self.assertIn("CVE-2026-47884、CVE-2026-47890、CVE-2026-47885", text)
        self.assertNotIn("等 3 条", text)

    def test_long_cve_lists_are_truncated(self) -> None:
        """spring-core 那种一次 12 条的要收着写，否则注解长得没法读。"""
        many = "[ERROR] x.jar (pkg:maven/g/a@1): " + ", ".join(f"CVE-2026-{10000 + i}(9.8)" for i in range(12))
        text = "\n".join(findings_summary(REAL_FINDINGS_LOG + many))
        self.assertIn("g/a@1 — 12 条（最高 9.8）", text)
        self.assertIn("等 12 条", text)

    def test_main_prints_the_summary_only_for_findings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "findings.log"
            good.write_text(REAL_FINDINGS_LOG, encoding="utf-8")
            bad = Path(tmp) / "rate.log"
            bad.write_text(RATE_LIMITED_LOG, encoding="utf-8")
            for name, expect in ((good, True), (bad, False)):
                with self.subTest(log=name.name):
                    buffer = io.StringIO()
                    with contextlib.redirect_stdout(buffer):
                        self.assertEqual(main(["classify_dependency_scan.py", str(name)]), 0)
                    self.assertEqual("阻塞" in buffer.getvalue(), expect)


if __name__ == "__main__":
    unittest.main()
