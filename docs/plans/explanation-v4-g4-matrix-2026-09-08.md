# G4 全链验收矩阵覆盖检查表（v4 批次 10）

日期：2026-09-08。基线：`3494fc0` + 本批 G4。
依据：[v4 计划 §10 RF-01~30](group-payment-lifecycle-iteration-plan-v4-2026-09-08.md)。

## 验证口径

- 默认：**491 通过 / 0 失败 / 1 跳过**
- 集成（隔离 MySQL，V1~V35 真实迁移）：**38 通过 / 0 失败**（31 + RefundLedgerIntegrationTest 7）
- 前端：66 通过 + vue-tsc/Vite 构建通过

## RF-01~30 覆盖状态

| ID | 状态 | 验证层与位置 |
|---|---|---|
| RF-01 核验失效/恢复 | ✅ 服务层 | EvidenceAdmissibilityTest（UNRESOLVED 失效+补核验恢复）；A6-05 失效链路 |
| RF-02 整体不适用拒绝 | ✅ 服务层 | ExplanationV3ReacceptanceTest#allQuestionsNotApplicableIsRejected + 政策核定矩阵 |
| RF-03 每题独立 | ✅ 服务层 | EvidenceAdmissibilityTest#q2CannotBorrowQ1Verification |
| RF-04 错绑义务 | ✅ 服务层 | ExplanationV3ReacceptanceTest#mismatchedObligationTaskDoesNotCoverDeliveryObligation |
| RF-05 删命中交易 | ✅ 服务层 | ExplanationV3ReacceptanceTest#removingHitTransactionFromScopeIsRejectedByServerScope（+未冻结/空集断言） |
| RF-06 原路退款全链 | ✅ 全链 | RefundLedgerIntegrationTest#refundLedgerBalancesInRealDatabase（真实库行级）+ RefundAuthorityTest（可提交语义） |
| RF-07 原路不免责 | ✅ 服务层 | RefundAuthorityTest#originalPathWithoutReasonIsBlocked |
| RF-08 买方权限核实 | ✅ 服务层 | RefundAuthorityTest#buyerWithVerifiedAuthorityIsAdmissible |
| RF-09 权限未知阻断 | ✅ 服务层 | RefundAuthorityTest#unresolvedAuthorityBlocksExplanation |
| RF-10 权限矛盾 | ✅ 服务层 | RefundAuthorityTest#contradictedAuthorityBlocksAndKeepsContradiction |
| RF-11 两次部分退款 | ✅ 真实库 | RefundLedgerIntegrationTest#twoPartialRefundsAccumulate |
| RF-12 待退不算已退 | ✅ 服务层 | RefundLedgerServiceTest#requestedRefundDoesNotReduceBalance |
| RF-13 超额拒绝 | ✅ 真实库 | RefundLedgerIntegrationTest#overAllocationRejectedWithNoPartialRows（含 RF-24 回滚语义） |
| RF-14 一退多付 | ✅ 服务层+真实库 | RefundLedgerServiceTest#oneRefundAcrossTwoOriginalPayments + 集成覆盖 |
| RF-15 差额调整项 | ⚠️ 部分建立 | 金额账支持独立调整语义（分配合计≤事件金额）；差额类型表待业务签认（§12-3） |
| RF-16 跨币种不适用 | ✅ 服务层 | RefundLedgerServiceTest#crossCurrencyIsNotSupported |
| RF-17 冲正 | ✅ 真实库 | RefundLedgerIntegrationTest#reversalRestoresBalanceKeepsRows |
| RF-18 付款后撤销 | ✅ 服务层 | PaymentAuthorityFactTest#revocationAfterPaymentDoesNotInvalidateHistoricPayment |
| RF-19 追认不替代授权 | ✅ 服务层 | PaymentAuthorityFactTest#ratificationAfterPaymentKeepsUnknown |
| RF-20 同源家族 | ✅ 服务层 | ClaimSourceFamilyTest（3 项归一化断言） |
| RF-21 幂等/更正冲突 | ✅ 真实库 | RefundLedgerIntegrationTest#idempotencyEnforcedByDatabaseUniqueKey（唯一键兜底） |
| RF-22 并发占用 | ✅ 真实库 | RefundLedgerIntegrationTest#concurrentRegistrationIsSerializedByCaseLock（行锁串行+超额显式） |
| RF-23 预检后变化 | ✅ 服务层 | ExplanationV3ReacceptanceTest#stalenessBetweenPrecheckAndSubmitYieldsTokenConflict |
| RF-24 失败回滚 | ✅ 真实库 | overAllocationRejectedWithNoPartialRows（事务边界=无半成品行）+ ReliabilityWorkflowTest 既有覆盖 |
| RF-25 自审 | ✅ 服务层 | ExplanationWorkspaceServiceTest#reviewerAmongContributorsIsBlockedFromFinalDecision（API 层）；浏览器 E2E 待补 |
| RF-26 合法接续 | ✅ 服务层 | ExplanationV3ReacceptanceTest#simulatedCoverageAcceptsQualifiedPlanWithoutMutating（拟态不写库）+ transferObligations 逐项对应 |
| RF-27 到期/停用 | ✅ 服务层 | ExplanationV3ReacceptanceTest#disabledAssigneeDoesNotCoverObligation + #overduePlanDoesNotCoverObligation |
| RF-28 档案回放 | ✅ 服务层 | CaseDossierServiceTest（冻结 payload 回放，草稿改写不影响档案） |
| RF-29 RAG 门禁 | ✅ 服务层 | RagEvalReportGatingTest（环境失败语义）；全链路 A/B 集成断言待 RAG 环境 |
| RF-30 存量/回退 | ✅ 真实库 | RefundLedgerIntegrationTest#legacyCaseLedgerIsEmptyNotFaked（存量不伪造）+ 回退开关语义（功能级未专项演练） |

## 本批新增（G4）

`RefundLedgerIntegrationTest`（7 项，隔离 schema `aml_refund_ledger_test`）：
RF-06 金额账行级、RF-21 数据库唯一键兜底、RF-13/24 失败回滚无半成品、
RF-11 累计、RF-17 冲正行级、RF-30 存量不伪造、RF-22 并发行锁串行+超额显式。

修复：退款幂等 digest 含 effectiveAt（业务时间）——重放必须携带相同业务时间；
同键重放携 `LocalDateTime.now()` 会误判内容冲突（测试暴露后修正 helper 语义注释）。

## 未尽事项（诚实边界）

1. **浏览器 E2E**（RF-25/28 的 UI 层）：RefundPanel/预检的组件级交互测试未写；API 与服务层语义已锁定。
2. **RF-15 差额类型表**与 **§12 六项评审材料**：需业务负责人签认（AI 只能起草）。
3. **RF-29 全链路 A/B**：需真实模型制品与索引环境（服务层门禁语义已锁定）。
4. **回退演练**（RF-30 尾项）：功能开关语义已实现（新政策拒绝写入、读取保留），演练记录待试点环境。
5. **G1-1 AlertScopeService** 的上游接入（监测系统真实推送 trigger_transaction_ids）待外部来源评估。
