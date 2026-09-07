# 解释核验专项 v2 首批修改验收

日期：2026-09-08；代码基线：`26b082a`，主要功能提交：`81c9542`。

## 1. 验收结论

**基础回归通过，业务闭环验收不通过。** 当前实现已经建立单元提交、混合结论、版本冲突和任务目的分离的框架，但仍存在能够使不充分依据通过最终校验、绕过独立复核、遗漏接续义务的问题。建议完成本报告 A5-01～A5-08 后再开放 v2 业务试点。

本次确认 **8 项 P1、1 项 P2**。其中 9 个服务层特征测试复现了异常行为；这些测试断言的是“当前错误行为确实发生”，通过不代表缺陷已修复。前端串稿和 EDD 复核调用顺序另以代码路径核实，未冒充浏览器或真实数据库实跑。

本次只进行审查、验证与计划编制，未修改业务实现。临时测试源码和 class 已移出正式测试目录，保留在 `.tmp`。

## 2. 验证范围与证据

| 验证 | 结果 | 能证明的范围 |
|---|---|---|
| 前端 `npm.cmd test` | 7 文件、63 项通过 | 现有工具函数、协议及决策表测试 |
| 前端 `npm.cmd run build` | vue-tsc、Vite 通过 | 类型检查与生产构建；仍有大 chunk 提示 |
| 后端默认 Maven test | 407 总计、406 通过、1 跳过、0 失败 | 默认测试；跳过项为 AgentEvalLiveTest |
| ExplanationAcceptanceProbeTest | 7 项异常行为复现 | 调用真实工作区 Service，Repository 使用 Mockito |
| EddAcceptanceProbeTest | 2 项异常行为复现 | 调用真实 EDD Service，Repository 使用 Mockito |
| Docker / 真实 MySQL / 角色 E2E | 未执行 | Docker engine 命名管道不存在，配置文件还存在读取权限问题 |

默认 Maven 配置排除 `integration` 标签，不能将 407 项结果写成“真实迁移、事务回滚、并发和角色端到端验收通过”。本次也未连接开发数据库做破坏性迁移测试。

复现编译遇到本机 Maven 缓存中 `prometheus-metrics-config-1.3.10.jar` 的编译资源关闭错误。确认新 probe class 已生成后，以 `surefire:test` 单独运行，分别得到 7/7、2/2。该结果证明特征测试执行成功，不代表新增测试的完整编译生命周期成功。

本地证据：

- [后端基线日志](/D:/JCode/.tmp/explanation-acceptance-20260908-backend.log)
- [工作区复现日志](/D:/JCode/.tmp/explanation-probes-execution-20260908.log)
- [EDD 复现日志](/D:/JCode/.tmp/edd-probes-execution-20260908.log)
- [工作区复现源码](/D:/JCode/.tmp/ExplanationAcceptanceProbeTest.java)
- [EDD 复现源码](/D:/JCode/.tmp/EddAcceptanceProbeTest.java)

复现运行条件：Java 21、本地缓存 Maven 3.9.9、`.tmp/review-maven-settings.xml`。将相应 probe 源码恢复到其包对应测试目录，编译后可使用 `-Dtest=ExplanationAcceptanceProbeTest#probe*` 或 `-Dtest=EddAcceptanceProbeTest#probe*` 选择执行。修复时必须把特征断言改为业务期望的拒绝、失效或冲突断言。

## 3. 已有成果及范围边界

可以接受为首批交付成果的部分：逐预警解释单元、不可变 payload 提交与修订入口、混合 EXPLAINED/SUSPICIOUS 汇总、旧调查入口对 v2 的拦截、决策表移植、EDD purpose 分离、基础工作区入口。

实施说明已经主动列为未交付的交易身份适配、Dossier 扩展、结构化错误、完整表单和真实集成验收，本次不认定为“已承诺交付却缺失”。但是，未交付能力所依赖的兜底必须真实阻断；不能一边没有权威交易范围，一边允许用任意字符串解决范围问题并最终排除。

## 4. 必须修复的问题

### A5-01 [P1] 材料与交易事实仍由调用方自证，空证据也可以形成排除依据

位置：[ExplanationWorkspaceService.java:242](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:242)、[同文件:794](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:794)。

`captureEvidence` 接受调用方提交的 `contentSha256`，没有获取来源内容，直接设置 RESOLVED；调用方再提交同一个 claimedSha256 即得到 MATCH。来源系统白名单只能限制名称，不能证明记录存在。六问题允许空 artifactVersionIds；有引用时只排除 MISMATCH，没有要求可用来源、可定位事实及满足该事实用途的核验记录。范围对账比较的也是调用方自行声明的交易 ID、金额和分配，VerificationBasisRepository 尚未参与读写。

复现：不存在的来源引用加两份相同 64 位哈希得到 RESOLVED/MATCH；另一例删除全部材料引用、构造 `NONEXISTENT-TRANSACTION` 和自洽的 1.00 元分配，仍可提交 EXPLAINED 并通过最终排除 Service 校验。

修复：服务端抓取受控来源并计算内容摘要；手工登记保持“声明/待核验”。冻结服务器可枚举的完整命中交易集，按源金额对账。每类关键事实有明确的证据可采用条件。范围缺失时保留阻断，不能靠说明字数解除。此项包含已知后续能力的补齐，也包含当前缺失兜底的修复。

### A5-02 [P1] NOT_SATISFIED 未阻断 EXPLAINED

位置：[ExplanationWorkspaceService.java:892](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:892)、[同文件:961](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:961)。

代码只把 UNKNOWN 转成 criticalUnknown；EXPLAINED 分支只检查 unknown/openCritical，没有验证六问题全部为允许的 SATISFIED 或具有适用依据的 NOT_APPLICABLE。

复现：把合法草稿 Q3 改成 NOT_SATISFIED，仍可提交 EXPLAINED 并通过最终排除校验。页面文案或决策表中传入的 assessmentValid=true 不能替代真实草稿验证。

修复：建立逐题可采用条件；NOT_SATISFIED 禁止 EXPLAINED，但不自动变成 SUSPICIOUS。后者仍需要人给出基于事实的怀疑理由。

### A5-03 [P1] 案件级待分派关键材料不阻断最终排除

位置：[ExplanationWorkspaceService.java:703](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:703)、[同文件:733](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:733)。

抓取材料创建 unitId=null 的 FACT_UNASSIGNED / DECISION_CRITICAL 问题；就绪评估只检查案件完整性问题和绑定单元的关键问题，没有处理这种案件级待分派事项。

复现：已有 EXPLAINED 提交后加入 OPEN 的案件级待分派反证，重新取得当前 token，最终排除校验仍通过。epoch 使旧 token 失效，只能提示刷新，不能阻止刷新后忽略新反证。

修复：待分派事实必须完成关联或有依据的不相关认定，清单非空时不得形成最终决定。处置、贡献人和受影响提交由服务器派生。

### A5-04 [P1] 问题降级的“独立复核人”可以由分析员编造

位置：[ExplanationWorkspaceService.java:589](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:589)；请求由 [ExplanationController.java](/D:/JCode/backend/src/main/java/com/bank/aml/controller/ExplanationController.java) 的分析员/管理员接口接收。

只校验 confirmedBy 非空且不同于 actor，没有真实复核人的认证动作、角色与启用状态核对。

复现：分析员传入 `nonexistent-reviewer-999`，成功将关键问题降为 CONTEXT_GAP。

修复：分析员提交降级提案，另一位已认证 REVIEWER/ADMIN 在独立请求中确认；服务端写确认身份并检查贡献人冲突及提案、问题版本。移除客户端 confirmedBy 的权威意义。

### A5-05 [P1] 提交没有记录材料反向引用，来源变化无法使采用提交失效

位置：[ExplanationWorkspaceService.java:452](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:452)、[同文件:352](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:352)。

submitUnit 收集材料引用后未写 explanation_evidence_use；markArtifactSuperseded 依赖该表反查。当前回归甚至断言 evidenceUses.save 从未调用。同一来源再次 capture 还总是返回已有版本，来源内容改变也不产生 v2。

复现：正常提交使用材料 1，真实提交方法没有调用 evidenceUses；随后 supersede 材料 1 得到空影响集合，提交仍为 CURRENT。

修复：提交、使用关系、依据快照在同一事务持久化；同源同内容幂等、不同内容追加版本；每种事实更正/核验结论变更都触发依赖影响计算并验证受影响单元失效。不能仅公开一个没有调用链的失效方法。

### A5-06 [P1] 最终复核没有重新评估交期，并无条件认为跟进已安排

位置：[ExplanationWorkspaceService.java:631](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:631)、[同文件:928](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:928)。

交期只在单元提交时计算；最终复核读取冻结布尔值。validateReadyForReview 无条件传 continuationArranged=true，没有按单元核实持续任务是否真实存在、是否覆盖该义务。

复现：9 月 10 日提交交期为 9 月 11 日的预付解释，确认 followupRequired=true；把注入 Clock 推进到 9 月 12 日，且不存在任何 EDD 任务，最终排除校验仍通过。

修复：最终事务内按 Clock、当前履约事实和政策重新计算到期条件，逐义务验证任务覆盖。到期不会自动变可疑；缺交付/延期依据应恢复关键待核验事项。

### A5-07 [P1] EDD 接续缺少逐项对应，最终调用顺序还会使有效令牌失效

位置：[EnhancedDueDiligenceService.java:148](/D:/JCode/backend/src/main/java/com/bank/aml/review/EnhancedDueDiligenceService.java:148)、[ReviewService.java:105](/D:/JCode/backend/src/main/java/com/bank/aml/review/ReviewService.java:105)。

复现：案件有两个 OPEN 决策支持任务，提交 originRequestId=999 的无关接续计划，两项原任务都被 CANCELLED。代码未核实原任务归属、逐条问题绑定或义务覆盖，遍历取消的是全部 OPEN 决策支持任务。completionStandard 只做字数验证并写长度审计，未专门保存其完整值；调用方可以不把它重复写进 issueBindingsJson。

另一个已核实的代码路径问题：ReviewService 先创建/取消任务，再校验用户提交的 reviewBasisToken；token 包含 OPEN 任务 ID 集合，因此正常接续改变集合后，变更前取得的 token 会失效。该顺序缺陷尚未做真实数据库复现，不能宣称数据库事务验收已完成。前端 ReviewView 也尚未提供 continuationTasks 录入与模拟预检入口。

修复：案件锁内先验证变更前 token，再校验拟议接续覆盖与模拟决定；随后原子落地。仅取消已被完整接替的原任务，保存完整完成标准和 originReviewId。事务结束前对新状态复核不变量，不能要求旧 token 等于本事务自己变更后的 token。

### A5-08 [P1] EDD 完成接口忽略版本，并允许材料提交人自行完成

位置：[EnhancedDueDiligenceService.java:304](/D:/JCode/backend/src/main/java/com/bank/aml/review/EnhancedDueDiligenceService.java:304)。

expectedRevision 只用于审计键，没有与当前版本比较；也未比较 resolvedBy 与 respondedBy/实质贡献人。角色权限不能避免有两种操作权限的 ADMIN 自审。

复现：任务实际 revision=5、respondedBy=admin-a，传 expectedRevision=0 且完成者仍为 admin-a，任务变成 RESOLVED、revision=6。

修复：同案件锁或条件更新检查版本、SUBMITTED 状态、操作者独立性；保存 resolvedBy 与具体核验依据。两位复核人竞争完成仅允许一位成功，另一位得到版本冲突。

### A5-09 [P2] 工作区草稿按页面共享，跨单元编辑会带入其他预警内容

位置：[ExplanationWorkspace.vue:26](/D:/JCode/frontend/src/views/ExplanationWorkspace.vue:26)、[同文件:60](/D:/JCode/frontend/src/views/ExplanationWorkspace.vue:60)、[同文件:77](/D:/JCode/frontend/src/views/ExplanationWorkspace.vue:77)。

只有一个 draftText，所有单元的编辑与保存共用；打开 B 单元会显示此前 A 的内容。UnitView 又不返回服务器草稿，刷新后无法从现有工作区响应恢复已保存内容。用户可能把 A 的分析保存到 B，后端又缺少权威交易范围校验，影响进一步放大。

修复：按 caseId/unitId 隔离本地草稿，服务端返回版本化草稿；冲突时保留本地版本并展示服务器差异，明确确认后保存。以真实组件交互覆盖 A→B→A、刷新恢复和并发冲突。

## 5. 为什么原测试没有发现

决策表测试验证的是已整理好的布尔条件；真实请求如何产生这些条件是另一层。当前 Service 测试普遍预置“合法材料、空问题、固定 epoch”，没有模拟数据库 bumpFactsEpoch 的实际推进，也没有把 ReviewService、工作区服务、EDD 和权限链连在一起。

下一次必须以“来源数据 → 请求 → 事务写入 → 决策 → 档案”作为验收单位。尤其补测真实 epoch 下的幂等重试、接续前后 token、材料版本更新、任务重复完成；这些项目不能以当前 mock 结果替代。

## 6. 下一步

先按 A5-01～A5-09 修复并把复现改成防回归测试，再进入 [第三方代付解释核验详细计划 v3](/D:/JCode/docs/plans/third-party-goods-payment-verification-plan-v3-2026-09-08.md)。该计划将基础补齐与新增业务价值分别设定验收关口，避免再次把对象、接口和测试数量当成业务闭环成果。
