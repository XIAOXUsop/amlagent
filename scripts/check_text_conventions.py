"""Verify repository-wide text encoding and line-ending conventions."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {
    ".css",
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    ".html",
    ".java",
    ".js",
    ".json",
    ".md",
    ".properties",
    ".py",
    ".sql",
    ".toml",
    ".ts",
    ".tsx",
    ".vue",
    ".xml",
    ".yaml",
    ".yml",
}
TEXT_NAMES = {"mvnw", "mvnw.cmd"}
# 已在共享环境执行的 Flyway 文件必须保持字节不变；这两个历史文件原始版本没有末尾换行。
# §9.2 的 checksum 不变约束优先于 §4.1 的通用末尾换行规则。
IMMUTABLE_NO_FINAL_NEWLINE = {
    "backend/src/main/resources/db/migration/V17__audit_log.sql",
    "backend/src/main/resources/db/migration/V18__user_token_version.sql",
}
IGNORED_PARTS = {
    ".git",
    ".idea",
    ".tmp",
    "coverage",
    "dist",
    "node_modules",
    "playwright-report",
    "target",
    "test-results",
}


def candidate_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.rglob("*")
        if path.is_file()
        and not any(part in IGNORED_PARTS for part in path.parts)
        and (path.suffix.lower() in TEXT_SUFFIXES or path.name in TEXT_NAMES)
    )


def violations(path: Path) -> list[str]:
    relative = path.relative_to(ROOT).as_posix()
    raw = path.read_bytes()
    failures: list[str] = []
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        return [f"{relative}: 非 UTF-8（{error}）"]
    if text.startswith("\ufeff"):
        failures.append(f"{relative}: 禁止 UTF-8 BOM")
    if b"\r" in raw:
        failures.append(f"{relative}: 必须使用 LF，检测到 CR/CRLF")
    if text and not text.endswith("\n") and relative not in IMMUTABLE_NO_FINAL_NEWLINE:
        failures.append(f"{relative}: 文件末尾缺少换行")
    if text.endswith("\n\n"):
        failures.append(f"{relative}: 文件末尾只能保留一个换行")
    for line_number, line in enumerate(text.splitlines(), start=1):
        if line.rstrip(" \t") != line:
            failures.append(f"{relative}:{line_number}: 行尾存在空白字符")
    return failures


def main() -> int:
    failures = [failure for path in candidate_files() for failure in violations(path)]
    if failures:
        print("文本规范检查失败：")
        print("\n".join(f"- {failure}" for failure in failures))
        return 1
    print(f"文本规范检查通过（{len(candidate_files())} 个文件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
