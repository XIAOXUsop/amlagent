# 解释核验 v3 再次验收

时间：2026-09-08 上午。基线：`9199924`；本次增量为 `3a89bda` 与 `9199924`，对照上次验收基线 `26b082a`。

## 1. 结论

**修复有实质进展，默认回归通过；仍不能判定解释核验和代付业务闭环验收通过。**

上轮 A5-02、A5-03、A5-04、A5-08、A5-09 的主要修复路径已由代码与当前测试确认；A5-01、A5-05、A5-06、A5-07 仍有未闭合的业务条件或新回归。这里的“修复路径确认”限于单元/组件层，不包含真实数据库、并发或认证端到端验收。

本次重点确认 **6 项 P1**：5 类服务/页面协作问题由 6 个针对性特征测试复现；另 1 项为代码与数据库非空约束直接冲突，未宣称已连接 MySQL 复现。暂不推进更多业务分支，下一步应集中完成 [v3 闭环补齐方案](/D:/JCode/docs/plans/explanation-v3-closure-plan-2026-09-08.md)。

## 2. 本轮实际验证

| 项目 | 本次结果 | 边界 |
|---|---|---|
| 前端测试 | 8 文件、66 项通过 | 包含草稿隔离组件测试 |
| 前端生产构建 | vue-tsc + Vite 通过 | 仍有大 chunk 提示，不作为此次业务阻断项 |
| 后端默认测试 | **434 总计、433 通过、1 跳过、0 失败/错误** | 不含默认排除的 integration 标签 |
| 针对性复现 | 6 项特征测试复现异常 | 真实 Service，模拟 Repository；epoch 实际在测试实体中递增，核验事件列表随保存更新 |
| MySQL 迁移、并发、事务回滚与角色 E2E | 未执行 | Docker engine 命名管道不存在；Docker 配置读取另有权限问题 |

现有对抗性审查记录写“434 通过 / 1 跳过”，与本轮 Maven 的总计口径不一致。应以 **433 通过、1 跳过**记载本轮结果。

新 probe 的编译再次遇到本机 Maven 缓存 `prometheus-metrics-config-1.3.10.jar` 的编译资源关闭错误。确认本次 class 已生成后单独调用 surefire 执行；因此探针执行通过不等于新增测试的完整编译生命周期通过。特征测试的通过表示异常存在，修复时要反转为正确业务期望。

证据文件：

- [后端回归日志](/D:/JCode/.tmp/explanation-reacceptance-backend.log)
- [前端回归日志](/D:/JCode/.tmp/explanation-reacceptance-frontend.log)、[构建日志](/D:/JCode/.tmp/explanation-reacceptance-build.log)
- [本轮特征测试源码](/D:/JCode/.tmp/ExplanationReacceptanceProbeTest.java)、[执行日志](/D:/JCode/.tmp/explanation-reacceptance-probes.log)

本次未修改业务实现。临时 probe 源码/class 在验证结束后移出正式测试目录，避免污染下一次默认测试数量。

## 3. 上次问题逐项复核

| 上次编号 | 本次确认的改进 | 本次状态 |
|---|---|---|
| A5-01 来源/交易自证 | 服务端来源适配、摘要计算、交易 ID 存在性和金额校验已加入 | 部分修复；空证据仍可解释，逐预警完整范围尚未冻结，另有空摘要落库冲突 |
| A5-02 NOT_SATISFIED | 逐题阻断 EXPLAINED | 主要修复路径通过 |
| A5-03 待分派事实 | OPEN 案件级关键事实进入最终 blocker | 主要修复路径通过 |
| A5-04 伪造降级确认人 | 提案与认证确认分开，核对角色、账户、提案/问题版本，禁止提案人自认 | 主要修复路径通过；真实角色 E2E 待补 |
| A5-05 依赖失效 | 提交写 evidence_use；显式失效及抓取内容改变可使依赖提交 STALE | 部分修复；核验结果变成 UNRESOLVED 未闭合 |
| A5-06 交期与义务 | 最终校验增加 Clock 交期重评 | 部分修复；未到期但无持续任务仍可通过 |
| A5-07 接续 | 原任务归属检查、只取消被引用原任务、完成标准保存、变更前校验 token | 部分修复；页面补充尽调发生 token 回归，逐义务覆盖/复核录入仍待完成 |
| A5-08 EDD 完成 | 状态+版本条件更新，阻止 respondedBy 自审，保存完成者 | 主要修复路径通过；真实竞争更新待补 |
| A5-09 草稿串稿 | 按单元隔离并返回服务端草稿，新增 3 项组件测试 | 原跨单元串稿/恢复路径通过；不等于所有页面冲突交互已验收 |

实施记录已明确未交付 Claim 写入链、来源家族、AlertScopePort、复核预检和接续录入，这些继续作为未交付项管理。关键前提未实现时必须阻断相关最终决定，不能以“首批”范围替代业务门禁。

## 4. 本轮仍需修复的具体问题

### A6-01 [P1] 空材料和零核验事件仍能形成最终排除依据

位置：[ExplanationWorkspaceService.java:1600](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1600)、[同文件:2070](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:2070)。

六问题只是遍历 artifactVersionIds；空数组不触发校验，EXPLAINED 分支也没有补充证据必要条件。persistEvidenceUses 在完全无引用时写一条 CONTEXT 占位记录，其注释声称“无引用的 EXPLAINED 已被拒绝”，与实际行为不符。

复现 `probeEmptyEvidenceStillPassesFinalExclusion`：保留合法服务器交易，删除 Q1～Q6 全部材料引用，没有核验事件，提交和最终排除 Service 校验均成功。

修复不能仅要求任意一个材料 ID：应从关键 Claim 派生其需要的有效材料版本、事实位置和核验方式，未满足条件时不得形成可采用解释。手工判断与来源已核验必须分开。

### A6-02 [P1] 代付授权可整体省略，也可用覆盖子集隐去未授权金额

位置：[ExplanationWorkspaceService.java:1341](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1341)、[同文件:1443](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1443)。

authority 对象、limitAmount 和 coveredTransactionIds 都是可选条件；只比较客户端声明的覆盖集合金额，没有要求它覆盖待解释的代付交易。C3/C4 的 SUPPORTED 声明不能补足缺失授权。

两个复现均能提交集团代付 EXPLAINED 并通过最终排除校验：

1. `probeAbsentAuthorityStillPassesGroupPaymentExclusion`：删除整个 authority。
2. `probeAuthoritySubsetIgnoresUncoveredPayment`：待解释收款 320,000.00 + 120,000.00；授权限额 320,000.00，仅把第一笔列入 coveredTransactionIds，第二笔 120,000.00 未被覆盖却没有形成阻断。

修复：服务端派生需要解释的代付资金腿，要求授权身份、订单/债务、期间与覆盖完整；逐笔比对实际覆盖及额度。额度累加和共享预警引用采用去重分配规则，不把“同一交易命中两个预警”本身当成重复用款。

### A6-03 [P1] 无任何持续任务的预付解释仍能通过最终门禁

位置：[ExplanationWorkspaceService.java:1078](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1078)、[同文件:1244](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1244)。

最终校验仍无条件传 `continuationArranged=true`；没有查询并校验每项 followupRequired 对应的真实任务。注释引用 validateObligationCoverage，但当前类并无该实现。

复现 `probePrepayWithoutContinuingTaskStillPassesFinalExclusion`：交期未到，提交已标 followupRequired=true，OPEN EDD 任务集合为空，最终排除校验仍通过。Clock 逾期修复不能替代未到期义务分派。

修复：从已采用单元提取义务，逐项匹配任务、负责人、期限与完成标准。能力值由实际覆盖计算，禁止通过“处于最终事务”推定任务已经安排。

### A6-04 [P1] 修复 token 顺序时使页面补充尽调路径回归

位置：[ReviewService.java:107](/D:/JCode/backend/src/main/java/com/bank/aml/review/ReviewService.java:107)、[ReviewView.vue:224](/D:/JCode/frontend/src/views/ReviewView.vue:224)。

后端现在对所有契约 2 的决定校验 token；前端对 REQUEST_ENHANCED_DUE_DILIGENCE 明确跳过取号，发送空 token。因此复核页发起补充尽调会先得到依据冲突，而不是创建任务。

复现 `probeUiEddRequestWithoutTokenIsRejectedBeforeCreatingTask`：使用真实 ReviewService 和真实 ExplanationWorkspaceService，按页面协议提交补件请求，在 EDD Service 被调用前抛出令牌冲突。该测试确认服务协议冲突，未宣称实际打开浏览器点击。

修复选择应统一：建议仅最终确认/排除强制最终依据 token，补件请求继续使用案件复核版本保护；若决定所有动作都要 token，则前端、控制器契约和测试一起更改，且不能要求调查已经满足最终决定条件才能请求补件。

### A6-05 [P1] 核验从 CONFIRMED 变为 UNRESOLVED 后旧解释仍可采用

位置：[ExplanationWorkspaceService.java:379](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:379)。

recordVerification 追加记录并推进 epoch，但只有 MISMATCH 产生完整性问题，UNRESOLVED 不使相关提交失效、不产生必须处理的问题；最终 readiness 也不重新检查采用核验的当前有效性。

复现 `probeReverificationUnresolvedDoesNotInvalidateAdoptedSubmission`：已有 CONFIRMED 核验和 EXPLAINED 提交，追加无法继续确认用途的 UNRESOLVED 结果，Repository 返回的事件列表已包含新记录。旧提交仍 CURRENT，重新取得 token 后最终排除校验通过。

修复：使用关系绑定具体核验事件和被支持 Claim；核验更正、撤销、失去支持与材料内容变更统一进入依赖失效流程。刷新 token 只能更新版本，不能把事实缺口消掉。

### A6-06 [P1] 来源不可用写入 null 摘要，与 JPA/数据库非空约束冲突

位置：[ExplanationWorkspaceService.java:340](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:340)、[EvidenceArtifactVersion.java:44](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/EvidenceArtifactVersion.java:44)、[V27 迁移:38](/D:/JCode/backend/src/main/resources/db/migration/V27__rapid_goods_explanation.sql:38)。

首次获取 NOT_FOUND/UNAVAILABLE/FORBIDDEN 或缺适配器时，Service 设置 contentSha256=null 后保存；实体仍 `nullable=false`，表列仍 `CHAR(64) NOT NULL`。V28～V30 未修改该约束。因此按当前映射/表结构持久化会被拒绝，不能得到预期的“记录来源状态并生成问题”。Mockito 的 save 返回原对象掩盖了此错误。

这是静态确定的持久化契约冲突，具体由 Hibernate 还是数据库首先抛错须在隔离 MySQL 上验证。

修复：使用新增迁移和实体映射允许“未取得内容”记录无摘要，并约束 RESOLVED 必须有实际摘要；或拆分抓取尝试与内容版本。不得填全零/伪造摘要凑过非空约束，不修改已应用的 V27。

## 5. 下一步验收应该改变什么

当前测试擅长验证“字段明确填写为坏值时拒绝”，对“关键字段被省略、只填部分集合、事实变化后重取 token、跨页面调用协议、真实持久化约束”的覆盖不足。下一轮应把删除字段、缩小范围、改变核验状态和真实事务写入作为必测输入变化。

下一步不另开更大业务范围，先完成来源—授权—决定的一条真实路径，并通过隔离数据库验证。具体工作顺序、交付物、预计人日和放行条件见 [闭环补齐方案](/D:/JCode/docs/plans/explanation-v3-closure-plan-2026-09-08.md)。
