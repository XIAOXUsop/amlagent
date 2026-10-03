"""Require a reviewed disposition for every blocking dependency finding."""

import datetime as dt
import json
import sys
from pathlib import Path

from classify_dependency_scan import blocking_findings


def check_triage(log: str, triage: dict, today: dt.date) -> list[str]:
    problems = []
    actual = {
        (package, version, cve): score for package, version, cves in blocking_findings(log) for cve, score in cves
    }
    if not actual:
        return ["扫描日志没有可解析的阻断项，不能用空集合证明处置完整"]
    for field in ("scanRun", "scanCommit", "reviewedOn", "owner", "nextReview"):
        if not triage.get(field):
            problems.append(f"处置清单缺少 {field}")
    try:
        reviewed_on = dt.date.fromisoformat(triage["reviewedOn"])
        next_review = dt.date.fromisoformat(triage["nextReview"])
        if reviewed_on > today:
            problems.append("处置清单的 reviewedOn 晚于今天")
        if next_review < reviewed_on:
            problems.append("处置清单的 nextReview 早于 reviewedOn")
        if next_review < today:
            problems.append("处置清单的 nextReview 已过期，需重新核验上游版本")
    except (KeyError, TypeError, ValueError):
        problems.append("reviewedOn 或 nextReview 不是有效日期")
    recorded = {}
    for group in triage.get("groups", []):
        for field in ("package", "version", "affected", "classification", "disposition", "evidence"):
            if not group.get(field):
                problems.append(f"处置组缺少 {field}")
        if not group.get("cves"):
            problems.append("处置组没有 CVE")
        for item in group.get("cves", []):
            if not item.get("id") or not isinstance(item.get("cvss"), (int, float)):
                problems.append("处置组的 CVE 缺少编号或 CVSS 分数")
                continue
            identity = (group.get("package"), group.get("version"), item.get("id"))
            if identity in recorded:
                problems.append(f"重复登记 {identity}")
            recorded[identity] = item.get("cvss")
    for identity in sorted(actual.keys() - recorded.keys(), key=str):
        problems.append(f"未处置的阻断项：{identity}")
    for identity in sorted(recorded.keys() - actual.keys(), key=str):
        problems.append(f"清单项目已不在本次扫描中：{identity}")
    for identity in actual.keys() & recorded.keys():
        if actual[identity] != recorded[identity]:
            problems.append(f"CVSS 与本次扫描不符：{identity}")
    return problems


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_dependency_triage.py <scan-log> <triage-json>")
    log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
    triage = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    problems = check_triage(log, triage, dt.date.today())
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        raise SystemExit(1)
    count = sum(len(group["cves"]) for group in triage["groups"])
    print(f"本次扫描的 {count} 条阻断项均有包版本、证据、处置、负责人和复查日期")
