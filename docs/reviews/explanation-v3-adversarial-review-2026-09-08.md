# 解释核验 v3 首批对抗性审查报告

日期：2026-09-08。
审查对象：提交 `3a89bda`（解释核验 v3 首批 S0–S5）。
依据：[第三方代付解释核验计划 v3](../plans/third-party-goods-payment-verification-plan-v3-2026-09-08.md)、
[v3 首批实施记录](../plans/explanation-v3-implementation-notes-2026-09-08.md)。

## 1. 审查结论

**发现 6 项缺陷（2 项 P1、3 项 P2、1 项 P3），已全部修复并转为防回归测试。**
审查方法：逐条对照 v3 计划 §5–§16 与代码路径；用真实 epoch 推进（此前单测的 mock 不推进实体 epoch，
掩盖了幂等缺陷）复现可疑行为后再修复。上一轮"428 通过"的真实含义是默认单测通过，
其中 1 项关键缺陷（D1）正是在旧 mock 假设下不可见的——本次审查将其复现并修复。

| 编号 | 严重度 | 缺陷 | 根因 | 修复 |
|---|---|---|---|---|
| D1 | P1 | TP-27 被违反：成功提交推进真实 epoch 后，同 requestId 幂等重放被误判为"相同幂等键对应不同提交内容"（409），重试永远无法成功 | `inputDigestOf` 混入 `epochAtDraft`——正是 v3 计划 §9.2 明文禁止的"把可变化的 caseFactsEpoch 混入稳定请求摘要"。旧单测 mock 的 `bumpFactsEpoch` 不修改实体 epoch，摘要恒定，掩盖缺陷 | 稳定请求摘要只绑定 caseId/alertId/policyCode/规范化草稿 JSON；epoch 并发保护职责归还 `reviewBasisToken`。防回归：`idempotentReplaySurvivesEpochAdvance`（真实 epoch 推进后重放成功）+ `sameIdempotencyKeyWithRealContentChangeIsStillRejected`（真改内容仍 409） |
| D2 | P2 | `DemoEvidenceSourceAdapter.markUnavailable` 抛 NPE（`ConcurrentHashMap.put(key,null)`） | 用 null value 表达不可用，违反 ConcurrentHashMap 契约；§9 要求适配器能返回 UNAVAILABLE | 不可用改为独立 Set 记录；`fetch` 先查 Set 再查内容。防回归：`sourceUnavailableIsRecordedNotFaked`（UNAVAILABLE 记录 + SOURCE_UNAVAILABLE 问题登记） |
| D3 | P1 | `canExclude/canConfirm` 不含自审限制：blocker 列表显示"实质贡献人"冲突时，`canExclude` 仍为 true——v3 计划 §8 明文禁止"blocker 显示自审冲突而 canExclude=true" | `evaluateReadiness` 返回决策表裸值；决策表 `CaseContext.reviewerIndependent` 也硬编码 true | `canExclude/canConfirm` 按当前操作者视角强制 `&& reviewerIndependent`；新增 `readinessResultForReviewer` 公开方法。防回归：`canExcludeIsFalseWhenReviewerIsContributor`（贡献人视角 false、独立复核人视角 true）。注：`validateReadyForReview` 最终校验原本就会 throw 自审 blocker，服务端最终门禁未失守；缺陷影响的是 GET 接口/UI 能力值与 blocker 的矛盾展示 |
| D4 | P2 | 来源内容变更（同源同 key 追加 v2）只 bump epoch，不触发依赖提交失效；引用 v1 的采用提交在内容已变为 v2 后仍为 CURRENT——TP-16 语义只覆盖显式 supersede 路径 | S1 实现 capture 内容变更路径时遗漏依赖失效计算 | 提取 `staleSubmissionsUsingArtifact` 共用方法；内容变更追加新版本时同样把引用旧版本的 CURRENT 提交置 STALE 并复位覆盖。防回归：`artifactContentChangeStalesDependentSubmissions` |
| D5 | P3 | `proposeDowngrade` 不校验问题处置状态：已 RESOLVED/DISCLOSED 的问题仍可提案降级，确认后改等级并推进 epoch | 提案仅校验 revision 与严重度方向 | 提案前校验 `disposition == OPEN`，非 OPEN 需先重开 |
| D6 | P2 | 同一交易可被同案多个单元的 CURRENT 代付提交重复声明为授权覆盖（TP-31 未落地）；v3 §6.1 要求"首期仅支持可明确划分额度的授权"，此前实现处于既未核对也未限制的中间态 | 跨单元额度去重未实现 | `registerGroupPaymentGaps` 增加跨单元 `authority.coveredTransactionIds` 重叠检查；重叠 → `AUTHORITY_DOUBLE_ALLOCATION` 关键问题（EXPLAINED 被阻断，不自动变可疑）。防回归：`crossUnitDoubleAllocationIsRegisteredAsCriticalIssue` |

## 2. 复核确认的符合项

以下计划要求经代码路径核实为已正确实现：

- **§10 最终复核事务顺序**（ReviewService）：案件锁 → 变更前 `validateReviewBasisToken` → 接续 → 决策表/Clock 重评/自审（`tokenAlreadyValidated=true`，不拿变更前 token 与变更后事实比较）→ 条件更新 → ManualReview → 保存。与 TP-22 一致。
- **§8.1 义务守恒**：`transferObligations` 逐项校验 originRequestId（不存在/非本案件/非 OPEN DECISION_SUPPORT 拒绝）；同任务被多 plan 引用拒绝；只取消被引用任务，未覆盖任务保持 OPEN 阻断；`completionStandard` 完整持久化（V29）。
- **A5-04 双人确认**：提案（ANALYST/ADMIN）→ 确认（认证 REVIEWER/ADMIN，服务端核对账户角色与启用状态，禁提案人自认，提案/问题版本绑定）。
- **A5-08**：条件更新绑定状态+版本；禁 respondedBy 自审；resolvedBy 持久化。
- **TP-04/TP-10/TP-11/TP-12/TP-06**：服务器来源集合对账、额度缺口拒绝整笔解释、部分覆盖缺口保留、CONTRADICTED 阻断、UNRESOLVED+怀疑依据允许 SUSPICIOUS。
- **§4.2 同名消歧边界**：`TRUSTED_SOURCE_SYSTEMS` 与配方核定不做字符串合并判断（消歧为人工步骤，服务端不自动合并主体）。
- **§15 存量与回退**：V28–V30 均为可兼容新增；未修改 V27；未知契约版本仍拒绝写入（未放宽 >=2）。
- **实施记录诚实性**：实施记录声明的未交付项（Claim 读写 API、复核预检、AlertScopePort、sourceFamily、真实 MySQL/E2E）经核实确实未实现且未以其他形式伪装。

## 3. 残留边界（不阻断首批，已列入实施记录）

1. **决策表 `CaseContext.reviewerIndependent` 硬编码 true**：自审判定由 service 层后置补强（本次 D3 修复后能力值正确）。决策表作为纯函数的 reviewerIndependent 输入仍是装饰性参数——如后续把决策表用于其他调用方，需注意该输入不可信。
2. **claim 引用 issue 的 use 记录**：`persistEvidenceUses` 对 issueIds 写 `note="issue:{id}"` 且 `verificationEventId=null`——反查 supersede 只按 artifactVersionId 命中，问题引用不会触发材料级失效（问题处置已有独立的 epoch 推进路径，风险有限）。
3. **真实 MySQL 迁移/并发/E2E 未实跑**（Docker 环境限制沿用）；TP-20/23/24/27 的数据库层验证仍待集成环境。
4. **授权有效期间/撤销状态的时钟比对**（§6.1"比较交易发生时的有效授权"）：授权对象尚未结构化（依赖 Claim 读写 API 批次），当前以 draft.authority 声明 + 缺口问题承载。

## 4. 验证

- 探针先行：`AdversarialAuditProbeTest`（已删除）复现 D1/D2 后，断言转为业务期望，落为 `ExplanationV3AdversarialRegressionTest`（6 项）。
- 修复后全量回归：后端 `./mvnw test` **434 通过 / 0 失败 / 1 跳过**；前端 66 通过（本轮无前端改动）。
