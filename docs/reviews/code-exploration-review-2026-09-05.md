# 代码探索与审查（2026-09-05）

## 范围与探索结果

以当前工作区为准（HEAD `3ab1c08`），包含已修改和未跟踪的新代码。本轮先探索架构，再重点审查最近扩展的预警分诊、调查契约、复核、补充尽调、报送及前端流程，抽查认证、审计、RAG 和可靠消息衔接。没有修改业务源码。本报告不是对全部模块的无缺陷保证。

- 后端：Java 21、Spring Boot 3.5.13、Spring Security、JPA、LangChain4j；MySQL 保存业务事实，Redis Streams + Outbox 驱动工作流，PostgreSQL/pgvector 提供法规检索。
- 前端：Vue 3、TypeScript、Element Plus、Vue Router，Axios 调用 API，SSE 更新自动尽调过程。
- 主链路：`CaseIntakeService` 创建或归并预警 → `WorkflowCommandService` 登记任务 → Worker 执行 `DueDiligenceService` → 冻结快照、Agent 和 Guardrails → 自动完成或人工复核 → 补充尽调 / 可疑报告登记 → 档案导出。
- 新调查链路：每个新案件启用 `investigationContractVersion=1`，关联调查假设、证据类型和逐预警覆盖结论；`ReviewService` 在最终人工处置前调用调查门禁。
- 关键审查边界：新调查契约与旧自动完成分支、归并后的模型输入、假设与覆盖结论的一致性、前端是否能进入后端允许的状态。

## 发现

### 1. [P1] 自动完成绕过新调查契约，并使未完成调查记录无法补齐

位置：`backend/src/main/java/com/bank/aml/service/DueDiligenceService.java:298`。

新建案件被写入调查契约版本 1，同时默认假设是 OPEN、覆盖结论是 PENDING。但正常 Agent 输出没有触发转人工时，自动流程仍直接写 DONE，未读取这些调查记录。最终调查门禁只在 `ReviewService.submit` 中执行。另一方面，`InvestigationService.lockEditableCase` 仅允许 PENDING/HOLD 更新调查记录，DONE 没有重新调查入口。

触发：新建版本 1 案件，尚未人工完成假设/覆盖，自动报告正常且 `mustEscalate=false`。结果是案件已经结束，但调查假设和预警覆盖永久停留在未完成状态；若此前在 PENDING 阶段已人工确认风险，自动流程也不会读取该判断。

建议：版本 1 案件在自动完成前统一检查调查契约；未完成时保留可调查状态，或将“自动分析完成”与“案件最终结案”区分。该项由完整调用链静态核对确认，未做依赖真实数据库和模型的端到端复现。

### 2. [P1] 归并/拆分把模型所需的预警命中原因替换成规则编号

位置：`backend/src/main/java/com/bank/aml/investigation/CaseIntakeService.java:225`。

`refreshCaseAlertSummary` 只取 `ruleCode` 并覆盖 `CaseEntity.alertRule`。随后 `DueDiligenceService` 仍只把这个字段交给快照工厂、法规关键词解析和 Agent 上下文，没有读取关联预警的 `hitReason`、场景或发生时间。原始预警记录虽保留在数据库中，却不再进入模型输入。拆分也调用同一方法，因此会出现相同问题。

最小复现已执行：将“客户连续发生夜间跨境转账”（RULE-001）与“客户通过拆分现金交易规避监测”（RULE-002）归并。得到 `alertRule=多预警归并：RULE-001、RULE-002`，法规关键词变为 `[尽职调查, 风险评估]`，跨境/夜间/拆分主题消失。

建议：把展示摘要与调查输入分开；冻结所有关联预警的命中原因、规则和场景，并用这些事实构造 Agent 输入与法规检索主题。

### 3. [P2] 假设改判后保留旧覆盖结论，最终复核仍接受矛盾证据链

位置：`backend/src/main/java/com/bank/aml/investigation/InvestigationService.java:151`。

`updateHypothesis` 允许在满足证据要求时从 CONFIRMED 改为 REJECTED，更新假设后没有重置引用它的覆盖结论。`blockers` 只检查案件中是否存在已确认假设、是否存在可疑覆盖，没有逐条检查覆盖结论与其关联假设是否一致。

最小复现已执行：案件有两个已确认假设和两条 SUSPICIOUS 覆盖；将第一个假设以反向交易证据改判 REJECTED，其覆盖仍是 SUSPICIOUS。第二个假设仍为 CONFIRMED，`validateReadyForReview(CONFIRM_SUSPICIOUS)` 不抛异常。因此可以把与其证据依据相矛盾的预警结论纳入最终报送。

建议：假设改判时同事务重置其关联覆盖为 PENDING 并递增版本；最终复核再逐条验证关联假设、状态和结论，防止陈旧判断被采用。

### 4. [P2] 前端强制立即处理，预警归并没有稳定可用的建案入口

位置：`frontend/src/api/client.ts:563`。

前端的普通建案、预警建案和拆分建案均固定发送 `autoProcess: true`，没有“建案后先归并”的选项。后端候选查询与归并只允许 PENDING 案件；Worker 一旦领取任务，状态进入 RUNNING 后便拒绝归并。正常队列运行时，用户完成首次建案再去分诊下一条预警，通常已经没有可归并候选。拆分入口也要求 PENDING，同样只能依赖短暂竞态窗口。

建议：提供先建案/归并、再显式开始调查的流程，允许前端传入 `autoProcess: false`；完成预警分诊后再触发已有处理接口。

## 验证与限制

- 前端：`npm test`，4 个测试文件、15 项测试通过；`npm run build` 的类型检查和生产构建通过。构建有 chunk 大小提示，不作为本轮缺陷。
- 后端：使用本机现有 JDK/Maven 和缓存依赖离线执行默认单元测试，共 347 项，首次 336 项通过、1 项跳过、10 项错误；10 项错误均为 JUnit 清理临时目录时的 `AccessDeniedException`，没有断言失败。
- 对这 10 项单独复测，使用工作区临时目录并设置 `junit.jupiter.tempdir.cleanup.mode.default=NEVER`，10 项全部通过。因此分批验证结果合计为 346 项通过、1 项跳过；没有声称默认命令在当前沙箱中完整通过。
- 两个业务缺陷通过临时 Java/Mockito 探针调用实际服务实现复现（数据访问层使用模拟对象）：归并丢失调查输入、假设改判后旧覆盖仍通过门禁。
- 本轮未执行需要 MySQL/PostgreSQL/Redis 的集成测试、Playwright 端到端测试或真实模型评测。静态识别的问题不等同已完成线上复现。

现有测试主要验证局部校验与单个服务行为，未覆盖上述跨流程场景。建议修复时优先补充对应状态流转和前后端流程测试。
