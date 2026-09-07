# 解释核验 v3 首批实施记录（S0–S5）

日期：2026-09-08。
依据：[第三方代付解释核验计划 v3](third-party-goods-payment-verification-plan-v3-2026-09-08.md)、
[解释核验 v2 首批验收报告](../reviews/explanation-v2-acceptance-2026-09-08.md)（A5-01～A5-09）。

## 交付总览

| 阶段 | 交付 | 状态 |
|---|---|---|
| S0 止住已确认错误 | A5-02/03/04/08/09 + 来源服务端抓取 | 完成（单元层） |
| S1 真实依据与依赖 | A5-01/05：服务器交易集对账、VerificationBasis 写入、evidence_use 反向引用、同源幂等/追加版本 | 完成（单元层） |
| S2 时间与接续闭环 | A5-06/07：Clock 即时重评、逐义务接续、token 顺序前置 | 完成（单元层） |
| S3 代付业务核心 | GOODS_GROUP_PAYMENT_V1 + C1~C4 事实约束 + 授权额度缺口（TP-06/09/10/11/12） | 完成（单元层） |
| S4 定向核验与界面 | next-actions 建议 API、重复补件提醒、降级提案 UI、前端草稿隔离 | 完成（组件层） |
| S5 档案与验收 | Dossier ExplanationSection（schema 1.7）、TP-28 组件测试、全量回归 | 完成（组件层） |

## 修复明细

### S0（A5-02/03/04/08/09 + 来源）
- **A5-02**：`ExplanationWorkspaceService.validateDraftContent` 增加 NOT_SATISFIED → EXPLAINED 拒绝；不自动变 SUSPICIOUS。
- **A5-03**：`evaluateReadiness` 案件级待分派关键事实（unitId=null 的 OPEN DECISION_CRITICAL）计入 general blocker。
- **A5-04**：降级改为提案/确认两步。新实体 `ExplanationIssueReview` + V28 迁移；`proposeDowngrade`（分析员）→ `confirmDowngrade`（认证 REVIEWER/ADMIN 独立请求，服务端写身份、校验账户启用与角色、禁提案人自认）；`rejectDowngrade`。处置接口移除 downgradeTo/confirmedBy 参数。
- **A5-08**：EDD `completeTask` 改为条件更新（状态+版本绑定，竞争完成仅一方成功）；禁止 respondedBy 自审；`resolvedBy` 持久化（V29 迁移）。
- **A5-09**：前端草稿按 unitId 隔离（`drafts` map）；UnitView 返回 `draftJson` 供服务器恢复；冲突保留本地并展示服务器版本。
- **来源**：新 `EvidenceSourcePort` + `DemoEvidenceSourceAdapter`（受控夹具、服务端 SHA-256、NOT_FOUND/UNAVAILABLE 可返回）；`captureEvidence` 移除调用方 contentSha256 输入；未取得内容 → 记录 SOURCE_UNAVAILABLE 关键问题，不再伪装 RESOLVED/MATCH。

### S1（A5-01/05）
- `TransactionRecord` 扩展 `sourceRecordId`（Relational=数据库主键，Mock=稳定 ID）；`serverTransactionAmounts` 冻结来源集合。
- 提交范围对账：声明的命中交易必须存在于服务器交易集且金额一致（TP-04 自编交易拒绝）；来源数据缺失时阻断。
- `submitUnit` 同事务：`persistEvidenceUses`（六问题材料/问题引用 → explanation_evidence_use）+ `persistVerificationBasis`（冻结 scopeJson/scopeDigest，绑定 submission.basisId）。
- `captureEvidence`：同源同内容幂等；不同内容追加 v2+（旧版本可回放）；`markArtifactSuperseded` 反查失效生效（A5-05 probe 场景转为断言 STALE）。

### S2（A5-06/07）
- `reevaluatePrepayDue`：最终复核事务内按注入 Clock 重评预付交期；过期且无依据 → 恢复 DELIVERY_OVERDUE 关键问题并 bump epoch（probe-7 场景转为断言阻断）。
- `transferObligations` 逐项对应：originRequestId 必须是本案件 OPEN DECISION_SUPPORT；同任务不得被多个 plan 引用；只取消被引用的原任务，未覆盖任务保持 OPEN（TP-21）；`completionStandard` 完整保存（V29）。
- ReviewService 顺序：`validateReviewBasisToken`（变更前）→ transferObligations → `validateReadyForReview(..., tokenAlreadyValidated=true)`（不拿变更前 token 与变更后事实比较，TP-22）。

### S3（代付配方）
- `ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1`：已交付 + 付款人≠买方 + 集团关系声明（CONFIRMED/UNVERIFIED/DENIED；DENIED → POLICY_NOT_APPLICABLE 不切宽松配方）。
- 新实体 `ExplanationClaim` / `ClaimEvidenceLink` + V30 迁移（Claim 写入链路为后续迭代项；本轮先以草稿 claims 节 + 服务端校验落地业务约束）。
- `validateGroupPaymentClaims`：EXPLAINED 要求 C1~C4 全部 SUPPORTED（TP-06/09/12）；授权额度 < 覆盖交易合计 → 拒绝整笔解释（TP-10）。
- `registerGroupPaymentGaps`：C3/C4 非 SUPPORTED、授权缺口在任何 outcome 下登记 DECISION_CRITICAL 问题（不自动变可疑，TP-06 SUSPICIOUS 允许）。

### S4
- `nextActions(caseId, unitId)`：优先级排序（完整性 → 矛盾 → 关键未知 → 到期义务 → 待分派）；`repeatedEvidenceRequest` 重复补件提醒。
- 前端：核验建议面板、提案降级按钮、代付配方问题口径。

### S5
- Dossier `ExplanationSection`（schema 1.7）：单元、Claim、问题、核验依据摘要。
- TP-28 组件测试（A→B 无串稿、服务器草稿恢复、保存失败不丢本地草稿）。

## 验证结果（本机 2026-09-08）

- 后端 `./mvnw test`：**428 通过 / 0 失败 / 1 跳过**（跳过项为默认不调真实模型的 AgentEvalLiveTest）。
- 前端 `npm test`：**66 通过**（含新增 TP-28 3 项）；`npm run build`（vue-tsc + Vite）通过。
- 新增迁移：V28（explanation_issue_review）、V29（edd completion_standard/resolved_by）、V30（explanation_claim/claim_evidence_link）。**真实 MySQL 迁移与并发/E2E 未实跑**（沿用 v2 验收报告的环境限制结论，见下节）。

## 已知边界与未交付项

1. **数据库与并发层未实跑**：本批全部为单元/组件层验证；TP-20/TP-23/TP-24/TP-27（并发竞争、失败注入回滚、真实 epoch 幂等重放）与真实 MySQL 迁移验收需 Docker 环境集成测试（沿用前置验收报告的未完成结论）。
2. **Claim 实体写入链路**：V30 已建表、服务已读 draft 中的 claims 节；`ExplanationClaim` 的实际读写 API（PUT /claims、verification-actions、ClaimEvidenceLink 写入）留待下一批（v3 计划 §9 的完整对象链）。
3. **复核预检接口**（POST /review-prechecks 只读模拟）与前端复核页接续计划录入未实施。
4. **解释范围冻结（AlertScopePort）**：当前范围对账按客户全量交易集；逐预警 `requiredAlertIds`/`triggerTransactionIds` 冻结留待与 V25 alert 范围映射打通。
5. **同源家族（sourceFamily）**：`ClaimEvidenceLink.sourceFamily` 字段已就绪，来源家族独立性计算未实施（TP-08 当前由"同源同内容幂等"部分覆盖）。
6. **v0/v1 回归**：既有调查契约 v1 测试保持通过（未修改旧路径语义）；未知契约版本仍拒绝写入（未放宽为 >=2 放行）。

## 测试映射

| 用例 | 位置 |
|---|---|
| A5-02 NOT_SATISFIED 阻断 | `notSatisfiedQuestionBlocksExplainedSubmission` |
| A5-03 待分派阻断 | `caseLevelUnassignedCriticalFactBlocksFinalExclusion` |
| A5-04 伪造确认人无效 / 提案人自认拒绝 | `arbitraryConfirmerCannotDowngradeViaDisposition` / `downgradeProposerCannotSelfConfirm` |
| A5-05 反向引用失效 | `supersedingUsedArtifactStalesSubmissionViaReverseIndex` |
| A5-06 Clock 重评 | `prepayExpiryBlocksFinalExclusionEvenWithoutTasks` / `prepayNotYetDuePassesFinalExclusionWithClock` |
| A5-07 token 顺序 / 逐项接续 | `reviewBasisTokenValidatedBeforeAnyMutation` / `unrelatedPlanIsRejectedAndOtherTasksRemainOpen` / `partialCoverageOnlyCancelsReferencedOrigin` / `duplicateOriginReferenceIsRejected` |
| A5-08 版本+独立性 | `submitterCannotCompleteOwnTaskAndStaleRevisionIsRejected` |
| TP-04 自编交易 | `inventedTransactionIsRejectedByServerScope` |
| TP-17 同源幂等/追加版本 | `sameSourceSameContentIsIdempotentAndChangedContentAppendsVersion` |
| TP-06/09/10/11/12 代付分支 | `unresolvedAuthorityAllowsSuspiciousWithBasis` 等 5 项 |
| TP-28 草稿隔离 | `frontend/src/views/ExplanationWorkspace.spec.ts` |
