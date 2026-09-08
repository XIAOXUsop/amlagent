# 全量回归与业务验收报告

日期：2026-09-08。验收基线：`7ad1ff5`，包括 `b9933f3` A6 修复、`7d5f846` R5/预检、`7ad1ff5` 专项数据库测试。结论：**基础功能与数据库修复有实质进展；业务最终排除链仍不满足整体验收条件。**

本报告区分已有测试通过、反例复现、静态审查和未覆盖范围。它不代表生产环境验收或真实模型效果认证。本次未修改业务代码；新增报告与下一代计划，临时探针源代码和日志保留在工作区 `.tmp/full-regression-20260908`。

## 1. 应先处理的发现

### FR-01 / P1：最新核验无法确认，重新提交仍可最终排除

位置：[ExplanationWorkspaceService.java:1751](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1751)。

现有修复能在追加 `UNRESOLVED` 核验事件时将被采用的旧提交置为 `STALE`，这一点有效。但重新提交只要求材料存在任意核验记录，没有验证最新有效事件是否仍支持该问题。

本轮服务层反例：取得材料并核验 `CONFIRMED` → 提交 `EXPLAINED` → 追加 `UNRESOLVED` → 确认旧提交已失效 → 保留草稿、取得新 token、换幂等键再次提交 → 新提交为 `CURRENT`，`validateReadyForReview(EXCLUDE_FALSE_POSITIVE)` 不抛异常。探针：`probeUnresolvedVerificationCanResubmitAndExclude`。

影响：失效只阻止使用旧提交，不能阻止以同一份已失去支持的依据生成新结论。重新取号不应修复业务事实。

修复要求：按当前问题/Claim 绑定的材料版本和具体核验动作计算可采用性；拒绝被后续事件更正、撤回或置为未解决的支持。`artifactIds` 当前还在 Q1～Q6 间累积，后题可能借用前题的核验记录；应改为逐题/逐 Claim 校验。后一分支为静态确认，未单独运行跨题探针。正向用例必须允许完成有效补核验后再提交。

### FR-02 / P1：全部“不适用”可绕过证据要求

位置：[ExplanationWorkspaceService.java:1698](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1698)、[EXPLAINED 校验:1806](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1806)。

本轮服务层反例使用普通已交付货款配方，把 Q1～Q6 全部设为 `NOT_APPLICABLE`，理由统一填写“本问题不适用”，删除所有材料引用，不抓取任何材料。提交成功并通过最终排除校验。探针：`probeAllNotApplicableCanExcludeWithoutAnyEvidence`。

当前只检查理由长度，没有政策允许跳过的具体问题和适用条件。空证据防线对 `SATISFIED` 生效，但仍可整体改成“不适用”通过。

修复要求：由政策版本定义哪些问题允许不适用、具体条件与所需替代事实；核心主体、交易对应、关键差异不得仅凭用户自由文本豁免。不支持的例外应保留 `UNRESOLVED`，允许保存草稿。此反例已证明普通货款配方问题；不将其扩大表述为集团代付 C1～C4 可全部跳过。

### FR-03 / P1：跟进任务数量符合，业务义务仍可能没有承接

位置：[ExplanationWorkspaceService.java:1138](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1138)。

当前按 `followupRequired` 的提交遍历同案件 OPEN 持续核验任务，检查有效承办人、未来期限、至少 10 字的完成标准，并避免同一任务重复匹配；没有检查任务究竟绑定哪项交付义务。

本轮复用预付成功样例，仅将任务完成标准改为“核对另一客户的联系电话与办公地址，不涉及本合同交付”，未设置本合同的义务绑定，最终排除校验仍成功。探针：`probeUnrelatedContinuingTaskPasses`。

影响：任务存在不能证明未完成交付有人接续。此前修复“完全没有任务”的反例已通过，但尚未实现业务上有效的逐义务承接。该限制也已在 A6 实施记录中披露，本轮补充了可执行的影响证据。

修复要求：显式绑定 `obligationId + originSubmissionId + unitId + allocationId/claimId`，服务端核对完成条件类型和责任范围；缺口必须显示为具体义务。不要尝试仅靠匹配完成标准中的关键词解决。

### FR-04 / P1（验收有效性）：RAG 四路零召回也能 A/B 测试通过

位置：[RagSecurityAndPipelineIntegrationTest.java:56](/D:/JCode/backend/src/test/java/com/bank/aml/evaluation/RagSecurityAndPipelineIntegrationTest.java:56)。

完整集成首轮中，真实重排序模型不可用，A/B 用例正确失败。指定本机已有模型目录后，定向重跑该类 2 项测试，Maven 返回成功，但日志同时显示：

```text
候选索引未通过发布门禁：coldP95Ms=1537.0 > 750.0
RAG_ADVERSARIAL total=151 fixtures=54 refusal=100.0 abstention=41.7 recall=0.0
DENSE / LEXICAL / HYBRID / HYBRID_RERANK:
recall=0.0 top3=0.0 mrr=0.0 ndcg=0.0 refusal=100.0
Tests run: 2, Failures: 0, Errors: 0
```

模型可加载不等于完整检索链可用；该测试只核对四路结果键和每路 18 条样本，没有要求 active 索引存在、可回答样本实际召回、重排确实执行或指标达到基线。全拒答能满足部分安全断言，却不能说明法规问答可用。

修复要求：将“索引成功发布/有有效 active 版本”“可回答集合质量”“不可回答集合拒答”“真实重排调用”分别设门禁。冷延迟超限应保留失败证据，不能为通过验收直接放宽阈值。此次延迟是在本机并行负载下测得，不据此推断生产 P95 或认定性能必然退化。

## 2. 回归矩阵与实际结果

| 层次 | 执行内容 | 结果 | 能证明的范围 |
|---|---|---|---|
| 后端默认套件 | Maven 离线 `verify`，Java 21，fork javac、禁用注解处理 | **450 总计，449 通过，1 跳过，0 失败/错误；打包成功** | 默认测试及构建通过；跳过的是 AgentEvalLiveTest |
| 集成全套 | `-Pintegration-test test`，真实隔离 MySQL、Redis、PGVector | **31 总计，30 通过，1 失败，0 跳过/错误** | 失败为真实 reranker 未加载；详见 FR-04 |
| RAG 依赖纠正后定向复跑 | 指定本机 bge 模型目录，独立向量表 | **2/2 断言通过，但 A/B 四路零召回** | 仅证明现有断言通过，不计为检索质量验收通过 |
| 前端单元测试 | Vitest | **8 文件，66/66 通过** | 当前前端自动测试覆盖范围 |
| 前端构建 | `vue-tsc -b && vite build` | **通过** | 类型检查与生产包；Element Plus 分块约 558 KB 有体积警告 |
| 浏览器原有全套 | Chrome headless、真实后端、全新 MySQL schema | 首轮 **6/7 通过**，创建后的跳转 5 秒断言超时；诊断后原样复跑 **7/7 通过** | 登录、菜单角色、退出、创建并进入详情；保留首轮不稳定记录 |
| 补充业务反例 | 3 个服务层特征探针 | **3/3 复现错误放行** | FR-01～03；“探针通过”表示缺陷存在，不是业务正确 |

不将重复运行相加成测试总数，不把 151 条 RAG 数据样本当作 151 个独立 JUnit 用例，也不把“450 总计、1 跳过”写成“450 通过、另有 1 跳过”。

### 2.1 集成套件展开

| 测试类 | 用例数 | 首轮结果 |
|---|---:|---|
| FlywayHibernateValidateTest | 1 | 通过 |
| FlywayMigrationTest | 1 | 通过 |
| InvestigationContractMigrationTest | 1 | 通过 |
| RagEvaluationIntegrationTest | 1 | 通过 |
| RagSecurityAndPipelineIntegrationTest | 2 | 1 通过、1 失败 |
| ExplanationV3DatabaseIntegrationTest | 3 | 通过 |
| KeywordLegalSearcherIntegrationTest | 1 | 通过 |
| CsrfSecurityTest | 12 | 通过 |
| ReliabilityWorkflowTest | 7 | 通过 |
| WorkflowE2ETest | 2 | 通过 |

### 2.2 本次可确认的修复成果

V1～V31 全链迁移及 Hibernate validate 在隔离 MySQL 通过；不可用来源能够以 NULL 摘要落库，数据库拒绝 RESOLVED + NULL 摘要；来源恢复追加新版本；真实 epoch 推进后的提交幂等重放已有数据库测试。A6 空 SATISFIED 引用、缺授权/部分授权、完全缺任务、补件 token 协议、UNRESOLVED 使旧提交失效等已有回归通过。

这说明近期修复有效，不需要回退整个批次。剩余问题主要在“通过结构检查”与“事实足以支持该业务结论”之间。

### 2.3 浏览器结果的边界

首次默认 Playwright 启动失败后改用本机已安装 Chrome，测试内容保持原样，临时配置在证据目录中。首轮真正运行时创建案件已成功，页面仍停留列表，5 秒跳转断言失败；独立诊断创建返回 200 并正常跳转，原全套再次运行 7/7 通过。冷加载/瞬态耗时是待验证假设，尚未确定根因，不能将增加超时当作业务修复。

这些用例**没有**覆盖 v2 分析员提交解释、独立核验、复核员确认并接续义务、争议退款、最终档案回放。诊断创建结果 `investigationContractVersion=1`，不能用这个创建成功宣称 v3 集团代付浏览器链已验收。

## 3. 业务完整性静态审查

以下是已明确的交付缺口，不冒充本轮完成端到端复现的新增缺陷：

| 项目 | 代码证据/现状 | 业务含义与下一步 |
|---|---|---|
| 预警交易范围 | [requiredLegs:1443](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1443) 取草稿 reviewedTransactionIds | 客户流水存在性不等于该预警命中全集；补 AlertScopePort 或等效服务器冻结范围，不能由调查者自选子集定义“全部” |
| 核验与 Claim 绑定 | 实体和仓储存在；提交主要读草稿、按问题关联材料 | 仍缺具体事实→核验动作→版本支持关系；不能证明授权主体、期间、订单均独立核验 |
| 复核预检/页面接续 | [ReviewService:63](/D:/JCode/backend/src/main/java/com/bank/aml/review/ReviewService.java:63)、[ReviewView:225](/D:/JCode/frontend/src/views/ReviewView.vue:225) | 预检有只读差异，但 readiness 取当前状态；页面在预检前已按当前状态阻断，未提交接续计划，合法“确认并接续”仍缺完整交互 |
| 复核来源链 | [ReviewService:209](/D:/JCode/backend/src/main/java/com/bank/aml/review/ReviewService.java:209) 传入 originReviewId=null | 接续任务难以精确追溯是哪次决定承接；补事务内关联和失败注入回滚 |
| 历史档案 | [CaseDossierService:238](/D:/JCode/backend/src/main/java/com/bank/aml/dossier/CaseDossierService.java:238) 使用 draftJson，currentOutcome=null，只取最近 basis | 草稿变化与当时已采用的解释混在一起；导出应绑定 reviewId 和不可变提交，而非把当前草稿展示为历史决定依据 |
| 证据原文 | EvidenceArtifactVersion.contentLocation 已定义，当前解释链未见赋值写入 | 摘要不能替代当时的原文；在真实来源变化后须能恢复已采用版本及定位 |
| 退款业务 | Q6 文案已询问退款、冲正、撤销；现有主配方没有独立退款处置/金额分配闭环 | 文本问到退款不等于能把新退款关联到旧代付及旧结论；这是下一代聚焦点 |

## 4. 证据位置与复现口径

全部路径位于 [本轮证据目录](/D:/JCode/.tmp/full-regression-20260908)。关键文件：

| 文件 | 内容 |
|---|---|
| backend-7ad1ff5-verify.log | 当前提交默认后端测试与构建 |
| backend-7ad1ff5-integration.log | 集成 31 项全套及首轮失败 |
| backend-7ad1ff5-rag-recheck.log | 模型目录纠正后的真实重排及零召回通过证据 |
| browser-7ad1ff5.log / browser-7ad1ff5-recheck.log | 浏览器首轮与原样复跑 |
| browser-diagnostic.log | 创建 200、案件 ID 和实际 URL；使用隔离演示数据 |
| FullRegressionBoundaryProbeTest.java | 复用当前 Reacceptance fixture 的特征探针副本，仅运行 `probe*` 三项 |
| business-boundary-probes.log / business-boundary-probes-execution.log | 探针编译尾部权限异常及显式 Surefire 执行结果 |
| playwright.regression.config.cjs | 本机 Chrome 的临时回归配置 |

命令主干：后端 `mvn -o -s .tmp/review-maven-settings.xml verify` / `-Pintegration-test test`，明确 Java 21、Maven 3.9.9、本地依赖仓库、`maven.compiler.fork=true`、`maven.compiler.proc=none` 和工作区 java.io.tmpdir。模型补跑增加 `-Daml.rag.rerank.model-dir=C:/Users/asus/.cache/aml-reranker/bge-reranker-base -Dtest=RagSecurityAndPipelineIntegrationTest`。数据库通过 `MYSQL_TEST_HOST=127.0.0.1:13307` 指向本轮新建实例，各测试使用自己的 schema；RAG 使用本轮独立向量表名。

探针编译在输出 class 后关闭 Maven 缓存中的 JAR 时遇到 Windows `AccessDeniedException`。确认新 class 已生成后，使用同一套件依赖执行 `surefire:test -Dtest=FullRegressionBoundaryProbeTest#probe*`，三项实际执行通过。这里不将编译命令标为成功。临时探针已从正式测试目录及编译输出移除，副本留证；默认 450 项结果来自加入探针之前。

本轮开始时旧工作树的失败日志以 backend-verify.log/backend-verify-forked.log 保留；基线更新后重新执行了当前提交，它们不作为 `7ad1ff5` 的失败结论。

收尾时已停止本轮隔离应用及前端进程，并通过 mysqladmin 正常关闭 13307 实例；测试数据目录和日志保留。没有停止原有 MySQL、Redis、PGVector 服务。最终 HEAD 仍为 `7ad1ff5`，Git 仅新增本报告与计划书，业务代码无差异。

## 5. 签收建议与下一步

可以签收：近期数据库映射、迁移约束、补件 token 和旧提交失效的基础修复。暂不签收：解释最终排除业务闭环、真实检索质量、三角色集团代付完整页面链。

先将 FR-01～03 的特征探针反转为“错误输入必须拒绝”的正式回归，连同正向补核验/有效例外/正确义务绑定的成功样例落入真实库与页面测试；FR-04 增加实质指标和实际执行门禁。

下一代聚焦 **已交付集团代付后的部分退货退款复核**，用新事件检验旧解释能否继续适用，并精确回答剩余 12 万退给了谁、为什么、依据哪份授权、谁负责查完。详见 [下一代详细计划书](/D:/JCode/docs/plans/group-payment-lifecycle-iteration-plan-v4-2026-09-08.md)。
