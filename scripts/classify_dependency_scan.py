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

── 它还被缓存步骤用来判断「这份数据能不能存」 ──────────────────────

CI 里 NVD 数据目录是要缓存的（见 `.github/workflows/ci.yml`）。但**它不能无条件存**：

  · 扫描因为「数据源不可用」而失败时，那个目录里是**没下完的**数据，
    甚至残留着 `odc.update.lock`。把它存进缓存，下一个运行恢复后会在
    同一个地方再失败一次——自己喂自己。
    实测过一次：job 日志里出现
        Lock file found `…/odc.update.lock`
        Existing update in progress; waiting for update to complete
        UpdateException: Unable to obtain an exclusive lock on the H2 database
        NoDataException: No documents exist
    而那一轮存下来的缓存只有 **23.2 MB**（正常是 114.9 MB）。

  · 运行被**取消**时（并发取消），扫描步骤根本没跑完，数据同样不可信。

所以传 `--github-output <path>` 时，它会追加一行 `verdict=<kind>`；
CI 的保存步骤只在 `verdict` 是 `pass`（扫描通过）或 `findings`（扫出漏洞，
但**数据是好的**——漏洞判定是可信的）时才存。其余一律不存。
"扫出漏洞"要存、"没跑成"不存，这个区分正是这个脚本存在的意义。
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
    "::error::依赖扫描未能完成：漏洞库数据源不可用。这**不是**发现依赖漏洞，"
    "而是这一次检查根本没跑成——**这一轮没有检查任何依赖**。"
    "最常见的原因是没配 `NVD_API_KEY`：NVD 的 API 有 39 万余条记录，"
    "匿名访问会被限流，日志里表现为连着一串 "
    "`NVD API request failures are occurring; retrying request for the Nth time`，"
    "最后 `NvdApiException: NVD Returned Status Code: 429`。"
    "密钥免费（https://nvd.nist.gov/developers/request-an-api-key），"
    "申请后加为仓库 secret `NVD_API_KEY`。"
)
DATA_SOURCE_KEYED_MESSAGE = (
    "::error::依赖扫描未能完成：漏洞库数据源不可用，但**这一次 `NVD_API_KEY` 是配了的**。"
    "所以不是「没密钥」那回事——可能是密钥配额用尽、被吊销，或者 NVD 侧故障。"
    "请查看 `dependency-scan.log` 里的具体状态码。"
    "**这一轮没有检查任何依赖**。"
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

# 允许把 NVD 数据目录写进缓存的判定结果。
# `pass`（扫描通过）与 `findings`（扫出漏洞）都表示**这一次的数据是完整可用的**；
# `data-source` / `unknown` 表示压根没跑成，数据不可信。
CACHEABLE_VERDICTS = ("pass", "findings")

# dependency-check 在**没配密钥**时会打这行建议。它出现在日志里，
# 就说明这一次是匿名访问——那 39 万条记录是下不完的。
NO_KEY_MARKER = "An NVD API Key was not provided"


def message_for(kind: str, log: str) -> str:
    """挑该说哪句话。

    `data-source` 要分两种，因为处理方式完全不同：**没配密钥**（去申请一个，
    免费的，两分钟）和**配了密钥还失败**（配额用尽 / 被吊销 / NVD 侧故障）。
    说成同一句会把人引到错误的排查方向。
    """
    if kind == "data-source" and NO_KEY_MARKER not in log:
        return DATA_SOURCE_KEYED_MESSAGE
    return MESSAGES[kind]


def _append_github_output(path: str, verdict: str) -> None:
    """把判定结果写进 GitHub Actions 的 $GITHUB_OUTPUT，供保存缓存的步骤判断。"""
    with Path(path).open("a", encoding="utf-8") as handle:
        handle.write(f"verdict={verdict}\n")


def main(argv: list[str]) -> int:
    args = argv[1:]
    github_output: str | None = None
    if "--github-output" in args:
        index = args.index("--github-output")
        if index + 1 >= len(args):
            print("--github-output 需要一个路径参数", file=sys.stderr)
            return 2
        github_output = args[index + 1]
        del args[index : index + 2]

    if len(args) != 1:
        print(
            "用法：classify_dependency_scan.py <dependency-check 日志> [--github-output <path>]",
            file=sys.stderr,
        )
        return 2

    path = Path(args[0])
    if not path.is_file():
        # 日志不在（比如扫描根本没启动）：这属于未知失败，但要说清是哪种未知。
        if github_output:
            _append_github_output(github_output, "unknown")
        print("kind=unknown")
        print(
            f"::error::依赖扫描没有产出日志（扫描可能根本没启动）。期望的日志路径：{path}",
        )
        return 0

    log_text = path.read_text(encoding="utf-8", errors="replace")
    kind = classify(log_text)
    if github_output:
        _append_github_output(github_output, kind)
    # `kind=` 这一行是给测试与后续脚本读的，别改格式。
    print(f"kind={kind}")
    print(message_for(kind, log_text))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
