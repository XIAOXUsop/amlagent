# G0 验收缺口修复实施记录（v4 批次 1）

日期：2026-09-08。基线：`7ad1ff5`。
依据：[v4 计划 §4（G0）](group-payment-lifecycle-iteration-plan-v4-2026-09-08.md)、RF-01～03、RF-29。

## 修复明细（此前错误输入 → 修复后的期望）

### FR-01 统一核验可采用性评估器（RF-01/RF-03）

| 此前错误输入 | 修复后的期望 |
|---|---|
| Q2 只用未核验材料、Q1 有核验 → Q2 借用 `artifactIds` 累积集合通过（跨题共享 bug） | `EvidenceAdmissibilityService.assessQuestion` **每题独立评估**：Q2 阻断 `VERIFICATION_MISSING`，"其它问题的核验不能替代本题" |
| 同一材料最后一条核验事件代表所有事实 | 核验链按"材料版本 + subjectFactKey（Q1~Q6/Claim 事实键）+ 事件序号"组织（V32 迁移，NULL=材料级通用核验兼容存量）；同一材料 Q2=UNRESOLVED、Q3=CONFIRMED 互不错误覆盖 |
| 最新核验 CONFIRMED→UNRESOLVED 后仍提供肯定支持 | 链内最新状态生效：`UNRESOLVED/MISMATCH` → `VERIFICATION_LOST` 阻断；有效补核验后恢复（调查可继续） |
| 结构化 blocker | `blockerCode（EVIDENCE_EMPTY/ARTIFACT_FOREIGN/INTEGRITY_BLOCKED/ARTIFACT_NOT_RESOLVED/VERIFICATION_MISSING/VERIFICATION_LOST）+ questionCode + artifactVersionId + remediation` |

实现：新 `EvidenceAdmissibilityService`（提交/readiness/预检/最终提交统一调用入口已就位——提交路径已接入）；
`recordVerification` 新增 `subjectFactKey` 参数（Controller 可选字段，前端 client 同步）。

### FR-02 NOT_APPLICABLE 例外核定（RF-02）

| 此前错误输入 | 修复后的期望 |
|---|---|
| 六题全部 NOT_APPLICABLE（仅理由文字）→ 可提交 EXPLAINED | 提交被拒："不允许整体不适用（FR-02）"；`ExplanationPolicyCatalog.notApplicableAllowed(policy, question)` 服务端核定——第一版三种配方六题全为核心（例外清单空集，结构可扩展）；`POLICY_VERSION` 常量就绪（payload 不可变保证历史提交不受后续政策修改影响） |

### FR-03 义务绑定业务对象（RF-04）

| 此前错误输入 | 修复后的期望 |
|---|---|
| 无关任务（绑定退款权限义务）承接交付义务 → 覆盖成立 | `validateObligationCoverage` 按 **obligationFactKey 逐项匹配**（V33 迁移：factKey/amount/transactionIds）；错绑 → "义务 DELIVERY:PO-xxx 无有效承接……错绑任务不能替代本义务"阻断 |
| 任务存在即覆盖（数量匹配） | 义务事实键从提交 payload 派生（预付 → `DELIVERY:{contractNumber}`）；无键存量任务不参与新语义匹配；`ContinuationTaskPlan` 强制 obligationFactKey（无绑定不能视为承接）；接续请求契约扩展（Controller/前端 client） |

### FR-04 RAG 评测实际管线门禁（RF-29）

| 此前错误输入 | 修复后的期望 |
|---|---|
| rerank 模型不可用时 A/B"四路比较"零召回静默通过 | `RagEvalReport` 新增 `rerankInvocations`（实际重排发生的样本数）与 `environmentFailure`；`HYBRID_RERANK` 管线 rerank 一次未发生 → 环境失败（质量指标不代表检索能力）；兼容构造保存量调用 |

## 验证

- 默认：**462 通过 / 0 失败 / 1 跳过**（450 + 12 新增：EvidenceAdmissibilityTest 5、RagEvalReportGatingTest 4、Reacceptance +3、RF-04 1）
- 集成：**31 通过 / 0 失败**（V32/V33 真实迁移 + Hibernate 校验通过）
- 前端：66 通过 + 构建通过（`recordEvidenceVerification` 契约同步）

## 边界

1. `EvidenceAdmissibilityService` 目前接入单元提交路径；readiness/复核预检的二次调用留待 G3 统一（§7.1 预检与提交同一评估函数）。
2. FR-02 例外清单第一版为空集；业务签认后登记局部例外（G4 样本签认流程）。
3. FR-04 的全链路 A/B 管线门禁在集成环境验证（本批锁定报告语义与构造点）；CI 冷启动制品核对（§4.4 完整版）未实施。
4. RF-01"服务器+真实库"全链路断言待 G2/G4 集成用例（本批为服务层+数据库迁移层）。
