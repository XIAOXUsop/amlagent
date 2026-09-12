# 退款金额账约束的 expand-contract 上线说明

## 1. 适用范围

本文约束 `V35__refund_event_allocation.sql` 到
`V36__refund_ledger_integrity_constraints.sql` 的生产升级。V36 只执行 contract 阶段，不能与
V35 的表结构扩展、兼容应用部署合并为一次未经观察的上线。

## 2. 分阶段流程

1. **Expand**：将 Flyway 目标固定在 V35，创建 `refund_event` 与 `refund_allocation`；部署能同时处理
   “无退款历史”和 V35 金额账的应用版本。该版本已在服务层拒绝非正金额、非 CNY、非法状态、跨案件分配，
   并以案件行锁保护登记和冲正。
2. **回填与验证**：不自动改写金融事实。先执行下方只读查询，结果必须全部为 `0`；非零时由业务、数据和审计
   共同确认修复单，保留修复前快照、审批记录和可回放 SQL。
3. **观察期**：至少覆盖一个完整发布观察窗口；监控退款登记/冲正失败率、数据库锁等待、主从延迟和审计对账。
   观察期内继续将 Flyway 目标保持在 V35。
4. **Contract**：在独立变更窗口解除 Flyway 目标并执行 V36。V36 会再次执行相同语义的存量扫描；发现异常会在
   任何约束 DDL 前失败。扫描通过后，增加金额/币种/状态 CHECK、事件身份唯一键、分配复合外键和冲正自引用复合外键。
5. **上线后验证**：核对 Flyway history、约束存在性、退款事件/分配行数与金额汇总，并观察锁等待和复制延迟。

## 3. 上线前只读检查

```sql
SELECT COUNT(*) AS invalid_event_count
FROM refund_event
WHERE amount <= 0
   OR currency <> 'CNY'
   OR event_status NOT IN ('REQUESTED', 'POSTED', 'REVERSED');

SELECT COUNT(*) AS invalid_allocation_count
FROM refund_allocation a
LEFT JOIN refund_event e
  ON e.id = a.refund_event_id
 AND e.case_id = a.case_id
 AND e.currency = a.currency
WHERE a.allocated_amount <= 0
   OR a.currency <> 'CNY'
   OR e.id IS NULL;

SELECT COUNT(*) AS invalid_reversal_count
FROM refund_event e
LEFT JOIN refund_event reversed
  ON reversed.id = e.reversed_event_id
 AND reversed.case_id = e.case_id
 AND reversed.currency = e.currency
WHERE e.reversed_event_id IS NOT NULL
  AND reversed.id IS NULL;
```

同时记录下列基线，V36 后必须一致：

```sql
SELECT COUNT(*) AS event_rows, COALESCE(SUM(amount), 0) AS event_amount FROM refund_event;
SELECT COUNT(*) AS allocation_rows, COALESCE(SUM(allocated_amount), 0) AS allocation_amount
FROM refund_allocation;
```

## 4. 锁表、兼容与回滚

- 添加 CHECK、唯一键和外键会扫描表并可能获取 metadata lock。上线前应按生产数据量在同版本 MySQL 副本演练，
  记录耗时；变更窗口内设置锁等待告警，存在长事务或复制延迟时停止执行。
- V36 不删除列、不改字段类型、不回填业务值；V35 兼容应用产生的数据满足新增约束，因此 contract 阶段仍支持
  上一应用版本短时并行。不得回滚到不满足这些不变量的更早应用版本。
- Flyway 版本迁移不做自动逆向执行。若 DDL 后必须回退，应在独立、已审批的更高版本迁移中依次删除新增复合外键、
  CHECK 和身份唯一键，并恢复 `fk_refund_alloc_event(refund_event_id)`；执行前先保留结构快照和金额账对账结果。
- V36 的存量扫描失败不触碰持久数据；根据查询结果修复并复核后重跑。禁止为了让迁移通过而自动删除、改币种或修改金额。

## 5. 验证证据

- `FlywayMigrationTest`：真实 MySQL 上验证 V35 合法存量升级、行数保留、约束生效，以及非法存量在 DDL 前阻断。
- `RefundLedgerIntegrationTest`：验证应用服务行为和数据库对跨案件、跨币种、非正金额、无效/跨案件冲正引用的拒绝。
- CI 的 `integration-test` 作业提供 MySQL 8.0，并执行所有 `integration` 标签测试。
