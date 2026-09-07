# 当前修改复审与业务迭代选题

日期：2026-09-07；工作区：`D:\JCode`；HEAD：`3ab1c08`，当前仍有多轮未提交修改。本报告与当天较早的[修改成果验收](remediation-acceptance-2026-09-07.md)属于不同工作区版本。

## 1. 本轮结论

**上轮 A4-01 已在当前代码和组件测试层关闭；真实集成验收仍未完成。业务下一轮建议聚焦“企业货款快进快出的合理解释核验”。**

当前系统已经能完成预警归并、假设判断、证据登记、逐预警覆盖、EDD、人工复核和报送登记。值得继续投入的地方，是让复核员有依据判断一项业务解释是否站得住脚：材料是否可解析、说明适用于哪些交易、反向信息是否处理、最终决定基于哪一版事实。

本轮新定位的业务薄弱点不是 A4-01 修复引入的回归，也不能据此断言演示系统违反某项监管规定。以下区分技术验收、现有业务行为与建议新增能力。

详细实施方案见：[企业货款快进快出——合理解释核验专项计划](../plans/rapid-movement-explanation-verification-plan-2026-09-07.md)。

## 2. 本轮技术复审

| 项目 | 结果 | 实际证据 |
|---|---|---|
| 假设写入成功、刷新失败 | 通过本轮代码与组件测试 | 成功提示在写入完成后发出；刷新单独捕获，置 `investigationStale`，阻止重复判断，提供显式重新加载 |
| 覆盖写入成功、刷新失败 | 通过本轮代码与组件测试 | 同样区分保存与刷新；再次点击不会重放写入；重载后解除限制 |
| 旧快照迁移夹具 | 静态改进确认 | 旧归档已移入 V25 阶段，升级后检查 NULL 摘要、原 source digest、payload 和定位信息；不再仅在 V26 后插入“旧形态”数据 |
| 前端 Vitest | 6 文件、39 项通过 | `npm.cmd test` |
| 类型检查和生产构建 | 成功 | `npm.cmd run build`；仍有依赖分块大小提示 |
| 后端默认测试 | 382 项：381 通过、1 跳过；0 失败、0 错误 | 本轮重新执行；跳过项为未配置实时模型环境的 `AgentEvalLiveTest` |
| MySQL 迁移、真实事务并发、角色 E2E | 未执行 | 默认 Maven 排除 integration；本轮 Docker 复查仍无法连接 `docker_engine` |

后端日志：`.tmp/business-deep-review-20260907-backend.log`。沿用离线 Maven 3.9.9、JDK 21、工作区测试临时目录和禁用 JUnit 自动清理的运行方式。

因此可以认可 A4-01 的修复，但仍不能宣布 A0 的真实数据库及全链路发布门槛已经满足。当前新增的事实过期限制主要保护假设与覆盖提交，不应宣传为整个页面所有操作均已获得统一过期状态控制。

## 3. 深入业务审查发现

### BR-01：材料“已登记”与“可作为事实使用”还没有区分

位置：`InvestigationService.addEvidence()` 第 105–132 行；`InvestigationReadinessEvaluator` 第 184–204 行。

调查证据登记校验类型、方向、引用字符格式和摘要长度，然后直接持久化。当前依赖中没有业务证据来源解析器，也没有按原文核对哈希、材料主体或适用期间的步骤。假设判断和最终复核主要检查必需类型是否存在，以及是否至少存在一项所需方向的证据。

**本轮服务层行为探针：** 在使用真实 `InvestigationService` 和真实 readiness evaluator、仅 mock 仓库的测试中，录入两个 `NO-SUCH-SOURCE-*` 格式合法但未解析的引用，分别声明为 TRANSACTION 与 CUSTOMER_PROFILE；随后排除假设、写入 EXPLAINED 覆盖，最终排除门禁没有抛出异常。

这证明当前门禁没有核验引用内容，不能证明真实 API 已自动结案，更不代表人工复核员一定会批准。业务影响是：系统显示材料类型齐全时，复核员仍需自行辨别引用是否实际存在、是否属于本客户及本次交易。

**迭代要求：** 区分登记、来源解析、内容一致性、人工作用判断；未解析材料可以作为待核验线索保存，但不能被自动计入合理排除所需的已核验依据。来源可查和哈希一致也不能单独证明商业交易真实。

### BR-02 [P2]：判断形成后新增相反证据，不会使原判断依据过期

位置：`InvestigationService.addEvidence()` 第 122–132 行；`InvestigationReadinessEvaluator` 第 197–204 行。

`addEvidence()` 允许向 PENDING/HOLD 案件中的已决假设追加材料，但没有推进假设判断版本、记录“证据集合已变化”，也没有使相关覆盖失效。readiness 只检查所需方向是否有至少一项，未要求对新出现的相反方向材料说明处理结果。

**本轮服务层行为探针：** 假设已 REJECTED，revision=3；覆盖 EXPLAINED，绑定假设版本 3；旧证据为 CONTRADICTS。再追加一项 SUPPORTS 新证据后，假设仍为 revision=3，覆盖保持 EXPLAINED，最终排除门禁仍然放行。

正确处理不是机械地“发现相反证据就认定客户可疑”。至少应标记本次判断依据已变化，要求分析员评估新材料并记录接受、驳回或仍待核验的理由；重要未知不能通过保留一项旧反证静默消失。

**短期修复建议：** 对已决假设的证据增补引入“需重新评估”标记或证据集合版本，在同一案件锁事务内写入并阻断基于旧依据的最终排除；不修改历史决定。专项方案进一步给出版本绑定、并发、已结案案件处理设计。

### BR-03：EDD 材料齐备与解释问题解决是两件事

位置：`EnhancedDueDiligenceService.normalizeEvidenceItems()` 第 256–300 行及 `applyReviewDecision()`。

EDD 已有可靠的分派、轮次、截止时间、材料项覆盖、来源枚举、哈希格式和审计流程。但它核对的是材料元数据清单；没有把材料明确绑定到“付款人与合同买方是否一致”等业务问题，也没有记录核验动作及差异处理。`SUBMITTED` 代表本轮材料已提交，不能解释为内容已核实；`RESOLVED` 代表流程已处置，不能推导每项业务问题均已解决。

这是下一轮产品能力缺口。建议复用 EDD 任务机制，增加问题绑定和逐项处置，避免另建一套任务系统。

### BR-04：目前还无法稳定做到逐笔交易与凭证对照

`CustomerTransactionEntity` 已有 `id` 和 `sourceUpdatedAt`，但 `RelationalCustomerDataPort` 映射成 `TransactionRecord` 时没有带入它们。领域交易记录也没有账户 ID、稳定对手主体 ID、源记录版本等定位字段。

`TransactionWindowService` 提供的是 `CustomerDataPort` 当前截止时点的汇总，而案件 Agent 已使用冻结快照。新核验页面如果直接使用这个汇总当作原案交易依据，会混淆不同截止时点。

这不等于现有“当前交易窗口”计算错误。它意味着专项功能应优先补全逐笔定位和核验范围，不能依靠日期、金额、同名对手拼接所谓唯一交易，也不能把最新客户视图静默混入旧案。

## 4. 候选方向与取舍

| 方向 | 能解决的具体问题 | 当前依赖与代价 | 建议 |
|---|---|---|---|
| 合理解释核验 | 对同样的快进快出，区分可核实经营活动、待补证解释、未解决疑点 | 可直接复用调查/EDD/复核；需补材料解析、逐笔关联和依据版本 | **本轮深入实施的首选** |
| 受益所有人差异核验 | 客户申报、权益路径与实际控制来源冲突时如何处理 | 现有股权模型缺关系两端和控制语义，需先补企业关系数据 | 后续单独选题 |
| 跨账户资金关系辅助调查 | 找到单客户视图难以发现的关联线索 | 需稳定账户/主体 ID、关系权限和合成网络基线 | 后续实验，不与本轮并行扩张范围 |

这些是基于当前代码与演示目标的选题判断，不是已测量的收益排名。合理解释核验的优势，是能通过同一笔资金流搭配不同材料，直接展示系统如何改变调查结论与后续动作。

## 5. 本轮探针与可信范围

- 探针源码：`.tmp/BusinessDepthProbeTest.java`；最终执行日志：`.tmp/business-depth-probe-execution-20260907.log`。
- 两项测试均为**现状行为验证**：通过表示 BR-01/BR-02 所述行为被复现，不表示对应业务控制已完备。
- 使用真实业务 service/evaluator 和 mock 仓库，不验证 SQL、事务回滚、HTTP 权限或真实数据源。
- 探针首次编译遇到本机 Maven 缓存 JAR 的 `ZipFileSystem.close` 访问限制；调整为 fork 编译后曾执行测试，发现探针夹具未跟随实际覆盖版本递增，已修正为读取当前 revision。随后 javac 已生成更新的 class，但退出关闭缓存 JAR 时仍报权限错误；通过 `-Dtest=BusinessDepthProbeTest surefire:test` 执行生成的 class，最终两项均通过。不能把这解释为探针完整 Maven 编译生命周期无环境错误。
- 探针源文件和生成的 class 均已移到 `.tmp`，没有留下正式测试源或可被下次默认测试误扫的 class。业务源码未修改。

## 6. 本次关键版本

| 文件 | SHA-256 |
|---|---|
| `frontend/src/views/CaseDetailView.vue` | `3BE49767CF2AFF9136E64436CDE4A0D5BCBFEC0DBC388AEC1DE0B3996CBECB49` |
| `frontend/src/views/CaseDetailView.spec.ts` | `65C924F8E183A877415C26110DFE1978749782DE7AF55B6CF61EB582C2809B97` |
| `backend/src/test/java/com/bank/aml/config/InvestigationContractMigrationTest.java` | `C9C1532474F06A0F01EAB1795325998C278FDC158C7BB79C72E876218BD61877` |
| `backend/src/main/java/com/bank/aml/investigation/InvestigationService.java` | `D61275ABAFFEE2EB0D1268D5BAB559B4030B7AE5BDAF4CEBAACFEA6ED02762DE` |
| `backend/src/main/java/com/bank/aml/investigation/InvestigationReadinessEvaluator.java` | `3FFD81A73A7B3B060211A6A05C955D693B75B218920AC9580BFBF744A7A7C1EA` |
