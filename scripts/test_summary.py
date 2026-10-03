"""Summarize test results read from real execution artifacts.

为什么需要它：README 里的测试数字以前是手写的——改了测试忘了改文档，
两边就慢慢对不上了。而这个脚本只读真实产物（Surefire XML / Vitest JSON），
想改数字就只能真的去跑测试。

它刻意**不**做的事：

* 不把单元测试、集成测试、真实模型评测加成一个"总数"——它们的运行条件完全不同，
  相加出来的数字既不能复现也没有意义；
* 产物不存在时不报 0，而是明确写"未执行"——0 和"没跑"是两件事，
  把后者写成前者正是这类文档最容易骗人的地方；
* 真实模型评测未运行时显示"未执行"，不沿用上一次的结果。

用法::

    python scripts/test_summary.py                # 人类可读
    python scripts/test_summary.py --markdown     # 可直接贴进 README
    python scripts/test_summary.py --strict       # 任何一层缺失就以非零退出

产物由这些命令产生::

    cd backend  && ./mvnw test                      # 单元测试
    cd backend  && ./mvnw -Pintegration-test test   # 集成回归
    cd frontend && npm run test:json                # 组件测试
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]

BACKEND_UNIT_REPORTS = ROOT / "backend" / "target" / "surefire-reports"
BACKEND_INTEGRATION_REPORTS = ROOT / "backend" / "target" / "surefire-reports-integration"
FRONTEND_REPORT = ROOT / "frontend" / ".reports" / "vitest.json"

# 需要外部模型才能执行的测试：它们被跳过是**预期行为**，但不能因此消失
LIVE_MODEL_MARKERS = ("LiveTest", "AgentEvalLive")


@dataclass
class Summary:
    """一层测试的统计结果。"""

    total: int = 0
    passed: int = 0
    failed: int = 0
    errors: int = 0
    skipped: int = 0
    skipped_names: list[str] = field(default_factory=list)
    newest_artifact: float = 0.0

    @property
    def executed(self) -> int:
        return self.total - self.skipped

    def live_model_skipped(self) -> list[str]:
        return [name for name in self.skipped_names if any(m in name for m in LIVE_MODEL_MARKERS)]


def _newest(paths: list[Path]) -> float:
    return max((p.stat().st_mtime for p in paths), default=0.0)


def read_surefire(report_dir: Path) -> Summary | None:
    """读取 Surefire XML。返回 None 表示**没有产物**（未执行），而不是 0 项通过。"""
    reports = sorted(report_dir.glob("TEST-*.xml"))
    if not reports:
        return None

    summary = Summary(newest_artifact=_newest(reports))
    for report in reports:
        root = ElementTree.parse(report).getroot()  # noqa: S314 - 本地构建产物，非不可信输入
        summary.total += int(root.get("tests", 0))
        summary.errors += int(root.get("errors", 0))
        summary.failed += int(root.get("failures", 0))
        summary.skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            if case.find("skipped") is not None:
                summary.skipped_names.append(f"{case.get('classname', '?')}#{case.get('name', '?')}")
    summary.passed = summary.total - summary.failed - summary.errors - summary.skipped
    return summary


def read_vitest(report: Path) -> Summary | None:
    """读取 Vitest 的 JSON reporter 输出。"""
    if not report.is_file():
        return None
    data = json.loads(report.read_text(encoding="utf-8"))

    summary = Summary(newest_artifact=report.stat().st_mtime)
    summary.total = int(data.get("numTotalTests", 0))
    summary.failed = int(data.get("numFailedTests", 0))
    summary.skipped = int(data.get("numPendingTests", 0)) + int(data.get("numTodoTests", 0))
    summary.passed = int(data.get("numPassedTests", 0))
    for suite in data.get("testResults", []):
        for assertion in suite.get("assertionResults", []):
            if assertion.get("status") in {"pending", "todo", "skipped"}:
                summary.skipped_names.append(assertion.get("fullName") or assertion.get("title", "?"))
    return summary


def format_when(timestamp: float) -> str:
    if timestamp == 0.0:
        return "未知"
    return datetime.fromtimestamp(timestamp).strftime("%Y-%m-%d %H:%M")


def render_text(layers: list[tuple[str, Summary | None, str]]) -> str:
    lines = ["测试统计（数字来自真实执行产物，不是手写的）", ""]
    for name, summary, hint in layers:
        lines.append(f"{name}")
        if summary is None:
            lines.append(f"  未执行 —— 没有找到产物。生成方式：{hint}")
            lines.append("")
            continue
        lines.append(f"  执行/总数 : {summary.executed}/{summary.total}")
        lines.append(f"  通过      : {summary.passed}")
        lines.append(f"  失败      : {summary.failed}")
        lines.append(f"  错误      : {summary.errors}")
        lines.append(f"  跳过      : {summary.skipped}")
        for skipped in summary.skipped_names:
            lines.append(f"      · {skipped}")
        lines.append(f"  产物时间  : {format_when(summary.newest_artifact)}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def render_markdown(layers: list[tuple[str, Summary | None, str]]) -> str:
    lines = [
        "| 层 | 执行/总数 | 通过 | 失败 | 跳过 | 最近验证 |",
        "|---|---:|---:|---:|---:|---|",
    ]
    for name, summary, hint in layers:
        if summary is None:
            lines.append(f"| {name} | 未执行 | — | — | — | 需要 `{hint}` |")
            continue
        lines.append(
            f"| {name} | {summary.executed}/{summary.total} | {summary.passed} | "
            f"{summary.failed + summary.errors} | {summary.skipped} | {format_when(summary.newest_artifact)} |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="从真实测试产物汇总统计")
    parser.add_argument("--markdown", action="store_true", help="输出 Markdown 表格")
    parser.add_argument("--strict", action="store_true", help="任何一层未执行就以非零退出")
    args = parser.parse_args(argv)

    layers: list[tuple[str, Summary | None, str]] = [
        (
            "后端单元测试（不含 integration 标签）",
            read_surefire(BACKEND_UNIT_REPORTS),
            "cd backend && ./mvnw test",
        ),
        (
            "后端集成回归（-Pintegration-test，需 MySQL/PGVector/Redis）",
            read_surefire(BACKEND_INTEGRATION_REPORTS),
            "cd backend && ./mvnw -Pintegration-test test",
        ),
        (
            "前端组件测试",
            read_vitest(FRONTEND_REPORT),
            "cd frontend && npm run test:json",
        ),
    ]

    print(render_markdown(layers) if args.markdown else render_text(layers), end="")

    # 真实模型评测单独列出：它没跑是正常的，但必须看得见
    backend = layers[0][1]
    if backend is not None:
        live = backend.live_model_skipped()
        if live:
            print("\n真实模型评测：未执行（以下测试被显式跳过，需模型 Key 与 RUN_LIVE_AGENT_EVAL=true）")
            for name in live:
                print(f"  · {name}")
        elif any(m in " ".join(backend.skipped_names) for m in LIVE_MODEL_MARKERS):
            print("\n真实模型评测：见上方跳过清单")

    missing = [name for name, summary, _ in layers if summary is None]
    if missing:
        print(f"\n注意：{len(missing)} 层没有产物，上面的表格里它们显示为「未执行」而不是 0。")
        if args.strict:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
