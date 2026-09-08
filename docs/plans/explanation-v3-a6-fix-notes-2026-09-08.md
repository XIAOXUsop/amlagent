# A6 修复实施记录（v3 再次验收 2026-09-08）

依据：[解释核验 v3 再次验收](../reviews/explanation-v3-reacceptance-2026-09-08.md)（A6-01～A6-06）、
[闭环补齐方案](explanation-v3-closure-plan-2026-09-08.md) R1/R2/R3/R4 服务层部分。

## 修复明细（此前错误输入 → 修复后的期望）

| 编号 | 此前错误输入 | 修复后的期望 | 实现位置 | 防回归测试 |
|---|---|---|---|---|
| A6-01 [P1] | 删除 Q1～Q6 全部材料引用（保留 SUPPORTED）→ EXPLAINED + 最终排除成功 | 草稿可保存；提交被拒："空引用不能形成可采用结论"；引用了材料但零核验记录也被拒（登记 ≠ 已核验） | `validateDraftContent`：SATISFIED/NOT_SATISFIED 必须引用 ≥1 份 **RESOLVED** 材料 + 该材料有核验记录；非 RESOLVED（NOT_FOUND/UNAVAILABLE/FORBIDDEN）引用直接拒绝；NOT_APPLICABLE/UNKNOWN 例外 | `emptyEvidenceDraftCanBeSavedButExplainedIsRejected`、`referencedMaterialWithoutVerificationIsRejected`、成功样例 `verifiedMaterialSupportsExplainedAndFinalExclusion` |
| A6-02 [P1] | ① 删除整个 authority → 代付 EXPLAINED 成功；② authority 只覆盖 T-1001（320,000），T-1002（120,000）未覆盖仍整笔解释 | ① 提交被拒："必须声明代付授权"；② 提交被拒："T-1002 未被授权覆盖"——服务端从 scope.reviewedTransactionIds 派生资金腿全集，不从客户端 covered 反向定义 | `validateGroupPaymentClaims`：authority/authorityRef/limitAmount/coveredTransactionIds 必填；covered 逐笔对账 + 必须覆盖全部 scope 交易；额度缺口（TP-10 保留） | `absentAuthorityIsRejected`、`authoritySubsetIgnoresUncoveredPaymentIsRejected`、`authorityLimitExceededEvenWithFullCoverage`（TP-10 额度语义）、成功样例 `fullAuthorityCoverageSupportsExplained` |
| A6-03 [P1] | 预付 followupRequired=true、OPEN 任务为空 → 最终排除通过（continuationArranged 硬编码 true） | 最终校验逐义务匹配：同案件 OPEN CONTINUING_REVIEW + 有效承办人（启用 ANALYST/ADMIN）+ 未来期限 + 完成标准 ≥10 字符；一项义务一个专属承接；无承接 → "未到期不等于已安排" 阻断 | 新 `validateObligationCoverage`；`validateReadyForReview` 合并义务 blocker；提交按 ID 去重（同事务重复 save 不重复计义务） | `prepayWithoutContinuingTaskIsBlocked`、成功样例 `prepayWithQualifyingContinuingTaskPasses`（合格任务放行，防"一律拒绝"） |
| A6-04 [P1] | 页面补件（REQUEST_ENHANCED_DUE_DILIGENCE，不取号）被令牌冲突拦截，EDD Service 未被调用 | 补件豁免最终依据 token（保留案件锁/reviewRevision/角色/任务状态检查）；最终确认/排除缺 token 仍拒绝 | `ReviewService.submit`：token 校验分支加 `decision != REQUEST_ENHANCED_DUE_DILIGENCE` | `uiEddRequestWithoutTokenCreatesTaskPath`（不再令牌冲突）、`finalExclusionStillRequiresToken` |
| A6-05 [P1] | 核验 CONFIRMED → UNRESOLVED 后旧 EXPLAINED 仍 CURRENT，重取 token 后最终排除通过 | UNRESOLVED/MISMATCH → 通过 evidence_use 反查依赖提交 STALE、覆盖复位；重取 token 后因"尚无可采用的单元提交"阻断 | `recordVerification` 尾部接入 `staleSubmissionsUsingArtifact` | `reverificationUnresolvedInvalidatesAdoptedSubmission`（含重取 token 仍阻断断言）、`reverificationUnresolvedWithoutDependentsIsRecordedOnly` |
| A6-06 [P1] | 未取得内容写 contentSha256=null，与实体 nullable=false / 列 NOT NULL 冲突（真实库持久化会被拒；mock 掩盖） | V31 迁移：列放宽 NULL + CHECK（RESOLVED 必须有 64 位十六进制摘要）；实体同步 nullable；服务端保存不变式校验（RESOLVED 缺摘要或未取得却带摘要 → 立即抛错）；禁止伪造摘要 | V31 + `EvidenceArtifactVersion` + `captureEvidence` 不变式 | `unavailableSourceSavedWithoutDigestAndInvariantHolds`（UNAVAILABLE 无摘要落库 + SOURCE_UNAVAILABLE 问题 + 来源恢复后 v2 RESOLVED 带摘要） |

## 附带语义修正（审查中发现的本轮缺陷）

- **TP-31 跨单元共享命中**：此前 AUTHORITY_DOUBLE_ALLOCATION（DECISION_CRITICAL，阻断 EXPLAINED）把"同一交易命中两个预警"误判为异常。修正为 `AUTHORITY_SHARED_REFERENCE`（CONTEXT_GAP）：共享命中复用解释引用、去重计量不阻断；人工核对后处置。防回归更新于 `ExplanationV3AdversarialRegressionTest.crossUnitDoubleAllocationIsRegisteredAsCriticalIssue`（断言改为提交成功 + CONTEXT_GAP 登记）。
- **persistEvidenceUses 注释纠正**：原注释声称"无引用的 EXPLAINED 已被拒绝"当时与实际不符（A6-01 修复后成立）；已改为准确表述，CONTEXT 占位记录只可能出现在 UNRESOLVED 提交。

## 验证结果

- 后端 `./mvnw test`：**448 总计 / 447 通过 / 0 失败 / 1 跳过**（跳过项为默认不调真实模型的 AgentEvalLiveTest；口径按上轮验收要求以"通过/跳过"分列）。
- 前端：**66 通过**；vue-tsc + Vite 构建通过（本轮无前端接口签名变化）。
- 新增迁移：**V31**（content_sha256 放宽 NULL + RESOLVED 摘要 CHECK）。未修改已应用的 V27。
- 新增正式测试：`ExplanationV3ReacceptanceTest` 13 项（6 项缺陷的反转断言 + 6 项成功样例 + 边界）。

## 边界与未闭合项（不属本轮修复范围）

1. **真实 MySQL 落库验证（RC-02 全语义）**：V31 CHECK 与 null 摘要的真实持久化需隔离 MySQL（闭环方案 R5）；当前验证到实体/服务不变式层。
2. **Claim→材料→核验事件结构化绑定**（闭环方案 §4.1 完整版）：本轮 A6-01 用"问题级材料引用 + 核验存在性"落地最小可用条件；Claim 版本冻结、sourceFamily 独立性、AlertScopePort 逐预警范围仍为未交付项。
3. **A6-04 的页面契约**：ReviewView 对三种决定的请求契约测试（前端侧）未新增；后端协议已按"补件豁免/最终强制"闭合。
4. **义务与具体事实键绑定**（闭环方案 §6：`AU-01:authority:SO-02` 级）：本轮义务匹配粒度为"采用提交含 followupRequired"，逐事实键绑定待 R4 完整版。
