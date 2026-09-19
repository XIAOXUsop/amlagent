"""检查 README 里的测试数字是否等于真实产物里的数字。

    python scripts/check_readme_test_numbers.py

── 为什么要有它 ──────────────────────────────────────────────────

README 里那张「最近一次本机验证」表，**数字是手抄的**。2026-09-19 发现它写着
「549/550」，而真实值是 **575/576**——差了 26 项，是加测试时忘了改文档。

这和本仓库另外几处是同一族问题：**检查绿着、事实是错的**。手抄的数字必然漂，
而且漂了没有任何东西会提醒你。所以这里让 CI 拿脚本的输出与 README 逐格比对。

── 它刻意不做什么 ────────────────────────────────────────────────

* **不改 README**。发现不一致就报错并把两边的值都打出来，改不改由人决定——
  一个会自己改文档的检查，等于把"文档错了"这件事也一起静默了。
* **不把三层合成一个总数**。它们的运行条件不同，相加没有意义
  （同 `test_summary.py` 的取舍）。
* 产物不存在时**不报 0**，而是跳过那一层的比对并说明——「没跑」与「跑了 0 项」
  是两件事，把后者写成前者正是这类检查最容易骗人的地方。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_summary import (  # noqa: E402 - 需要先把它所在目录加进 sys.path
    BACKEND_INTEGRATION_REPORTS,
    BACKEND_UNIT_REPORTS,
    FRONTEND_REPORT,
    read_surefire,
    read_vitest,
)

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"

# README 表格里每行的第一个单元格（层名）→ 对应的产物读取方式。
# 层名要与 `test_summary.py` 里的一致——**故意用同一个字符串**，
# 那边改名这边就会在下面的"README 里找不到这一行"上报错，而不是静默跳过。
LAYERS = (
    ("后端单元测试（不含 `integration` 标签）", lambda: read_surefire(BACKEND_UNIT_REPORTS)),
    (
        "后端集成回归（`-Pintegration-test`，需 MySQL/PGVector/Redis）",
        lambda: read_surefire(BACKEND_INTEGRATION_REPORTS),
    ),
    ("前端组件测试", lambda: read_vitest(FRONTEND_REPORT)),
)

# README 表格行形如： | 层名 | 执行/总数 | 通过 | 失败 | 跳过 | 最近验证 |
_ROW = re.compile(
    r"^\|\s*(?P<layer>[^|]+?)\s*\|\s*(?P<executed>\d+/\d+)\s*\|\s*(?P<passed>\d+)\s*\|"
    r"\s*(?P<failed>\d+)\s*\|\s*(?P<skipped>\d+)\s*\|"
)


def parse_readme_table(text: str) -> dict[str, tuple[str, int, int, int]]:
    """抽出 README 表格里每一层的 `(执行/总数, 通过, 失败, 跳过)`。"""
    rows: dict[str, tuple[str, int, int, int]] = {}
    for line in text.splitlines():
        m = _ROW.match(line)
        if m:
            rows[m.group("layer").strip()] = (
                m.group("executed"),
                int(m.group("passed")),
                int(m.group("failed")),
                int(m.group("skipped")),
            )
    return rows


def main() -> int:
    readme_rows = parse_readme_table(README.read_text(encoding="utf-8"))
    problems: list[str] = []
    skipped: list[str] = []

    for layer, read in LAYERS:
        summary = read()
        if summary is None:
            # 「没跑」不等于「跑了 0 项」——不比对，但要说出来。
            skipped.append(layer)
            continue
        if layer not in readme_rows:
            problems.append(
                f"README 表格里找不到「{layer}」这一行。如果是在 test_summary.py 里改了层名，这里要跟着改。"
            )
            continue
        want = (f"{summary.executed}/{summary.total}", summary.passed, summary.failed, summary.skipped)
        got = readme_rows[layer]
        if got != want:
            problems.append(
                f"{layer}：README 写 {got[0]}（通过 {got[1]} / 失败 {got[2]} / 跳过 {got[3]}），"
                f"实测 {want[0]}（通过 {want[1]} / 失败 {want[2]} / 跳过 {want[3]}）"
            )

    for layer in skipped:
        print(f"跳过比对（没有产物，不是 0 项）：{layer}")

    if problems:
        print()
        for p in problems:
            print(f"[x] README 的测试数字与实测不一致：{p}")
        print()
        print("要更新 README，跑：python scripts/test_summary.py --markdown")
        return 1

    compared = len(LAYERS) - len(skipped)
    if compared == 0:
        # **这是关键的一条**：一层都没比对成，说明产物一个都没找到——
        # 这时返回 0 等于「用空集证明合格」。CI 里如果没有把产物送过来，
        # 这个检查会恒过；宁可红，也不要一个恒真的绿灯。
        print("[x] 一层都没能比对——三层测试产物一个都没找到。")
        print("   这不是「通过」，是「什么都没检查」。")
        print("   本地跑请先产出：cd backend && ./mvnw test；cd frontend && npm run test:json")
        return 1

    print(f"README 的测试数字与实测一致（比对 {compared}/{len(LAYERS)} 层）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
