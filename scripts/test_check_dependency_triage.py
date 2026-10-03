import copy
import datetime as dt
import unittest

from check_dependency_triage import check_triage

LOG = """[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0':
[ERROR] demo-1.jar (pkg:maven/example/demo@1): CVE-2026-12345(9.1)
"""
TRIAGE = {
    "scanRun": "https://example.invalid/run/1",
    "scanCommit": "abcdef0",
    "reviewedOn": "2026-09-26",
    "owner": "@maintainer",
    "nextReview": "2026-10-03",
    "groups": [
        {
            "package": "example/demo",
            "version": "1",
            "affected": "v1",
            "classification": "awaiting-fix",
            "disposition": "review weekly",
            "evidence": "upstream advisory",
            "cves": [{"id": "CVE-2026-12345", "cvss": 9.1}],
        }
    ],
}


class DependencyTriageTest(unittest.TestCase):
    def test_matching_findings_have_reviewed_disposition(self):
        self.assertEqual(check_triage(LOG, TRIAGE, dt.date(2026, 9, 26)), [])

    def test_new_finding_cannot_hide_behind_old_triage(self):
        changed = LOG + "[ERROR] other-1.jar (pkg:maven/example/other@1): CVE-2026-23456(8.0)\n"
        self.assertTrue(any("未处置" in problem for problem in check_triage(changed, TRIAGE, dt.date(2026, 9, 26))))

    def test_review_date_expires(self):
        self.assertTrue(any("已过期" in problem for problem in check_triage(LOG, TRIAGE, dt.date(2026, 10, 4))))

    def test_invalid_review_date_reports_problem_instead_of_crashing(self):
        changed = dict(TRIAGE, nextReview=None)
        self.assertTrue(any("有效日期" in problem for problem in check_triage(LOG, changed, dt.date(2026, 9, 26))))

    def test_changed_severity_requires_new_review(self):
        changed = copy.deepcopy(TRIAGE)
        changed["groups"][0]["cves"][0]["cvss"] = 8.0
        self.assertTrue(any("CVSS" in problem for problem in check_triage(LOG, changed, dt.date(2026, 9, 26))))

    def test_unparseable_log_cannot_pass(self):
        self.assertTrue(check_triage("[ERROR] scan failed", TRIAGE, dt.date(2026, 9, 26)))


if __name__ == "__main__":
    unittest.main()
