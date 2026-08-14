# 可靠工作流：Outbox、租约与状态机

## 消息闭环

所有"改数据库状态 + 发任务消息"的操作统一走 Transactional Outbox：

```
条件更新工单状态 + 插入 Outbox 事件（同一事务）
        ↓ 提交
OutboxPublisher 异步扫描 PENDING 事件 → XADD Redis Streams
```

幂等键 `caseId:eventType:executionVersion`（唯一索引）防止重复入队。

事件类型：`CASE_CREATED` / `CASE_MANUAL_TRIGGERED` / `CASE_RETRY_DUE` / `CASE_RECLAIMED` / `CASE_DEAD_REPLAYED` / `CASE_DEAD_LETTER`。

`OutboxPublisher` 按事件类型路由：`CASE_DEAD_LETTER` → Dead Stream，其余 → 主 Stream。

## 执行租约（fencing token）

每个工单执行携带 `workerId + executionVersion`，所有心跳/完成/失败/接管更新均以二者为条件：

- `tryLock`：仅 `PENDING` 可抢占，`executionVersion` 自增（FAILED 只能通过显式管理命令恢复）。
- `updateHeartbeat`：`WHERE id + lockedBy = worker + executionVersion = version`，返回 0 表示租约丢失。
- `finishCase` / `failCase` / `markRetryWait`：绑定 `worker + executionVersion`，旧 Worker 陈旧写入返回 0 被丢弃。
- `reclaimStuckCase`：`WHERE id + status=RUNNING + executionVersion + lockedBy + heartbeatAt < threshold`，心跳刷新后不误接管。

`ExecutionLease` 在 Worker 抢占后创建，心跳线程发现返回 0 时 `markLost()`；主流程各阶段检查 `isValid()`，
租约丢失后不再写日志 / SSE / 报告。

## 失败状态机

```
PENDING → RUNNING → DONE / HOLD / RETRY_WAIT / FAILED
RETRY_WAIT → PENDING（到期重投）
FAILED → PENDING（仅人工重试/死信重放命令）
HOLD → DONE / FAILED（仅人工复核）
```

死信走 Outbox：`RUNNING → FAILED` 与 `CASE_DEAD_LETTER` 事件同事务，不直接写 Redis。

## 验证

`ReliabilityWorkflowTest`（集成）：Outbox 幂等、退避重投、超时接管、接管耗尽、原子租约、死信兜底。
`WorkflowCommandServiceTest` / `ExecutionLeaseTest`（单元）：接管条件、死信 Outbox、租约失效。
