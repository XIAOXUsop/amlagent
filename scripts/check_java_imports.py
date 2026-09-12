"""Check or deterministically sort Java import blocks."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "backend" / "src"


def sorted_source(source: str) -> str:
    lines = source.splitlines()
    import_indexes = [index for index, line in enumerate(lines) if line.startswith("import ")]
    if not import_indexes:
        return source

    start = import_indexes[0]
    end = import_indexes[-1]
    while end + 1 < len(lines) and not lines[end + 1].strip():
        end += 1

    imports = [lines[index] for index in import_indexes]
    ordinary = sorted(line for line in imports if not line.startswith("import static "))
    static = sorted(line for line in imports if line.startswith("import static "))
    block = ordinary + ([""] + static if static else []) + [""]
    normalized = lines[:start] + block + lines[end + 1 :]
    return "\n".join(normalized) + ("\n" if source.endswith("\n") else "")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true", help="rewrite files whose import order is not deterministic")
    args = parser.parse_args()

    changed: list[Path] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        normalized = sorted_source(source)
        if normalized == source:
            continue
        changed.append(path)
        if args.fix:
            with path.open("w", encoding="utf-8", newline="\n") as output:
                output.write(normalized)

    if changed and not args.fix:
        print("Java import 顺序检查失败：")
        for path in changed:
            print(f"- {path.relative_to(ROOT).as_posix()}")
        return 1
    action = "已修复" if args.fix else "检查通过"
    print(f"Java import {action}（{len(changed)} 个变更文件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
