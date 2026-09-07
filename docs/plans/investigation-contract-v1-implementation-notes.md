# 调查契约 v1 修复实施说明（代码审查 2026-09-05）

对应《代码审查问题修改计划 v1》。本文记录实际落地的修改、验证结果与未执行项，供审查与部署参考。

> **返工记录（2026-09-05 第二轮，依据验收报告）**：A1 存量覆盖重新确认入口、A2 容量前置校验与 FAILED 恢复、
> A3 影响查询分版本，见“返工与验收问题修复”一节。
>
> **W1/W2 记录（2026-09-05 第三轮，依据《下一阶段修复与迭代详细计划 v2》与第二轮复审 R1/R2）**：
> 调查版本冲突统一协议、页面冲突草稿恢复状态机、V26 影响查询 R2 修复与迁移/查询实跑测试，见“W1/W2 实施记录”一节。
> 阶段 0 基线（代码定位 + 实测结果）见 `w1-w2-baseline-2026-09-05.md`。
>
> **A0 记录（2026-09-05 第四轮，依据 v3 计划与第三轮验收 A3-01/A3-02）**：
> 迁移验收测试修正、假设判断页面级草稿恢复，见“A0：v3 计划必修问题修复”一节。
>
> **A4-01 记录（2026-09-07 第五轮，依据 2026-09-07 验收报告）**：写入成功后刷新失败的
> 如实提示与事实过期限制、归档迁移夹具前置，见“A4-01 修复记录”一节。

## 1. 修改清单（按工作包）

### 工作包 A：统一调查就绪检查与覆盖失效处理（问题 3）

- 新增 `InvestigationReadinessEvaluator`（`investigation/`）：无副作用的就绪判断组件。
  对每条 LINKED 预警逐条检查：覆盖存在且属于当前案件、覆盖关联假设存在、结论与假设状态一致
  （SUSPICIOUS↔CONFIRMED / EXPLAINED↔REJECTED）、覆盖绑定的 `hypothesisRevision` 等于当前假设版本
  （NULL 视为存量无绑定，要求重新确认）、最终假设仍满足必需证据类型与证据方向。
  输出 `readyForFinalReview` + 通用阻断 + 两种最终决定各自的阻断。
- `alert_investigation_coverage` 新增 `hypothesis_revision` 列（V26 迁移，可空，不回填）；
  覆盖视图返回该字段；更新覆盖的请求新增必填 `expectedHypothesisRevision`，成功时由服务端
  写入锁定的当前假设版本。版本冲突抛 `InvestigationRevisionConflictException` → 409；
  缺字段返回 400。既有的覆盖乐观锁冲突继续走 412 语义（`IllegalStateException`）。
- `updateHypothesis` 改判语义：与既有请求完全相同（状态+依据均未变）时幂等返回、不递增版本；
  实际修改时递增假设版本，并在同一案件行锁事务内把引用该假设的所有覆盖重置为 PENDING、
  清除版本绑定并递增覆盖 revision（原分析文字保留，前端标注已失效）；
  审计 Outbox 记录前后状态/版本与受影响覆盖编号/数量，登记失败时全部回滚。
- `CaseInvestigationView` 增加 `readyForFinalReview` 与 `generalBlockers`，保留两种决定的 blockers。
- 前端：详情页展示通用阻断与 HOLD 阶段提示；已决假设提供"修改判断"入口（预填原依据、
  明示覆盖将失效）；覆盖更新遇 409 刷新状态并预填原输入供人工核对，不自动重放。

### 工作包 B：自动分析完成与最终结案分离（问题 1）

- `DueDiligenceService`：`investigationContractVersion >= 1` 的案件，自动分析结束后一律进入
  HOLD（调查契约要求人工最终处置），不写 DONE；该策略原因单独写工作流日志，
  不伪装成 `AGENT_INVALID_HOLD`。风险评级仍由模型/Guardrail 决定，低风险进 HOLD 不升高风险。
- `FinalDecisionAssembler.assemble(..., forceManualReview = 模型违规 || 契约策略)`：保证
  状态、`manualReviewRequired`、MANUAL_REVIEW 动作码与正文一致。
- `CaseOperationsService`：HOLD 案件按就绪分流 —— 有 OPEN EDD 归承办分析员（显式截止时间）、
  v1 调查未就绪归 INVESTIGATION/ANALYST（待办展示前 3 条阻断项）、就绪归 REVIEW/REVIEWER、
  REPORT_PENDING 归 REPORTING/REVIEWER。复核阶段起点取"自动分析完成、有效假设/覆盖最后完成、
  最近 EDD 回传/撤销"的最晚时间（全部为可持久化事实，查询不漂移）；队列批量预加载就绪结论，
  不逐条预警查库。
- 前端：HOLD 详情展示阶段提示；SSE 终态后同步刷新案件、调查就绪与运营待办；
  DONE/REPORT_PENDING 案件的调查面板只读（编辑入口隐藏）。

### 工作包 C：把关联预警冻结进模型输入（问题 2）

- 新增 `InvestigationAlertSnapshot`（不可变预警快照 DTO）与 `AlertSnapshotAssembler`：
  - 生产入口 `fromLinkedAlerts`：Worker 抢占案件后冻结全部 LINKED 预警，
    按 occurredAt+alertId 稳定排序；超出 `aml.agent.max-linked-alerts`（默认 8）明确拒绝，不截断。
  - 兼容入口 `fromAlertRuleText`：仅版本 0 / 评测夹具使用，生成显式标记的 LEGACY 伪预警。
- `InvestigationSnapshot` 新增 `alerts` / `alertsDigest` / `snapshotSchemaVersion`
  （0=旧 schema，1=含预警事实）；保留 14 参旧构造（旧归档与评测夹具继续可读）。
  `sourceDigest` 口径不变，新增 `alertsDigest` 证明预警输入一致性（规范化 JSON 后 SHA-256）。
- `LegalKeywordResolver.resolve(alerts, riskFacts)`：逐条命中原因提取特征词 +
  scenarioCode 确定性主题映射，与风险事实强制主题合并去重。
- `DueDiligenceContext`：逐条渲染预警（编号/规则/场景/时间/命中原因），标注不可信；
  `PromptInjectionGuard` 扫描扩展到全部命中原因。
- `SnapshotArchiveService`：归档写入 `alertsDigest`；相同执行版本已有归档时校验
  业务事实/预警/法规索引一致，不一致显式失败（不再静默保留另一份内容）。
- 档案：`SnapshotMetadata` 增加 `alertsDigest` 与 `frozenAlerts`（从加密载荷提取本次执行
  实际冻结的预警编号+版本），档案 schema 版本升为 1.6；实时关联预警继续单独展示。
- v1 案件无有效关联预警时，工作流返回明确数据错误（`NonRetryableWorkflowException`）。

### 工作包 D：先建案、归并/拆分，再显式开始调查（问题 4）

- 前端 `createCase` / `createCaseFromAlert` / `splitAlertToNewCase` 全部要求显式
  `CaseStartOptions { autoProcess }`（内部调用点无一遗漏）。
- 分诊队列"新建案件"默认暂不启动（可继续归并），另设"建案并调查"；手工建案主按钮
  "创建案件"（保持 PENDING）、次按钮"创建并尽调"；拆分默认暂不启动。
- 详情页 PENDING 案件显示"开始调查"（确认框提示关联预警数与"开始后不能归并/拆分"）；
  服务端仍依靠 Outbox 幂等键与 Worker 抢占防重复执行；PENDING 文案不区分"未入队/已入队"。
- 后端 `trigger` 改为短事务内 `findByIdForUpdate` 锁定并复查状态，防止读取-入队竞态。

## 2. 数据库迁移

`V26__coverage_hypothesis_revision.sql`（增量，不重写 V25）：

- `alert_investigation_coverage.hypothesis_revision BIGINT NULL`；
- `investigation_snapshot.alerts_digest VARCHAR(64) NULL`；
- `idx_coverage_case_hypothesis (case_id, hypothesis_id)` 索引。

NULL 语义：存量覆盖表示"没有可证明的版本绑定"，门禁要求重新确认；不批量回填。
已结案历史与原档案保持原样。

## 3. 存量影响清单

- `docs/ops/investigation-impact-queries-v25.sql`：**V26 迁移前**可执行（schema 前提 = V25）。
  仅引用 V25 已存在列；含容量超限活跃案件清单（A2 存量形态）。
- `docs/ops/investigation-impact-queries-v26.sql`：**V26 迁移后**执行（schema 前提 = V26）。
  覆盖版本绑定（NULL/过期/矛盾）、未完成调查清单、已 DONE 无人工决定清单、归档预警摘要完整性。

两个阶段均只含 SELECT；执行阶段已在文件头标明，不能跨阶段混用。

## 4. 验证结果（按测试层级区分，仅记录实际执行过的项）

| 层级 | 内容 | 结果 |
|---|---|---|
| 接口参数单元测试 | `mvnw test` 默认 profile：**382 总计 / 381 通过 / 0 失败 / 1 跳过**（A4-01 后实测；跳过为真实模型评测 `AgentEvalLiveTest`，未设置 `RUN_LIVE_AGENT_EVAL`） | 实际执行，BUILD SUCCESS |
| 页面交互测试 | `CaseDetailView.spec.ts`（真实 SFC + el-table stub + mock API）：4 项入口断言 + W1 冲突状态机 6 项（V2-04~07） + A3-02 假设草稿恢复 2 项 + A4-01 写入成功/刷新失败 2 项（假设与覆盖路径：写入仅一次、提示如实、重新加载后解除限制） | 实际执行，前端共 39 项测试通过（6 个文件） |
| 接口参数契约 | `client.spec.ts`：建案/拆分显式传 autoProcess、覆盖更新携带 expectedHypothesisRevision | 实际执行 |
| 类型检查与构建 | `npm run build`（vue-tsc + vite build） | 实际执行，通过 |
| 数据库事务测试 | V2-12~17（需 MySQL/Redis/pgvector 容器）；Docker CLI 存在但守护进程不可连接 | **未执行**（W3 待验收） |
| 迁移与影响查询实跑 | V2-08~11 的执行代码与数据夹具已交付（`InvestigationContractMigrationTest`，integration 标签）；守护进程不可连接，本轮未执行 | **代码完成、验收待执行** |
| 完整端到端测试 | V2-18~23（Playwright 完整角色流程） | **未执行**（W3 待验收） |
| 真实模型评测 | 按计划不作为确定性修复前提 | 未执行 |

测试 JVM 使用工作区临时目录并显式设置 `junit.jupiter.tempdir.cleanup.mode.default=NEVER`
规避沙箱临时目录清理问题；未修改任何测试断言，未排除任何业务测试。

## 5. 返工与验收问题修复（第二轮，依据验收报告）

### A1 [P1] 存量覆盖重新确认入口

- 新增 `frontend/src/utils/investigation.ts`：`coverageGap` 把覆盖状态归类为
  PENDING / MISSING_BINDING（V26 迁移留下的 NULL 绑定）/ STALE_BINDING（改判后过期）/ INCONSISTENT。
- `CaseDetailView`：操作列对 MISSING_BINDING / STALE_BINDING / INCONSISTENT 显示“重新确认”按钮；
  对话框展示当前假设依据、预填原分析，由用户明确提交当前版本；结论由服务端锁定的当前假设状态
  推导，**不要求反转调查判断**；409 时刷新并保留输入。存量已决假设增加“重申判断”
  （保持原结论仅重新确认依据；与原依据完全相同时后端幂等返回不递增版本）。
- 后端无需变更：`updateCoverage` 已支持在不修改假设状态的前提下补齐版本绑定
  （锁定当前假设、校验状态一致后写入服务端版本）。
- 验证：`utils/investigation.spec.ts`（6 项判定逻辑）+ `CaseDetailView.spec.ts`
  （挂载真实 SFC：NULL 绑定已决覆盖出现“重新确认”、无“形成结论”；PENDING 保留原入口；
  DONE 只读）。
- **更正**：当时描述的“409 时刷新并保留输入”经复审（R1）证实不成立
  （正常改判返回 412 且不刷新；重新确认分支也会丢草稿）；该问题已在下节 W1 修复，
  并补充了正式交互回归。

### A2 [P2] 容量前置校验与失败恢复

- `CaseIntakeService.linkToCase`：锁顺序统一为“案件 → 预警”（与拆分/覆盖更新一致），
  在案件行锁事务内前置校验容量：`LINKED 数 + 1 > aml.agent.max-linked-alerts` 时拒绝归并，
  案件保持可拆分的 PENDING 状态；同时保证已入队 PENDING 案件在等待 Worker 期间
  不被并发归并至超限（校验在锁内、归并事务串行化）。
- `DueDiligenceService.trigger`：启动前在短事务内校验容量，超限抛出前置条件错误，
  案件保持 PENDING，不进入执行后才失败。
- 受控恢复：`splitToNewCase` 允许 FAILED 案件拆分，条件为：无 reportJson/rawReportJson
  （没有任何调查产出）、契约版本 ≥ 1，且既有 `validateCanSplit` 继续兗底
  （无证据、无已决假设、无已决覆盖）。拆分后原案件保持 FAILED，由用户显式人工重试。
- Worker 快照装配的容量防线保留（防御纵深）。
- 验证：归并超限拒绝且不改状态/不入队（`CaseIntakeServiceTest`）；边界值 8 条允许；
  FAILED 容量案件拆分成功且不自动入队；有调查产出的 FAILED 拒绝拆分；
  启动超限拒绝且不触发入队（`DueDiligenceServiceTest`）；
  前端 FAILED 案件保留“拆分”按钮（`CaseDetailView.spec.ts`）。

### A3 [P2] 影响查询分版本

- 原 `investigation-contract-impact-queries.sql` 拆分为两个文件并删除旧文件：
  - `investigation-impact-queries-v25.sql`（迁移前）：修正 `reported_at_alias` →
    实际列名 `reviewed_at`；仅引用 V25 已存在列；新增容量超限活跃案件查询
    （派生表避免无 GROUP BY 的 HAVING 语义错误）。
  - `investigation-impact-queries-v26.sql`（迁移后）：NULL/过期版本绑定、矛盾结论、
    未完成调查清单、DONE 无人工决定清单、归档预警摘要完整性。
- 两个文件头部标明 schema 前提，不能跨阶段混用。列名已逐一对照 V19/V22/V23/V25 迁移与实体定义。
- 验证限制：本机无可用数据库守护进程，未实跑；已完成静态列名核对，实跑验证仍需在
  隔离 MySQL 上分别迁移到 V25/V26 后执行（见下节未执行项）。

## 6. W1/W2 实施记录（第三轮，依据 v2 计划与复审 R1/R2）

### W1：统一调查版本冲突协议与草稿恢复（R1 修复）

后端（409 + 稳定错误码 + 有界冲突对象）：

- `InvestigationRevisionConflictException` 增加 `conflictType`（HYPOTHESIS/COVERAGE）、
  `conflictId`（假设 ID 或预警 ID）、`currentVersion`（服务端当前版本）。
- 三个版本校验点统一抛调查版本冲突异常：修改假设的 `expectedRevision`
  （原为普通状态异常→412）、覆盖自身 `expectedRevision`（原 412）、覆盖引用的
  `expectedHypothesisRevision`（原已是 409）。统一返回 409 +
  `INVESTIGATION_REVISION_CONFLICT`，并在响应体附 `conflict{type,id,currentVersion}`。
- 业务前置条件语义不变：案件不可编辑、证据不足、结论与状态不一致仍是 412
  （`IllegalStateException`）；缺版本参数仍是 400。未把任何 `IllegalStateException` 全局改 409。
- 版本校验仍在锁定案件、读取受保护对象之后；冲突时不写覆盖、不新增成功审计事件
  （V2-01 测试断言）。

前端（统一编辑状态机）：

- “形成结论”与“重新确认”收敛为同一入口 `editAlertCoverage(alertId, mode)`：
  提交前重新解析当前事实（只读/预警已拆走/假设不存在或未决 → 停止并说明原因，
  不再用 `0` 兑底对象 ID/版本，不把未知状态默认当 EXPLAINED）。
- 草稿按预警隔离存于页面内存 `coverageDrafts`，发请求前先写入；仅保存成功或用户
  明确放弃时清理。
- 冲突恢复：仅 409 + `INVESTIGATION_REVISION_CONFLICT` 协议判定为冲突
  （`isInvestigationRevisionConflict`，任意 409/412 不自动刷新重试）；刷新失败时保留草稿
  并提示稍后重试；刷新成功后展示最新假设状态/依据/将提交的结论
  （含冲突类型文案），用户明确确认后才以刷新后版本重新提交；连续冲突可重复该循环。
- 假设确认/修改/重申沿用同一冲突恢复原则；成功提示区分幂等重申
  （返回版本 == 提交版本 → “未递增版本，覆盖结论未失效”）与实际改判。
- 后端与前端同批交付，前端不解析中文异常文案，只依赖错误码与冲突对象字段。

验证（V2-01~07，全部实际执行通过）：

- V2-01/02/03（`InvestigationServiceTest`）：先真实调用改判（乙）再提交旧覆盖（甲）→
  冲突类型 COVERAGE、旧提交无写入、不新增覆盖审计；仅覆盖并发更新 → 同协议且不误报
  “假设已改判”；旧假设版本/缺版本/缺必需证据分别对应 409 冲突（HYPOTHESIS）、400、412。
  协议映射测试（`GlobalExceptionHandlerTest`）：409 + 稳定码 + conflict 对象；412 语义不变。
- V2-04~07（`CaseDetailView.spec.ts`）：真实点击按钮 + 受控 Promise 顺序；
  冲突后草稿逐字保留、第二次请求使用刷新后版本（rev 2/假设 rev 4）；
  重新确认路径保留新编辑而非数据库旧分析；连续两次冲突无未处理异常；
  刷新失败保留草稿且重新打开仍预填；刷新后假设未决则停止提交；
  EXPLAINED/REJECTED 存量覆盖推导合理解释结论并绑定当前版本。

### W2：影响 SQL 修复与迁移/查询实跑验收（R2 修复 + 发布门槛）

- `investigation-impact-queries-v26.sql` 查询 2 补充 `LEFT JOIN aml_alert a ON a.id = cov.alert_id`
  （R2 修复点），并全量复核两份 SQL 的别名/列/筛选语义；查询 1 增加案件状态列与
  JOIN aml_case，使 NULL 绑定清单可区分可编辑/只读案件（终态不引导到页面重新确认）。
- `investigation-impact-queries-v25.sql` 容量清单注明：8 为默认值，运行前必须替换为
  部署实际 `aml.agent.max-linked-alerts`，并说明覆盖 FAILED 状态的用法。
- 新增 `InvestigationContractMigrationTest`（integration 标签，V2-08~11）：
  隔离 schema 迁移至 V25 → 插入合成夹具（正常/异常终态、v0 兼容、EDD、矛盾、报送、
  容量 8/9 边界、FAILED 超限）→ 逐条执行 V25 全部查询并断言命中/不命中 →
  应用 V26 → 断言存量绑定保持 NULL、原状态/结论/人工决定事实未被改写、新列/索引存在 →
  插入新鲜/过期/矛盾绑定 → 逐条执行 V26 全部查询（过期绑定查询必须返回预设外部预警编号）→
  只读重跑结果一致。SQL 直接从仓库 `docs/ops` 读取（测试对象即交付物），任一语句报错即失败。
- **执行状态：代码与夹具完成；Docker 守护进程不可连接，V2-08~11 本轮未实跑**，
  按计划 3.3 标记为“代码修复完成、验收待执行”；可在任意隔离 MySQL 上运行
  `mvnw test -Dgroups=integration` 完成（默认 HOST localhost:3307，可用 MYSQL_TEST_* 环境变量指向测试实例）。
- 本次未新增 schema 变更，未修改已部署 V25/V26 的校验和。

## 7. 未执行项与原因（W3 发布门槛，未验收不得写通过）

1. **真实事务/并发/回滚（V2-12~17）与 Playwright 完整角色流程（V2-18~23）**：需要
   MySQL/Redis/pgvector 依赖环境（本机守护进程不可连接，CI/专属环境待接入）；
   W1/W2 的单元与组件测试不能替代这些证据。
2. **V25→V26 迁移实跑与两份影响查询实跑（V2-08~11）**：执行代码已交付（见上节），
   待隔离 MySQL 环境后运行 `mvnw test -Dgroups=integration` 即可验收。
3. **真实模型质量评测**：按计划不作为确定性修复的前提；未执行。

## 8. A0：v3 计划必修问题修复（第四轮，依据验收 A3-01/A3-02）

### A3-01 [P2] 迁移验收测试修正

按验收报告逐项修正 `InvestigationContractMigrationTest`：

1. **V25 阶段不再引用 V26 列**：拆分插入路径 `insertCoverageV25`（列清单只含 V25 已存在列），
   迁移前所有覆盖的 `hypothesis_revision` 为 NULL。
2. **外键完整性**：已报送报告夹具先创建 `manual_review`，以实际生成 ID 引用
   （满足 V23 `fk_str_review`），不再写死 review_id=1。
3. **NULL 与 0 区分**：`queryLong`/`queryString` 均检查 `wasNull()`；
   `hypothesis_revision IS NULL` 断言返回 null 而非 0。
4. **按列名取值**：过期绑定查询改用列名 `external_alert_id` 读取（该列不在第 1 列）。
5. **健康对照的绑定策略与“不回填”一致**：迁移前 consistent 夹具保持 NULL；
   迁移后通过显式 UPDATE 绑定（模拟分析员重新确认），并断言存量 NULL 绑定不被批量回填。
   V26 夹具的新鲜/过期绑定同样由显式操作建立（模拟正常提交与历史过期）。
6. **全部 SELECT 均执行**：断言 V25=5 条、V26=6 条；新增归档快照夹具（存量无摘要 + 新归档带摘要），
   V26 第 6 条（归档摘要清单）有明确断言，且存量归档摘要保持 NULL 不被迁移伪造。

**执行状态：代码修正完成并编译通过；Docker 守护进程仍不可连接，V2-08~11 实跑仍未执行**，
可在任意隔离 MySQL 运行 `mvnw test -Dgroups=integration`（默认 localhost:3307，
`MYSQL_TEST_*`/`IMPACT_SQL_DIR` 可覆盖）。

### A3-02 [P2] 假设判断草稿页面级恢复

- 新增按假设隔离的页面级草稿 `hypothesisDrafts`（记录目标结论与依据；页面即案件实例，
  键为假设 ID）：发请求前先写入；仅保存成功或用户明确取消时清理；
  冲突刷新失败、普通网络错误、假设被重置为未决等一切失败分支均保留草稿，
  且错误提示明确“已保留”。
- 重新打开时预填草稿依据（并提示已恢复与目标结论），不再退回数据库旧依据；
  与覆盖编辑同一状态机模式。
- 正式交互测试（替换临时探针为期望行为）：
  - BA-01 主路径：冲突 → 刷新失败 → 错误提示且无成功提示；重开草稿逐字恢复；
    第二轮冲突 → 刷新成功 → 用户确认后以 rev 2 提交成功。
  - 成功后草稿清理：重开预填数据库当前依据（非残留草稿）；取消后草稿清理。

前端 37 项测试全部通过；构建/类型检查通过；后端 382 总计/381 通过/1 跳过。

### A0 3.2 集成门槛：未执行（环境阻断，不得写通过）

迁移实跑、改判/复核并发、归并/Worker 抢占、Audit Outbox 回滚、旧 Worker 终态保护与
ANALYST/REVIEWER 完整流程（V2-12~23 复用 v2 计划 W3 场景）：需要独立 MySQL/Redis/pgvector 实例；
Docker CLI 存在但守护进程不可连接（`dockerDesktopLinuxEngine` 管道不存在）。集成代码与夹具已就绪，
环境恢复后即可执行；本轮不宣称集成验收完成。

## 9. A4-01 修复记录（2026-09-07 第五轮，依据验收报告）

### 缺陷与修复

**写入成功后的刷新失败被误报为提交失败**：假设与覆盖两条路径均存在“写入成功 → 删除草稿
→ 刷新失败 → 落入提交 catch”的结构，导致误报“更新失败、草稿已保留”并显示旧事实。
修复（两条路径同步）：

- **写入与刷新分离处理**：写入明确成功后即删除草稿并给出真实保存结果
  （幂等确认 / 版本递增 / 确认 / 排除 / 覆盖已保存）；随后的刷新失败单独捕获，
  不再进入提交失败/冲突恢复流程。
- **如实提示**：刷新失败提示“已保存，但最新调查事实加载失败”，不声称更新失败、
  不承诺已删除的草稿仍存在；不自动重放已成功的写入。
- **事实过期限制**：新增 `investigationStale` 标记（仅由“写入成功 + 刷新失败”置位，
  重新加载成功即清除）。置位期间调查面板展示警告横幅与“重新加载调查事实”按钮；
  假设编辑入口与覆盖编辑上下文解析均被阻断（旧版本/旧依据不能再次提交）；
  重新加载入口只读不写，失败保持限制并提示。

### 正式测试（全部实际执行通过）

- 假设路径：写入成功（rev 2→3）+ 刷新失败 → `updateInvestigationHypothesis` 仅一次、
  成功提示 + “已保存”警示、无失败错误提示、横幅出现；再次点击被阻断且写入次数不变；
  点击“重新加载调查事实”成功后横幅消失。
- 覆盖路径：同一断言形态（写入仅一次、提示如实、重开被阻断不弹框、重载后解除）。
- 既有用例（普通失败、409 冲突、明确取消、成功清理）继续通过；未用“草稿删除后移”
  掩盖问题。

### 归档迁移夹具完善（验收第 5.2 条）

- 存量归档夹具移至 **V25 迁移前**阶段：INSERT 列清单只含 V25 已存在列（无
  `alerts_digest`），与生产写入路径一致；V26 迁移后逐项断言主键、`source_digest`、
  `payload_ciphertext` 原貌保留且新摘要列为 NULL（不得被迁移伪造）；新归档（带摘要）
  在迁移后创建，供 V26 第 6 条查询同时列出存量 NULL 与新摘要两类记录。

### 实测（第五轮后）

- 后端：382 总计 / 381 通过 / 0 失败 / 1 跳过（跳过 `AgentEvalLiveTest`），BUILD SUCCESS。
- 前端：**39 项通过**（6 文件）；类型检查与生产构建通过。
- 集成验收（真实迁移、并发、角色闭环）：Docker 守护进程仍不可连接，**未执行**；
  建议执行入口 `mvnw test -Dtest=InvestigationContractMigrationTest -Dgroups=integration`
  （隔离 MySQL），不得以默认单测替代。

## 10. 企业货款快进快出：合理解释核验专项（v2 计划首批实现）

依据《企业货款快进快出：合理解释核验专项计划 v2》（2026-09-07）。本节区分已实现、
演示级实现与未交付项；设计一致性由冻结样例验证。

### 已实现（默认测试全部通过）

- **V27 迁移（§12）**：`case_facts_epoch`（案件事实序号）、`alert_investigation_coverage.unit_submission_id`、
  `verification_basis`、`evidence_artifact_version`、`evidence_verification_event`、
  `alert_explanation_unit`、`explanation_submission`、`explanation_evidence_use`、`explanation_issue`
  七类对象；EDD 增加 purpose/origin_request_id/origin_review_id/issue_bindings/due_calendar_version/resolution_reason。
  布尔列与既有 BIT(1) 映射对齐（Hibernate validate 兼容）。
- **契约版本白名单（§7.3/§10.3）**：0/1=既有语义；2=解释核验政策（v2 试点显式建案开启，
  `enableExplanationPolicy`）；>=3 一律拒绝写入与最终处置。v2 案件的
  `addEvidence/updateHypothesis/updateCoverage` 旧入口全部拒绝并指引单元提交（V2-23，有测试）。
- **决策表（§7.2）**：`ExplanationDecisionRules` 与冻结设计样例 JSON（21 案例）逐例一致，
  含方向互斥、顺序不敏感、六个关键门禁取反不放行（Java 与前端 Vitest 双份移植测试）。
- **逐预警单元 + 混合结论（§3）**：一条预警一个 `AlertExplanationUnit`；提交时原子完成
  校验→幂等→令牌→政策适用性→六问题约束→不可变提交→覆盖/单元指针→假设汇总→epoch→审计。
  同案 EXPLAINED 与 SUSPICIOUS 并存；假设汇总 任一可疑→CONFIRMED、全部解释→REJECTED、否则 OPEN。
- **六问题约束（§5）**：assessment/judgement/factLocation/factKind 校验；UNKNOWN 或 OPEN 关键问题
  阻断 EXPLAINED；SUSPICIOUS 必须具备已评估事实+反向解释+为何不足以消除疑点；
  UNRESOLVED 需显式披露未知；已披露未知不能随 EXPLAINED 提交。
- **两种配方与适用性（§4）**：`GOODS_SETTLED_V1`/`GOODS_PREPAY_V1`；不适用返回
  POLICY_NOT_APPLICABLE 并登记问题，不自动切换宽松配方；预付 NOT_YET_DUE（注入 Clock 评估日）
  与逾期区分，逾期自动产生 DELIVERY_OVERDUE 关键问题（V2-03）。
- **重要性/处置（§6）**：INTEGRITY_BLOCKER 不可降级、不可“不相关”、不可披露未解决后继续采用；
  降级需与处理人不同的复核人确认（V2-19）；处置推进 epoch。
- **版本与令牌（§10）**：draftRevision 乐观锁；caseFactsEpoch 绑定 reviewBasisToken
  （案件锁内比较，失效 409，V2-15）；幂等键同键同内容重放返回原 ID+当前状态（可 STALE，
  V2-22）、同键不同内容 409；amendment 撤回当前提交、保留回放（V2-13）；
  材料变更按 evidence_use 反查置 STALE 并重置覆盖（V2-11）；新材料未关联单元进入案件待分派清单（V2-12）。
- **自审限制（§9/V2-18）**：实质贡献人由服务端从草稿编辑/材料抓取/核验/问题处置/提交人派生；
  最终复核人属于贡献人并集时被阻断（有测试）。
- **EDD 接续（§8/V2-06/07/17/24）**：OPEN DECISION_SUPPORT 阻断最终处置、CONTINUING_REVIEW 不阻断；
  确认可疑可在同一事务内创建 CONTINUING_REVIEW 并将被接替任务置 CANCELLED+
  SUPERSEDED_BY_CONTINUING_REVIEW（非 RESOLVED）；父案 DONE/REPORT_PENDING 后持续任务仍可提交；
  RESOLVED 只能由不同复核人显式完成；自动 RESOLVED 已移除（旧断言按 §18 重写）。
- **前端**：`utils/explanation.ts` 决策表镜像（21 案例 + 守卫/顺序/互斥断言）；
  `ExplanationWorkspace.vue`（单元混合呈现、草稿/提交/修订/问题处置/材料登记/复核取号，
  挂接 CaseDetailView contractVersion>=2）；ReviewView 对 v2 案件取号并按决策表阻断；
  建案 API 支持 `enableExplanationPolicy` 显式试点。

### 演示级实现（可用但简化，需在评审中说明）

- 草稿编辑为 JSON 文本入口（结构化逐题表单未做）；禁用项提示由后端消息承担。
- 工作区“分析员 EDD 建议”直接生成 OPEN 决策支持任务进入复核队列（无独立提案状态）。
- 复核依据取号在前端工作区手动触发；复核页按案件契约版本自动取号。

### 未交付（按计划顺序待后续批次，不冒充已完成）

1. **交易身份字段（§11.1）**：CustomerDataPort 交易适配的
   sourceRecordId/sourceRecordVersion/accountId/counterpartySubjectId/bookedAt/postingStatus/reversalOf
   等可定位字段；冲正关联与多币种原币口径。当前范围核验以草稿声明的
   reviewedTransactionIds/transactionAmounts + ALERT_SCOPE_UNRESOLVED 问题兜底。
2. **档案（Dossier）扩展（§16）**：采用提交及来源版本、反证处置、已披露未知、任务接续、
   政策/评价时点未并入档案 schema。
3. **专用状态事件表（§12）**：当前沿用 Audit Outbox 事件（reason 字符串）；按计划若不适合
   充当业务事件存储应增加专用表，尚未实施。
4. **统一阻断结构（§13）**：code/severity/unitId/questionCode/issueId/transactionIds/message/
   allowedActions 的结构化错误对象未实现（现为异常消息 + 稳定错误码）。
5. **§10.2 白名单非实质字段豁免、§6.2 第二次相同补件的自动停止/升级提示**：未实现。
6. **AI 辅助（§15）**：按计划明确后置于人工闭环稳定后，未实现。
7. **真实 MySQL 迁移实跑（V27）、并发、角色 E2E**：Docker 守护进程不可连接，未执行；
   V27 已列入 `FlywayHibernateValidateTest`/`InvestigationContractMigrationTest`（integration 标签）
   的覆盖范围，环境恢复后运行。

### 测试基线（专项首批后实测）

- 后端默认：**407 总计 / 406 通过 / 0 失败 / 1 跳过**（新增：工作区 16 + 决策表移植 1 +
  EDD 接续 6 + v2 守卫 2）。跳过仍为 `AgentEvalLiveTest`。
- 前端：**63 通过**（7 文件；新增决策表镜像 21 案例 + 守卫/顺序/互斥/预检 + 汇总/协议 3 项）。
- 设计一致性脚本：`node docs/plans/examples/check-rapid-goods-v2-plan.mjs` →
  PASS（21 案例 / 168 项检查）。
- 构建：后端 BUILD SUCCESS；前端 vue-tsc + vite 通过。

## 10. 部署与回退要点（按 v2 计划第 9 节）

1. 先应用 V26 增量迁移（向后兼容，旧二进制可继续读写：新列可空、新摘要字段仅新代码写入）。
2. 升级 Worker 前停止领取新任务并等待当前执行收敛；不要直接重置 RUNNING 状态。
3. 以受控维护窗口切换后端/前端；前端必须与后端同窗口发布（覆盖更新新增必填
   `expectedHypothesisRevision`，旧前端调用会被 400 拒绝）。
4. 回退：保留已写入的新列与历史事实；旧二进制读取新快照（含 alerts 字段）时，
   Jackson 会忽略未知字段并按旧 schema 反序列化（`alertsDigest`/`snapshotSchemaVersion`
   为 null → schema 0），已验证反序列化兼容；若需暂停消费，先停 Worker 再回退。
5. 上线观察：版本 1 自动 DONE 数应为 0；未完成调查案件应出现在分析员待办；
   覆盖版本冲突提示明确；档案中预警数量与冻结关联数一致。
