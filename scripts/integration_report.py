"""Preflight checks for the integration-test dependencies, and a failure summary.

集成测试要同时连上 MySQL、PGVector 和 Redis。它们没起来时，测试的报错往往是一句
连接超时或 Flyway 迁移失败——读的人得自己推断是哪个依赖的问题。这个脚本把两件事分开说：

* ``--preflight``：在跑测试**之前**逐个探测三个依赖，明确说出谁不可用；
* ``--failures``：跑完之后读 Surefire XML，列出失败的集成测试类，让 Actions 日志里
  的 ``::error::`` 直接点到类名，而不是埋在一屏 Maven 输出里。

为什么不在 Actions 里分成三个 job 各带一个 service：集成测试基本都是 ``@SpringBootTest``，
启动的就是完整的应用上下文，三个依赖一个都不能少。按依赖切 job 只会让每个 job 都起全套
service，切了等于没切。真正需要区分的是"依赖没起来"和"测试断言失败"，这里就是这么分的。

用法::

    python scripts/integration_report.py --preflight
    python scripts/integration_report.py --failures backend/target/surefire-reports-integration
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]

CONNECT_TIMEOUT_SECONDS = 3


@dataclass(frozen=True)
class Dependency:
    """一个集成测试依赖：名字、环境变量前缀、默认端口、以及连不上时的排查提示。"""

    name: str
    env_host: str
    env_port: str
    default_host: str
    default_port: int
    hint: str

    def address(self) -> tuple[str, int]:
        host = os.environ.get(self.env_host, self.default_host)
        port = int(os.environ.get(self.env_port, self.default_port))
        return host, port


DEPENDENCIES = (
    Dependency(
        "MySQL",
        "MYSQL_HOST",
        "MYSQL_PORT",
        "127.0.0.1",
        3307,
        "启动：docker compose up -d mysql（容器端口 3307，避开本机 3306）",
    ),
    Dependency(
        "PGVector",
        "PGVECTOR_HOST",
        "PGVECTOR_PORT",
        "127.0.0.1",
        5433,
        "启动：docker compose up -d pgvector（容器端口 5433）",
    ),
    Dependency(
        "Redis",
        "REDIS_HOST",
        "REDIS_PORT",
        "127.0.0.1",
        6379,
        "启动：docker compose up -d redis（容器端口 6379）",
    ),
)


def probe(dependency: Dependency) -> str | None:
    """能连上返回 None，连不上返回一句人话。"""
    host, port = dependency.address()
    try:
        with socket.create_connection((host, port), timeout=CONNECT_TIMEOUT_SECONDS):
            return None
    except OSError as error:
        return f"{host}:{port} 连不上（{error.__class__.__name__}: {error}）"


def preflight() -> int:
    unhealthy: list[str] = []
    print("集成测试依赖就绪检查")
    for dependency in DEPENDENCIES:
        problem = probe(dependency)
        if problem is None:
            host, port = dependency.address()
            print(f"  [OK]   {dependency.name:<9} {host}:{port}")
        else:
            print(f"  [FAIL] {dependency.name:<9} {problem}")
            print(f"         {dependency.hint}")
            unhealthy.append(dependency.name)

    if unhealthy:
        # 单独一行 ::error:: —— 让 Actions 的失败摘要里直接写着是哪个依赖，
        # 而不是一句笼统的 "integration failed"
        print(f"::error::集成测试依赖未就绪：{', '.join(unhealthy)}")
        return 1
    print("三个依赖都可用，可以开始跑集成测试")
    return 0


def read_failures(report_dir: Path) -> list[str]:
    """列出失败的测试：``类名#方法名``。没有产物时返回空列表（未执行 ≠ 通过）。"""
    reports = sorted(report_dir.glob("TEST-*.xml"))
    failures: list[str] = []
    for report in reports:
        root = ElementTree.parse(report).getroot()  # noqa: S314 - 本地构建产物，非不可信输入
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(f"{case.get('classname', '?')}#{case.get('name', '?')}")
    return failures


def failures(report_dir: Path) -> int:
    if not report_dir.is_dir():
        print(f"::error::找不到测试报告目录 {report_dir}——测试很可能根本没跑起来")
        return 1

    failed = read_failures(report_dir)
    if not failed:
        print("没有任何失败的测试")
        return 0

    print(f"共 {len(failed)} 项失败：")
    for name in failed:
        # 每条都带 ::error:: 前缀，Actions 会在 PR 与摘要里逐条列出
        print(f"::error::集成测试失败 {name}")
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="集成测试依赖检查与失败汇总")
    parser.add_argument("--preflight", action="store_true", help="探测 MySQL / PGVector / Redis 是否可用")
    parser.add_argument("--failures", action="store_true", help="从 Surefire XML 汇总失败的测试")
    parser.add_argument(
        "report_dir",
        nargs="?",
        default=str(ROOT / "backend" / "target" / "surefire-reports-integration"),
        help="Surefire 报告目录（配合 --failures）",
    )
    args = parser.parse_args(argv)

    if args.preflight:
        return preflight()
    if args.failures:
        return failures(Path(args.report_dir))

    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
