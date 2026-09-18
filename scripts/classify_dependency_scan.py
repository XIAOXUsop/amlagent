"""Classify an OWASP dependency-check run so CI reports the real reason it failed.

── 为什么要有这个脚本 ──────────────────────────────────────────────

这个分类原先直接写在 CI 的 shell 里，判定模式是：

    grep -qE "Failed to process CVE-|...|NVD returned|429"

那个裸的 `429` 本意是 HTTP 429（NVD 在没有 API Key 时限流）。但它匹配到了：

    [INFO] Completed processing batch 59/198 (30%) in 429ms

——**一个处理耗时**。于是 2026-09-18 那次「真的扫出了 CVSS 9.8 的依赖漏洞」
被报成「只是限流，不是发现漏洞」，正好把安全门禁说成可以忽略。
报错信息主动误导，比沉默更糟。

所以这里把判定写进一个可测的脚本，并让那次误判成为回归用例
（`test_classify_dependency_scan.py`）。

── 判定顺序不能改 ────────────────────────────────────────────────

**先看「有没有真发现」。** dependency-check 只有在确实扫出达到阈值的漏洞时
才会打印 `One or more dependencies were identified with vulnerabilities`，
这是确凿信号，优先于任何数据源判断：两个信号同时出现时，
「有漏洞」才是需要人立刻行动的那一个。

用法：`python scripts/classify_dependency_scan.py <dependency-check 日志>`
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 真发现漏洞的确凿标志。dependency-check 在写出这份总结时才说明它扫到了东西。
FINDINGS_MARKER = "One or more dependencies were identified with vulnerabilities"

# 数据源不可用的标志。**不要往里加裸的数字**——见模块开头那段。
DATA_SOURCE_PATTERNS = (
    re.compile(r"Failed to process CVE-"),
    re.compile(r"Unable to continue dependency-check analysis"),
    re.compile(r"Error updating the NVD"),
    re.compile(r"NVD returned"),
)

# 出现 429 时必须同时出现的语境词。单独一个 429 什么都不是：
# 它可能是耗时（`in 429ms`）、字节数、行号。
RATE_LIMIT_CONTEXT = ("NVD", "HTTP", "status", "Too Many Requests")

FINDINGS_MESSAGE = (
    "::error::依赖扫描发现达到 CVSS 阈值的依赖漏洞——这是**真的**，"
    "请查看报告产物（dependency-check-report.html / dependency-scan.log）并升级受影响的依赖。"
)
DATA_SOURCE_MESSAGE = (
    "::error::依赖扫描未能完成：漏洞库数据源不可用（多半是 NVD 限流）。"
    "这**不是**发现依赖漏洞，而是这一次检查根本没跑成。配置 NVD_API_KEY 可解决。"
)
UNKNOWN_MESSAGE = (
    "::error::依赖扫描以未知原因失败：既没有发现漏洞的确凿证据，也没有可识别的数据源错误。"
    "请查看 dependency-scan.log 的完整输出。"
)


def _is_rate_limit(line: str) -> bool:
    """429 只有在 HTTP/NVD 语境里才算限流，单独出现不算。"""
    return "429" in line and any(context in line for context in RATE_LIMIT_CONTEXT)


def classify(log: str) -> str:
    """返回 `findings` / `data-source` / `unknown` 三者之一。

    顺序有意如此：先判 `findings`。两个信号同时出现时，「有漏洞」是更该被
    人看到的那一个，不该被数据源噪音盖掉。
    """
    if FINDINGS_MARKER in log:
        return "findings"
    for line in log.splitlines():
        if any(pattern.search(line) for pattern in DATA_SOURCE_PATTERNS) or _is_rate_limit(line):
            return "data-source"
    return "unknown"


MESSAGES = {
    "findings": FINDINGS_MESSAGE,
    "data-source": DATA_SOURCE_MESSAGE,
    "unknown": UNKNOWN_MESSAGE,
}


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("用法：classify_dependency_scan.py <dependency-check 日志>", file=sys.stderr)
        return 2

    path = Path(argv[1])
    if not path.is_file():
        # 日志不在（比如扫描根本没启动）：这属于未知失败，但要说清是哪种未知。
        print("kind=unknown")
        print(
            f"::error::依赖扫描没有产出日志（扫描可能根本没启动）。期望的日志路径：{path}",
        )
        return 0

    kind = classify(path.read_text(encoding="utf-8", errors="replace"))
    # `kind=` 这一行是给测试与后续脚本读的，别改格式。
    print(f"kind={kind}")
    print(MESSAGES[kind])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
