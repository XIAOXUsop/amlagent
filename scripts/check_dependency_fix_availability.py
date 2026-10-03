"""回答一个反复被问、每次都靠手工查的问题：**上游发修复版了没有？**

    python scripts/check_dependency_fix_availability.py
    python scripts/check_dependency_fix_availability.py --json out.json
    python scripts/check_dependency_fix_availability.py --fail-on-available   # 给定时任务用

── 为什么要有它 ──────────────────────────────────────────────────

依赖门禁红着的时候（见 README「依赖安全」），最常被问的是「现在能升了吗」。
这个问题此前只能一次次手工查：先去 NVD 抠 `configurations[].nodes[].cpeMatch[]`
拿到受影响版本，再去 Central 翻版本列表看有没有更靠后的——重复、易漏，
而**答案会随时间变**（上游随时可能发补丁）。人查两轮就不查了。

所以把这两步固定下来。它读 `dependency-watchlist.json`，对每个依赖回答一句话：

    Central 上有没有一个版本，能绕开这些 CVE 的**全部**受影响版本？

── 它只读，这是刻意的 ────────────────────────────────────────────

**不改 pom、不改版本号、不提 PR、不合并依赖升级。** 只报告。
自动升版本意味着在没有跑测试的情况下改产物，而这个仓库的依赖升级
（见 pom 注释：`log4j 2.25.4 → 2.25.5` 是因为新公告的修复线正是它，
**不是**"有更新的就用最新的"）需要逐条读公告、只升到够用的最小版本、再跑全套测试。
那不是脚本能决定的事。

计划里的定时任务是**只读监控**：发现"有可用修复版"就提示人，不代替人做决定。

── 「查不动」必须和「没有」分得开 ──────────────────────────────────

这是本仓库反复踩的那一类坑（见 README 与 `assertion_blind_spots_family`）：
NVD 取不到、Central 取不到、某个 CVE 在 NVD 里没有 `configurations`（只有描述）、
返回体不是预期结构——它们在日志里长得很像"干净"。

所以这里：**任何一个 CVE 的受影响版本取不到，那个依赖的结论就是「无法判定」**，
而不是「没有可用修复版」。同理，一个依赖的 CVE 列表为空也不等于安全，
它会报错退出而不是打印「全部安全」。

退出码：`0` 判定完成 · `1` 用法/配置错误 · `2` 有依赖**无法判定** ·
`3` 有依赖**存在可用修复版**（只在给了 `--fail-on-available` 时返回）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WATCHLIST = Path(__file__).resolve().parent / "dependency-watchlist.json"

NVD_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId={cve}"
CENTRAL_METADATA_URL = "https://repo1.maven.org/maven2/{path}/maven-metadata.xml"

# 预发布版本不参与「有没有修复版」的判断——`7.1.0-M1` 不是一条能升级的路。
PRERELEASE = re.compile(r"[-.](M\d|RC\d|alpha|beta|snapshot|SNAPSHOT|dev)", re.IGNORECASE)


class Unavailable(Exception):
    """取不到判定所需的数据。**与「没有可用修复版」是两回事**，不要合并。"""


# ─────────────────────────── 版本比较 ───────────────────────────


def parse_version(text: str) -> tuple:
    """把 Maven 版本号切成可比较的元组。

    只做**够用**的那一档：数字段按数值比，字母段按原文比，
    并让 `1.0 == 1.0.0`（补零）。不去实现 Maven 完整的版本序——
    这里要判的是"9.7.0 与 26.7.0 谁新""6.2.19 有没有越过 6.2.20"这类问题，
    而 Maven 的完整规则（限定符排序、`Final` 的特殊地位……）在这类比较上并不改变结论。

    每一段都是 `(是否数字, 数值, 原文)` 的三元组：**第二个元素恒为 int、第三个恒为 str**。
    否则 `6.2.19` 与 `6.2.19.Final` 这种在同一位上一边是数字一边是字母的比较
    会直接抛 `TypeError: '>' not supported between instances of 'int' and 'str'`
    ——而那是运行时才炸，不在类型检查里。
    """
    parts: list[tuple[int, int, str]] = []
    for chunk in re.split(r"[.\-_]", text.strip()):
        if not chunk:
            continue
        if chunk.isdigit():
            parts.append((1, int(chunk), ""))
        else:
            parts.append((0, 0, chunk))
    # 把尾部纯零的数字段去掉，让 1.0.0 == 1.0
    while parts and parts[-1] == (1, 0, ""):
        parts.pop()
    return tuple(parts)


# ─────────────────────────── 受影响版本 ───────────────────────────


@dataclass
class AffectedSet:
    """一个 CVE 对某个包声明的受影响版本。

    NVD 有两种写法，**两种都要支持**：
      · 显式列版本（`cpe:2.3:…:mysql_connector/j:9.7.0`）——mysql 那两条就是这样，
        它说的是"9.7.0 与 9.7.1 受影响"，**不是**"≤9.7.1 都受影响"；
      · 区间（`versionStartIncluding` / `versionEndExcluding`）——spring 那批是这样。
    把这两种混为一谈会得出完全相反的结论，所以分开存在这里。
    """

    cve: str
    exact: set[str] = field(default_factory=set)
    ranges: list[tuple] = field(default_factory=list)  # (start_inc, start_exc, end_inc, end_exc)

    def contains(self, version: str) -> bool:
        if version in self.exact:
            return True
        target = parse_version(version)
        for start_inc, start_exc, end_inc, end_exc in self.ranges:
            # 四道闸都是「不在区间内就 continue」；全部走完说明落在区间里。
            # 区间至少有一端（见 `affected_versions`），不会出现四个都为空的那种条目。
            if start_inc and target < parse_version(start_inc):
                continue
            if start_exc and target <= parse_version(start_exc):
                continue
            if end_inc and target > parse_version(end_inc):
                continue
            if end_exc and target >= parse_version(end_exc):
                continue
            return True
        return False

    def describe(self) -> str:
        bits = []
        if self.exact:
            bits.append("受影响版本 " + "、".join(sorted(self.exact, key=parse_version)))
        for start_inc, start_exc, end_inc, end_exc in self.ranges:
            left = f"≥ {start_inc}" if start_inc else (f"> {start_exc}" if start_exc else "")
            right = f"≤ {end_inc}" if end_inc else (f"< {end_exc}" if end_exc else "")
            bits.append(" ".join(x for x in (left, right) if x) or "*")
        return "；".join(bits) if bits else "（该 CVE 没有声明任何受影响版本）"


def fetch(url: str, timeout: int = 40, retries: int = 3) -> bytes:
    """带重试的 GET。失败一律抛 Unavailable——调用方要能把它和「空结果」分开。"""
    token = os.environ.get("NVD_API_KEY")
    headers = {"User-Agent": "amlagent-dependency-fix-check"}
    if token and "nvd.nist.gov" in url:
        headers["apiKey"] = token
    last: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            last = Unavailable(f"HTTP {error.code}")
            # 404 是确定性的，重试没意义；其余（429/5xx）值得等一等
            if error.code == 404:
                break
        except Exception as error:  # noqa: BLE001 - 网络层的异常种类太多，统一成 Unavailable
            last = Unavailable(f"{type(error).__name__}: {error}")
        time.sleep(2 * (attempt + 1))
    raise last if last else Unavailable("未知错误")


def affected_versions(cve: str, cache: dict[str, AffectedSet]) -> AffectedSet:
    """从 NVD 取一条 CVE 声明的受影响版本。取不到就抛 Unavailable。

    **这里有意不按 CPE 的 vendor/product 过滤**，取的是该 CVE 的全部 `cpeMatch`。
    理由是清单里的 CVE 本来就是"因为这条公告讲的是这个包"才被选进来的，
    而 CPE 里的产品名与 Maven 坐标对不上号（`vmware:spring_framework` 对
    `org.springframework:spring-core`、`oracle:mysql_connector/j` 对
    `com.mysql:mysql-connector-j`），硬做映射只会引入另一类错。

    代价是：如果某条公告**同时**列了多个产品，别的产品的区间会被并进来。
    那会让结论偏向"更新版也仍在受影响范围内"——即**偏保守**（少报"有修复版"），
    不会反过来把有洞的版本说成干净的。实测 2026-09-21 的四条：
    `CVE-2026-47884` 的 7 条 cpeMatch 全是 `vmware:spring_framework`，没有这种情况。
    """
    if cve in cache:
        return cache[cve]

    payload = json.loads(fetch(NVD_URL.format(cve=cve)).decode("utf-8", "replace"))
    records = payload.get("vulnerabilities") or []
    if not records:
        raise Unavailable("NVD 里没有这条 CVE")

    item = records[0]["cve"]
    result = AffectedSet(cve=cve)
    for configuration in item.get("configurations") or []:
        for node in configuration.get("nodes") or []:
            for match in node.get("cpeMatch") or []:
                if not match.get("vulnerable", True):
                    continue
                parts = match.get("criteria", "").split(":")
                # cpe:2.3:a:vendor:product:version:...
                if len(parts) > 5 and parts[5] != "*":
                    result.exact.add(parts[5])
                if any(
                    match.get(key)
                    for key in (
                        "versionStartIncluding",
                        "versionStartExcluding",
                        "versionEndIncluding",
                        "versionEndExcluding",
                    )
                ):
                    result.ranges.append(
                        (
                            match.get("versionStartIncluding"),
                            match.get("versionStartExcluding"),
                            match.get("versionEndIncluding"),
                            match.get("versionEndExcluding"),
                        )
                    )

    if not result.exact and not result.ranges:
        # **这正是最该报出来的那一类**：公告存在、区间缺失。此时任何"没命中"
        # 的结论都不是扫描器给的，而是这个脚本自己编的。
        raise Unavailable("NVD 这条记录没有 configurations/cpeMatch，无法判定受影响版本")

    cache[cve] = result
    return result


def central_versions(group: str, artifact: str) -> list[str]:
    """从 Maven Central 的 maven-metadata.xml 取全部版本。

    用 `maven-metadata.xml` 而不是 `search.maven.org` 的搜索接口：后者的索引
    **明显滞后**，2026-09-21 实测它给 spring-core 的"最新"是 6.2.8，
    而这个工程用的是 6.2.19——用它判"上游发了没"会得出相反的结论。
    """
    path = f"{group.replace('.', '/')}/{artifact}"
    try:
        text = fetch(CENTRAL_METADATA_URL.format(path=path)).decode("utf-8", "replace")
    except Unavailable as error:
        raise Unavailable(f"Central 取不到 {group}:{artifact} 的版本列表（{error}）") from error
    versions = re.findall(r"<version>([^<]+)</version>", text)
    if not versions:
        raise Unavailable(f"Central 的元数据里没有版本条目：{group}:{artifact}")
    return versions


# ─────────────────────────── 判定 ───────────────────────────


@dataclass
class Verdict:
    group: str
    artifact: str
    line: str
    counts: int
    status: str  # upgrade-available | cross-line-only | none-available | unknown
    detail: str
    in_line: str | None = None  # 同线最新正式版
    cross_line: str | None = None  # 跨线最小的可用版本
    blocked_cves: list[str] = field(default_factory=list)
    unknown_cves: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "group": self.group,
            "artifact": self.artifact,
            "line": self.line,
            "declaredCounts": self.counts,
            "status": self.status,
            "detail": self.detail,
            "latestInLine": self.in_line,
            "crossLineCandidate": self.cross_line,
            "blockedCves": self.blocked_cves,
            "unknownCves": self.unknown_cves,
        }


def line_prefix(line: str) -> str:
    """`6.2.x` → `6.2.`；`9.x` → `9.`。用来把候选版本限制在声明的分支上。"""
    return line.split("x")[0]


def judge(entry: dict, cache: dict[str, AffectedSet]) -> Verdict:
    group, artifact = entry["group"], entry["artifact"]
    line = entry.get("line", "")
    cves = entry.get("cves") or []
    base = dict(group=group, artifact=artifact, line=line, counts=int(entry.get("counts", 0)))

    if not cves:
        # 空列表不是"安全"，是"这份清单没在监视任何东西"——不要让它静默通过。
        return Verdict(**base, status="unknown", detail="清单里这个依赖的 cve 列表是空的，没有任何东西可比对")

    if base["counts"] != len(cves):
        # 条目自身不一致：声明的阻断条数与列出的 CVE 条数对不上。
        #
        # 这条检查是补的——清单的 `_comment` 里曾写着「counts …… 供脚本与 README 对账用；
        # 数字对不上多半意味着有新的 CVE 进来而没人更新这里，**脚本会说出来**」，
        # 而**代码里没有任何一处比对过它**：脚本不扫描本项目，无从知道 README 里
        # 当前写的阻断数是多少，那句承诺从写下那天起就不成立。
        #
        # 能确凿检查的是**条目内部**的一致性（声明的条数 vs 实际列出的 CVE），
        # 那就把它检查掉，而不是继续留一句做不到的声明。
        return Verdict(
            **base,
            status="unknown",
            detail=f"清单里 counts={base['counts']} 与列出的 {len(cves)} 条 CVE 对不上——"
            "要么漏列了 CVE，要么那条已经清掉却没删干净",
        )

    sets: list[AffectedSet] = []
    unknown: list[str] = []
    for cve in cves:
        try:
            sets.append(affected_versions(cve, cache))
        except Unavailable as error:
            unknown.append(f"{cve}（{error}）")

    if unknown:
        return Verdict(
            **base,
            status="unknown",
            unknown_cves=[u.split("（")[0] for u in unknown],
            detail="这些 CVE 的受影响版本取不到，无法判定：" + "；".join(unknown),
        )

    try:
        versions = central_versions(group, artifact)
    except Unavailable as error:
        return Verdict(**base, status="unknown", detail=str(error))

    stable = [v for v in versions if not PRERELEASE.search(v)]
    if not stable:
        return Verdict(
            **base, status="unknown", detail=f"Central 上有版本记录但没有任何正式版：{'、'.join(versions[:5])}"
        )

    def clean(candidate: str) -> bool:
        return not any(s.contains(candidate) for s in sets)

    prefix = line_prefix(line)
    in_line_versions = [v for v in stable if v.startswith(prefix)] if prefix else stable
    latest_in_line = max(in_line_versions, key=parse_version) if in_line_versions else None

    # ① 同一条线上已经有绕得开的版本 —— 这是**补丁级**的路，最该先看
    if latest_in_line and clean(latest_in_line):
        return Verdict(
            **base,
            status="upgrade-available",
            in_line=latest_in_line,
            blocked_cves=cves,
            detail=f"同线（{line}）最新正式版 {latest_in_line} 不在任何一条被监视 CVE 的受影响范围内",
        )

    # ② 同线没有，但往上走有 —— **报出来，但不替人判断能不能走**
    #
    # 取的是「**从本线最新版往上数，第一个绕得开的版本**」，不是「所有干净版本里最小的那个」。
    # 后者会把 `spring-security 2.0.0`、`mysql-connector-j 8.0.31` 这种**比你现在还旧**的
    # 版本报成"修复版"——它们确实不在受影响区间里（区间是从 6.5 / 9.7 起的），
    # 但退回它们是降级不是升级。**这类答案比没有答案更坏**：2026-09-21 第一版就是这么报的。
    if latest_in_line is None:
        return Verdict(
            **base,
            status="unknown",
            detail=f"Central 上没有任何 {line} 分支的正式版，无法确定该从哪个版本往上找",
        )

    floor = parse_version(latest_in_line)
    upward = [v for v in stable if clean(v) and parse_version(v) > floor]
    if upward:
        target = min(upward, key=parse_version)
        return Verdict(
            **base,
            status="cross-line-only",
            in_line=latest_in_line,
            cross_line=target,
            blocked_cves=cves,
            detail=f"同线（{line}）没有修复版——最新 {latest_in_line} 仍在受影响范围内。"
            f"从它往上数，第一个不在受影响范围的正式版是 {target}。"
            f"**但那是不是一次大版本迁移、代价多大，这个脚本不做判断**，"
            f"要人读了发行说明再定。",
        )

    # ③ 任何版本都在区间里 / 最新版就是当前版本
    ranges = "；".join(f"{s.cve}: {s.describe()}" for s in sets[:3])
    more = f"（另有 {len(sets) - 3} 条，见 --json）" if len(sets) > 3 else ""
    return Verdict(
        **base,
        status="none-available",
        in_line=latest_in_line,
        blocked_cves=cves,
        detail=f"Central 上最新正式版就是 {latest_in_line}，且它仍在受影响范围内。{ranges}{more}",
    )


# ─────────────────────────── 输出 ───────────────────────────

STATUS_LABEL = {
    "upgrade-available": "同线有可用修复版",
    "cross-line-only": "同线没有，只有跨线可用",
    "none-available": "没有可用修复版",
    "unknown": "无法判定",
}


def render(verdicts: list[Verdict]) -> str:
    lines = ["依赖修复版可用性检查（只读；数据来自 NVD 与 Maven Central）", ""]
    for verdict in verdicts:
        lines.append(f"{verdict.artifact}  ({verdict.group})  分支 {verdict.line}")
        lines.append(f"  监视 {len(verdict.blocked_cves) or verdict.counts} 条 CVE   →  {STATUS_LABEL[verdict.status]}")
        lines.append(f"  {verdict.detail}")
        if verdict.cross_line and verdict.status != "upgrade-available":
            lines.append(f"  往上第一个可用的正式版：{verdict.cross_line}")
        lines.append("")

    in_line = [v for v in verdicts if v.status == "upgrade-available"]
    cross_line = [v for v in verdicts if v.status == "cross-line-only"]
    unknown = [v for v in verdicts if v.status == "unknown"]
    lines.append("─" * 64)
    if in_line:
        lines.append("**同线就有修复版的**：" + "、".join(f"{v.artifact} → {v.in_line}" for v in in_line))
    if cross_line:
        lines.append(
            "只有跨线可用的："
            + "、".join(v.artifact for v in cross_line)
            + "——这些**不是补丁级升级**，得先读发行说明再决定要不要走。"
        )
    if in_line or cross_line:
        lines.append(
            "升级要走完整流程：只升到够用的最小版本，再跑单元 / 集成 / E2E / "
            "dependency-check / dependency:list；**只有扫描结果里那些包确实消失，"
            "才算清掉**。"
        )
    if unknown:
        lines.append(f"有 {len(unknown)} 个依赖**无法判定**（不是「没有」）：" + "、".join(v.artifact for v in unknown))
    if not in_line and not cross_line and not unknown:
        lines.append("四个依赖都没有可用修复版——与 README「依赖安全」一节记录的一致。")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="检查被阻断的依赖在上游有没有可用的修复版")
    parser.add_argument(
        "--watchlist", default=str(DEFAULT_WATCHLIST), help="监视清单（默认 scripts/dependency-watchlist.json）"
    )
    parser.add_argument("--json", dest="json_path", help="把结果写成 JSON")
    parser.add_argument(
        "--fail-on-available", action="store_true", help="存在可用修复版时以 3 退出（给定时任务用；仍然只读）"
    )
    args = parser.parse_args(argv)

    path = Path(args.watchlist)
    if not path.is_file():
        print(f"::error::监视清单不存在：{path}")
        return 1
    watchlist = json.loads(path.read_text(encoding="utf-8"))
    entries = watchlist.get("entries") or []
    if not entries:
        # 空清单会让下面所有结论都是"没有可用修复版"——又一个用空集证明合格。
        print("::error::监视清单里没有任何依赖，这不是「全部安全」，是「什么都没检查」。")
        return 1

    cache: dict[str, AffectedSet] = {}
    verdicts = []
    for entry in entries:
        verdicts.append(judge(entry, cache))
        # NVD 无 Key 时限流是 5 次/30 秒；留出余量，别把检查做成限流实验
        time.sleep(0.5 if os.environ.get("NVD_API_KEY") else 6.5)

    print(render(verdicts))

    if args.json_path:
        Path(args.json_path).write_text(
            json.dumps([v.as_dict() for v in verdicts], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    if any(v.status == "unknown" for v in verdicts):
        return 2
    if args.fail_on_available and any(v.status in ("upgrade-available", "cross-line-only") for v in verdicts):
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
