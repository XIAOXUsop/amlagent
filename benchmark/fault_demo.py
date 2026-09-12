"""
AML Agent 可靠性演示：一键演示"可重试失败 → 重试 → 死信 → 人工重试恢复"。

用法：
    python benchmark/fault_demo.py
"""

import time

from http_client import SessionClient

BASE = "http://localhost:8080"
TERMINAL = {"DONE", "HOLD", "FAILED"}


def wait_status(client, case_id, target, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, case = client.request("GET", f"/api/cases/{case_id}")
        if code == 200 and case and case.get("status") in target:
            return case
        time.sleep(0.5)
    return None


def main():
    client = SessionClient(BASE, timeout=120)
    client.authenticate("admin", "admin123")
    print("=" * 60)
    print("AML Agent 可靠性演示：故障注入 → 重试 → 死信 → 人工重试恢复")
    print("=" * 60)

    # 1. 开启故障注入（5 次失败 > max-retry=3，确保触发死信）
    print("\n[1] 开启故障注入（COLLECTING 阶段注入 5 次失败）...")
    code, status = client.request("POST", "/api/debug/fault?enabled=true&failCount=5")
    print(f"    注入器状态: {status}")

    # 2. 创建工单（autoProcess 自动执行）
    code, case = client.request("POST", "/api/cases", {"customerId": "C003", "alertRule": "故障注入演示"})
    cid = case["id"]
    print(f"[2] 创建工单 #{cid}，自动执行")

    # 3. 等待进入死信（FAILED + retryCount 达到上限）
    print("[3] 观察失败重试...")
    failed = wait_status(client, cid, {"FAILED"}, timeout=120)
    if not failed:
        print("    警告：工单未在超时内进入 FAILED")
        return
    print(f"    工单 FAILED：retryCount={failed['retryCount']}，failureCode={failed['failureCode']}")
    print(f"    失败原因: {failed['failureMessage']}")

    # 4. 查看死信队列
    code, dead = client.request("GET", "/api/queues/dead")
    print(f"[4] 死信队列消息数: {len(dead) if dead else 0}")

    # 5. 关闭故障注入
    code, status = client.request("POST", "/api/debug/fault?enabled=false")
    print(f"[5] 关闭故障注入: {status}")

    # 6. 人工重试
    code, retried = client.request("POST", f"/api/cases/{cid}/retry")
    print(f"[6] 人工重试工单 #{cid}，状态置回 {retried['status'] if retried else '?'}")

    # 7. 等待恢复完成
    print("[7] 等待恢复完成...")
    done = wait_status(client, cid, {"DONE", "HOLD"}, timeout=180)
    if done:
        status = done["status"]
        risk_level = done["riskLevel"]
        execution_version = done["executionVersion"]
        print(f"    恢复成功：status={status}，riskLevel={risk_level}，executionVersion={execution_version}")
    else:
        print("    恢复未完成")

    print("\n" + "=" * 60)
    print("演示完成：完整展示了 失败 → 指数退避重试 → 超限进死信 → 人工重试恢复")
    print("=" * 60)


if __name__ == "__main__":
    main()
