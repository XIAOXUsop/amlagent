# 第三轮修改成果验收报告

日期：2026-09-05；工作区：`D:\JCode`。
依据：[第二轮复审](remediation-review-v2-2026-09-05.md)、[v2 修改计划](../plans/remediation-and-iteration-plan-v2-2026-09-05.md)、本轮 W1/W2 实施记录。
业务延伸：[前沿调研](../research/aml-business-frontier-2026-09-05.md)、[业务优化详细计划](../plans/aml-business-optimization-plan-v3-2026-09-05.md)。

## 1. 验收结论

**上一轮两项问题的主要修复已落地，但整体验收仍未通过。** 覆盖版本冲突现已统一为 409，形成结论/重新确认共用编辑流程并保留覆盖草稿；原 V26 SQL 漏写 `aml_alert` 关联也已修正。

本轮仍发现两类 P2 问题：新增迁移验收代码存在多个确定性错误，无法完成声称的两阶段验证；假设判断的冲突刷新失败路径仍会丢失草稿。真实数据库事务、迁移和全栈 E2E 没有执行成功证据。

本次仅做验收、调研和计划编制，未实施业务源码修复或修改业务数据。临时页面探针已移出正式源码目录。

## 2. 本轮修复逐项验收

| 项目 | 结果 | 证据及限制 |
|---|---|---|
| 覆盖自身/引用假设/假设修改版本冲突 | 代码及默认测试通过 | 三个版本校验点使用专用异常；异常映射返回 409、稳定码及 conflict 对象；普通业务前置条件仍为 412 |
| 形成结论、重新确认的草稿恢复 | 已有组件交互场景通过 | 当前正式测试点击实际 SFC 按钮，覆盖连续冲突、覆盖草稿保留、刷新失败和 EXPLAINED 重新确认 |
| 假设确认/改判/重申的恢复 | 部分通过 | 正常循环可刷新版本；刷新失败后草稿丢失，见 A3-02 |
| V26 过期绑定查询漏 JOIN | 静态修复通过 | 第 26 行增加 `LEFT JOIN aml_alert a ON a.id = cov.alert_id`；数据库实跑待完成 |
| V25→V26 查询/迁移测试 | 不通过代码审查 | 新增 integration 测试有 A3-01；不能仅因本机缺数据库而把代码本身视为可验收 |
| 上轮容量预检/失败拆分、v1 HOLD 门禁 | 相关默认测试继续通过 | 尚不等同真实 Worker 并发与完整角色流程验收 |

## 3. 需要修正的问题

### A3-01 [P2] 迁移验收测试在 V25 阶段使用新列，且夹具与取值逻辑不匹配

主要位置：`backend/src/test/java/com/bank/aml/config/InvestigationContractMigrationTest.java:270`。

测试先迁移到 V25，再调用 `insertV25Fixtures()`。但 V25 的 `insertCoverage()` 委托 `insertCoverageWithRevision()`，后者始终在 INSERT 中写 `hypothesis_revision`。该列由 V26 才新增；传 NULL 也不能使不存在的列变得可用。因此即使提供正确 MySQL 环境，测试仍会在迁移前夹具阶段失败。

对同一测试继续核对，还存在需要一起处理的后续阻断：

| 位置/操作 | 问题 | 修正要求 |
|---|---|---|
| `insertCoverageWithRevision` | V25 阶段使用 V26 列；同时试图在迁移前预造“已绑定健康记录” | V25 插入语句不含新列；迁移后再明确创建/确认健康绑定，旧数据保持 NULL |
| `insertSubmittedReport` | `review_id` 写死为 1，夹具没有创建对应 `manual_review`；V23 存在复核外键 | 先为同一案件创建有效复核记录，使用实际生成的 ID |
| `queryLong` 与 `.isNull()` | 使用 `ResultSet.getLong(1)` 却不检查 `wasNull()`，数据库 NULL 会被当成 0 | 使用可空读取或检查 `wasNull()`，区分 NULL 与 0 |
| `queryStringSet` | 始终取第 1 列；过期绑定 SQL 的第 1 列是 case_id，而断言期望外部预警编号 | 按 `external_alert_id` 列名读取，或明确传入所需列 |
| `consistent` 健康对照 | 假定 V25 数据已有可证明版本绑定，与“V26 不回填”政策矛盾 | 迁移前应为 NULL；迁移后通过明确操作绑定，或另建 V26 健康夹具 |
| V26 查询执行范围 | 声称全部执行，但未执行第 6 条归档查询 | 每条脚本语句都必须执行；加入归档夹具与明确结果断言 |

**验证方式：** 对照实际 Java 控制流、JDBC 取值方式及 V23/V25/V26 schema 定义完成静态核对；未连接真实 MySQL，不声称实际跑出了数据库错误码。以上属于测试本身的可定位错误，不能只列为“等待环境”。

**验收标准：** 修复上述错误后，在独立 MySQL 上从 V25 存量夹具完整执行到 V26，全部影响查询成功且命中预期记录；保留原业务事实、NULL 绑定与外键完整性，不得通过关闭外键或提前添加列让测试通过。

### A3-02 [P2] 假设冲突刷新失败后，提示已保留但实际丢失判断依据

位置：`frontend/src/views/CaseDetailView.vue:740`。

`decideInvestigationHypothesis()` 将新输入保存在函数局部变量 `analysis`。当提交返回调查版本冲突、随后 `reloadInvestigation()` 失败时，页面提示“你输入的判断依据已保留”并 return。下一次点击重新调用函数，`analysis` 又变为 `''`，弹框预填数据库旧 `rationale`；没有类似覆盖编辑的页面级草稿保存。

**本轮复现：** 临时测试挂载实际页面，点击“重申判断”，输入新依据，模拟更新 409 + 刷新网络失败，再次点击。错误提示包含“已保留”，但第二次弹框内容是“当前判断依据”，不是用户的新输入。

探针位于 `.tmp/AcceptanceV3Probe.spec.ts`，执行时临时放在 `frontend/src/views`；套件共 11 项，其中 10 项复用现有组件测试，1 项新增复现，执行后已移出。探针通过表示缺陷成功复现，不表示此功能已修复。

**修正要求：** 假设草稿按案件和假设 ID 保存到页面级状态，记录目标结论与依据；刷新失败或普通网络失败后可重新打开恢复。成功或用户明确放弃时清理，并补充假设操作的正式交互测试。只改变提示文字不足以满足上一版计划的草稿恢复要求。

## 4. 实际验证结果

| 验证项 | 结果 |
|---|---|
| 后端默认 Maven 测试 | 382 总计；381 通过、0 失败、0 错误、1 跳过；BUILD SUCCESS |
| 前端 Vitest | 6 个文件、35 项通过 |
| 类型检查和生产构建 | `npm run build` 通过；chunk 大小提示不作为阻断 |
| 假设草稿临时组件探针 | 11 项通过，其中 1 项新增复现 |
| Docker 复查 | `docker_engine` 管道不存在，守护进程不可连接 |
| 数据库迁移、事务并发、Playwright 全栈 | 未执行；默认 Maven 排除 integration 标签 |
| 真实模型效果 | 未执行；与确定性验收分开 |

后端沿用已有 JDK 21/Maven 3.9.9 和离线缓存，测试 JVM 临时目录位于工作区，使用 `junit.jupiter.tempdir.cleanup.mode.default=NEVER` 规避沙箱清理问题；没有删减断言或业务用例。日志：`.tmp/acceptance-v3-backend.log`。

## 5. 本次验收对象定位

工作区仍含多轮未提交修改；以下 SHA-256 对关键文件提供明确定位，后续修改后不应继续沿用本报告作为新版本验收证明。

| 文件 | SHA-256 |
|---|---|
| `frontend/src/views/CaseDetailView.vue` | `C564E768A29FAF61E2BA4A3D392F0BA342DDC481C0C4F73C0A7AD89A6C1307D3` |
| `backend/src/test/java/com/bank/aml/config/InvestigationContractMigrationTest.java` | `46145B5E0BC4F257EAEA2AF233DB76F0E928136AB81EFE408E14D36406A4B6D3` |
| `docs/ops/investigation-impact-queries-v26.sql` | `146A19CE22EAA3B444429CCC5B52F34BBADF93E2CF860D71F52FB0734605EBA6` |

## 6. 接下来如何推进

先在独立的小批次中关闭 A3-01/A3-02，并兑现 v2 的 W3 数据库和角色闭环验收。业务优化以这个可验证基线为起点，避免继续堆叠功能而无法确认关键流程正确性。

业务调研发现的持续尽调、受益所有人多来源核验、网络研判和报告质量需求属于后续产品优化方向，不能混算为本轮新增代码缺陷；详细安排见配套计划。
