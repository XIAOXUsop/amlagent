# -*- coding: utf-8 -*-
"""
AML Agent 压测脚本：并发创建工单，测量系统吞吐与端到端延迟。

用法：
    python benchmark/load_test.py --count 200 --concurrency 20

说明：
    - 压测的是系统吞吐/队列/DB 能力，建议后端运行在 Mock 模式（不带 dev profile），
      以排除外部模型延迟的干扰；如带真实模型，延迟会包含模型推理时间，如实记录即可。
    - 端到端延迟包含 Outbox 发布器轮询间隔（默认 5s，见 aml.queue.outbox-poll-seconds）。
"""
import argparse
import json
import statistics
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "http://localhost:8080"
TERMINAL = {"DONE", "HOLD", "FAILED"}


def request(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, None


def login(username, password):
    code, resp = request("POST", "/api/auth/login", {"username": username, "password": password})
    if code != 200 or not resp:
        raise RuntimeError(f"登录失败: {code}")
    return resp["token"]


def create_case(token, customer_id):
    code, case = request("POST", "/api/cases", {"customerId": customer_id, "alertRule": "压测"}, token=token)
    if code != 200 or not case:
        return None, time.time()
    return case["id"], time.time()


def wait_terminal(token, case_id, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, case = request("GET", f"/api/cases/{case_id}", token=token)
        if code == 200 and case and case.get("status") in TERMINAL:
            return time.time()
        time.sleep(0.2)
    return None  # 超时


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    idx = min(len(sorted_values) - 1, int(len(sorted_values) * p))
    return sorted_values[idx]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=200, help="工单数量")
    parser.add_argument("--concurrency", type=int, default=20, help="并发线程数")
    parser.add_argument("--customer", default="C003", help="压测客户（默认 C003 正常客户）")
    parser.add_argument("--timeout", type=int, default=120, help="单工单等待终态超时（秒）")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    args = parser.parse_args()

    token = login(args.username, args.password)
    print(f"已登录，开始压测 {args.count} 个工单（并发 {args.concurrency}）...")

    # 并发创建
    create_start = time.time()
    created = []
    failures = 0
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(create_case, token, args.customer) for _ in range(args.count)]
        for f in as_completed(futures):
            cid, done_at = f.result()
            if cid is None:
                failures += 1
            else:
                created.append((cid, done_at))
    create_elapsed = time.time() - create_start
    create_qps = len(created) / create_elapsed if create_elapsed > 0 else 0

    # 轮询终态
    e2e_ms = []
    timed_out = 0
    for cid, created_at in created:
        terminal_at = wait_terminal(token, cid, args.timeout)
        if terminal_at is None:
            timed_out += 1
        else:
            e2e_ms.append((terminal_at - created_at) * 1000)

    e2e_ms.sort()
    success = len(e2e_ms)
    report = {
        "count": args.count,
        "concurrency": args.concurrency,
        "customer": args.customer,
        "created": len(created),
        "createFailures": failures,
        "createQps": round(create_qps, 1),
        "timedOut": timed_out,
        "e2eSuccess": success,
        "e2eP50Ms": round(percentile(e2e_ms, 0.50)),
        "e2eP95Ms": round(percentile(e2e_ms, 0.95)),
        "e2eP99Ms": round(percentile(e2e_ms, 0.99)),
        "e2eMeanMs": round(statistics.mean(e2e_ms)) if e2e_ms else 0,
    }

    print("\n===== 压测结果 =====")
    print(f"创建: {report['created']} 成功 / {report['createFailures']} 失败，吞吐 {report['createQps']} 工单/秒")
    print(f"端到端: {report['e2eSuccess']} 完成 / {report['timedOut']} 超时")
    print(f"  平均 {report['e2eMeanMs']}ms，P50 {report['e2eP50Ms']}ms，P95 {report['e2eP95Ms']}ms，P99 {report['e2eP99Ms']}ms")

    with open("benchmark/load-test-report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n报告已写入 benchmark/load-test-report.json")
    print("提示：压测产生约", len(created), "条工单数据，可在 MySQL 中清理：")
    print("  DELETE FROM aml_case_log; DELETE FROM case_execution; DELETE FROM outbox_event; DELETE FROM aml_case;")


if __name__ == "__main__":
    main()
