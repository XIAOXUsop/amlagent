# 对抗性审查：v4 退款与解释核验链

日期：2026-09-09。基线：`a1f4be4`；审查范围为 `7ad1ff5..a1f4be4` 的 48 个变更文件及关联提交、复核、档案、权限和测试链。开始时工作树干净。

**结论：暂不建议签收 v4 业务闭环。** 本次已有测试可以通过，但仍存在从客户声明直接进入可信退款事实、旧核验覆盖新反证、退款/Claim 变化未进入最终复核依据等路径。问题涉及调查系统的事实和结论，不意味着本系统能直接执行资金转账。

本次仅审查、运行现有测试和临时对抗探针，不修改业务代码。严重性采用：P1＝可能错误采用业务结论或阻断核心正常流程；P2＝重要一致性或配套功能缺陷。以下区分运行复现和静态确认，不将单元探针称为真实数据库或浏览器验收。

## 1. 发现汇总

| 编号 | 等级 | 问题 | 验证方式 |
|---|---|---|---|
| A9-01 | P1 | 老的通用 CONFIRMED 覆盖新的定向 UNRESOLVED，重新提交仍可最终排除 | 评估器及完整服务调用链探针 |
| A9-02 | P1 | 授权到期未校验，过期授权仍能支持 EXPLAINED；时间检查还能通过省略 facts 跳过 | 过期授权服务/提交探针；省略分支静态确认 |
| A9-03 | P1 | 手工声明可自标 POSTED，并计入“已核实退款” | 登记与金额账探针；控制器透传确认 |
| A9-04 | P1 | 原付款金额由请求定义，累计超退仅事后显示，未分配退款也直接扣余额 | 两个金额账探针；已有并发测试反向佐证 |
| A9-05 | P1 | 新增无关收款人退款后旧 token、CURRENT 提交和最终排除资格不变 | 跨服务调用链探针 |
| A9-06 | P1 | 独立 Claim 接口写入 C3=CONTRADICTED 后，最终复核仍使用草稿中的 SUPPORTED | 同一 Workspace 的 ClaimService 调用链探针 |
| A9-07 | P1 | 新增“必须冻结预警范围”的要求，但没有生产调用入口完成冻结 | 全部生产调用点、控制器和持久化字段静态核查 |
| A9-08 | P2 | 幂等摘要遗漏收款账户，同键更换账户被当作原请求重放 | 幂等探针 |

## 2. 可复现的业务缺陷

### A9-01：合并核验链后没有重新排序

位置：[EvidenceAdmissibilityService.java:116](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/EvidenceAdmissibilityService.java:116)。

代码先追加指定事实键的核验链，再追加 `subjectFactKey IS NULL` 的通用链，然后直接取整个列表最后一个元素。两个查询各自有排序，并不能保证拼接后的列表有序。因此只要存在通用核验，其最后一条就压过所有定向核验。

复现：9 月 1 日通用核验 CONFIRMED；9 月 2 日 Q1 定向核验 UNRESOLVED。`effectiveSupport(artifactId, "Q1")` 仍返回 CONFIRMED。进一步调用真实工作流服务：已采用解释 → Q1 无法确认 → 旧提交 STALE → 原草稿重新提交 → 新提交 CURRENT → 最终排除校验通过。

原测试夹具还将 `find...SubjectFactKeyIsNull...` 模拟成返回所有事件，将定向查询模拟为空，掩盖了真实查询的差异。本轮临时探针按仓储实际条件分开返回 NULL 和 Q1 事件后验证。

修复：明确通用与定向核验的适用优先级；需要按时间合并时，对合并结果以 `(eventTime,id)` 稳定排序后计算状态。保留“之后有效补核验可恢复”的正向路径。增加有通用旧记录、定向新否认、相同时刻不同序号的真实仓储测试。

### A9-02：到期日被读取，但不参与授权有效性

位置：[PaymentAuthorityFactService.java:73](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/PaymentAuthorityFactService.java:73)、[ExplanationWorkspaceService.java:1612](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1612)。

`AuthorityFact` 接收 effectiveTo，但 GRANTED 分支只检查 effectiveFrom 不晚于付款日。反例 AU-01：8 月 1 日生效、8 月 31 日到期、9 月 2 日付款，服务返回 VALID；将该序列写入完整集团代付草稿后，EXPLAINED 提交及最终排除校验均通过。

另一个静态分支：只有 authority.facts 是非空数组才执行整段时间校验；省略 facts 或传空数组可跳过。paymentDate 也来自草稿，未逐笔使用服务器交易时间；当前单一日期不能充分代表不同日期的两笔付款。

修复：逐笔读取权威付款时间，校验授权起止期间及对应 authorityRef、主体、订单范围；明确日期边界。缺少必要的时间事实不能跳过检查后当作充分支持。补过期一日、同日边界、删除 facts、修改客户端 paymentDate、多笔跨有效期的反例。

### A9-03：手工材料可以变成“银行已入账退款”

位置：[RefundController.java:49](/D:/JCode/backend/src/main/java/com/bank/aml/controller/RefundController.java:49)、[RefundLedgerService.java:107](/D:/JCode/backend/src/main/java/com/bank/aml/refund/RefundLedgerService.java:107)。

控制器直接透传 sourceSystem/eventStatus；服务只校验状态枚举，并且 eventStatus 为空时默认 POSTED，没有核对来源返回的到账事实。前端允许选择“手工声明”和“已入账”，并默认 CORE_BANKING + POSTED。

复现：登记 `sourceSystem=CUSTOMER_PROVIDED,eventStatus=POSTED,amount=80.00`，没有独立到账证据，返回 POSTED；金额账将 80 计入 totalRefunded。普通 ANALYST 已有合法调用权限，触发不需要绕过认证。

修复：区分客户声明、来源待核实事件和银行确认到账。手工接口不能授予 POSTED；到账状态应由受控来源或明确的核验采用动作产生。sourceSystem 字符串不能充当来源真实性凭证。对来源字段伪造、空状态、无证据手工录入做 API 层测试。

### A9-04：金额账的原始金额可改写，资金分配不变式未执行

位置：[RefundController.java:60](/D:/JCode/backend/src/main/java/com/bank/aml/controller/RefundController.java:60)、[RefundLedgerService.java:169](/D:/JCode/backend/src/main/java/com/bank/aml/refund/RefundLedgerService.java:169)、[RefundLedgerService.java:236](/D:/JCode/backend/src/main/java/com/bank/aml/refund/RefundLedgerService.java:236)。

登记路径只保证“分配合计不大于本次退款金额”，不查询原付款是否存在、是否属于本案件、原分配余额或累计有效退款。ledger 接收调用者提供的 originalAmounts，按 transactionId 合并，原 allocationKey 没有用于额度保护。

反例一：同一原分配以两个事件各登记退款 80，均成功。传原额 120，账面保留 -40 并提示超额；仅把查询原额改成 200，超额提示消失、保留变为 +40。缺陷的核心是“真实原额”没有服务器约束。

反例二：登记 200 的 POSTED 事件，分配为空。查询原额 120，totalRetained=-80，refundedByTransaction 为空，overAllocations 也为空。总账按事件全额扣减，逐笔账和异常检测却只看分配，导致两种口径脱节。

已有 `RefundLedgerIntegrationTest.concurrentRegistrationIsSerializedByCaseLock` 明确断言原额 12 万、两次 8 万均落库，再显示超额；这证明行锁串行化，不证明 RF-22 所要求的有效额度保护。

修复：用服务器冻结的原付款/原分配取代请求金额；在同一事务中按原分配锁定并核对累计额度。真实发生的异常退款事件可以保留，但不应被接受为合法分配或充分解释；分别展示事件总额、已关联金额和未关联金额。按 allocationKey 校验跨单元占用，增加并发超额、虚构原交易、部分分配和空分配测试。

### A9-05：退款新事实未进入最终复核的失效链

位置：[RefundLedgerService.java:97](/D:/JCode/backend/src/main/java/com/bank/aml/refund/RefundLedgerService.java:97)、[ExplanationWorkspaceService.java:2105](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:2105)。

register/reverse 虽取得案件锁，但没有推进事实版本、调用影响评估、登记问题/责任或使受影响采用关系失效。RefundAuthorityService 的评估目前只是由请求枚举驱动的只读结果；其影响评估没有接入实际登记和最终复核。

复现：已有 CURRENT 解释并取得 token；登记向无关 D 账户支付 12 万的退款；旧 token 完全不变，原提交仍 CURRENT，携旧 token 仍能通过 EXCLUDE_FALSE_POSITIVE 校验。该探针复用了当前工作流的同一案件仓储，并实际调用 RefundLedgerService。

修复：退款新增、更正、冲正进入依赖变化流程，至少推进 token 对应事实版本，并将未完成匹配/未解决权限的影响显式带入 readiness。按受影响事实建立新调查或问题，保留历史决定；正常退款不应自动判为可疑，但尚未核实的新事实也不能被旧决定无声覆盖。

### A9-06：持久化 Claim 与采用判断存在两套状态

位置：[ExplanationClaimService.java:64](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationClaimService.java:64)、[ExplanationWorkspaceService.java:169](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:169)。

新 `PUT /units/{unitId}/claims` 将 Claim 与链接落库，但不改变 caseFactsEpoch/当前提交、不登记矛盾问题。Workspace 只在展示时读取 ClaimService，集团代付可采用性仍主要读取 draft/payload 中的 claims。因此页面/API 持久化的事实状态与采用判断可以矛盾。

复现：集团代付解释以 C3=SUPPORTED 提交；通过同一 Workspace 内注入的 ClaimService 将 C3 写为 CONTRADICTED，说明付款主体否认授权；确认仓储保存了矛盾状态，但 token 不变，原 token 最终排除仍通过。

此外，当前更新覆盖 Claim 并删除旧链接，没有按采用版本保留完整链接历史；declareClaims 本身也没有查询 unit 是否属于 case。后两项为静态风险，不冒充本轮独立数据库复现。

修复：统一 Claim 的权威版本与采用来源，提交冻结 Claim revision 和证据/核验链接；Claim 更正触发精确失效、贡献人更新和 token 变化。校验案件、单元归属及预期版本，禁止只写展示数据而不影响业务门禁。

### A9-07：范围冻结强制要求缺少可达的生产入口

位置：[ExplanationWorkspaceService.java:1852](/D:/JCode/backend/src/main/java/com/bank/aml/explanation/ExplanationWorkspaceService.java:1852)、[AlertScopeService.java:45](/D:/JCode/backend/src/main/java/com/bank/aml/investigation/AlertScopeService.java:45)。

现在没有冻结命中范围就拒绝 EXPLAINED，这个默认拒绝方向正确。但全量生产源码搜索中，freezeScope 只有定义，没有调用者；triggerTransactionIds 的写入仅在该服务及实体 setter，AlertController 的预警创建 DTO 也不接收对应来源字段。

因此通过当前正常分诊 API 创建、启用解释政策的案件，无法沿现有公开流程完成新增前置条件，最终解释路径被阻断。测试通过直接调用服务或设置实体字段建立冻结范围，无法代表产品入口已接通。

修复：提供受控上游导入及本地演示适配路径，将范围与来源版本绑定后可达地冻结；不要直接开放让客户端随意指定“权威全集”的接口。上游未接入时明确限制该政策启用，保留其他调查能力，并补“从预警创建到解释提交”的成功路径。

### A9-08：同键更换收款账户不会产生冲突

位置：[RefundLedgerService.java:281](/D:/JCode/backend/src/main/java/com/bank/aml/refund/RefundLedgerService.java:281)。

payloadDigest 包含 payeeSubject，却遗漏 payeeAccountRef。使用同一来源键先登记 ACCT-P，再将账户改为 ACCT-D，服务返回 idempotentReplay=true，保留 ACCT-P。调用者会误以为新账户内容已被同一事件接受，账户差异也不会进入更正流程。

修复：摘要应覆盖所有有业务意义的字段，在明确的规范化规则下生成；同键账户变化返回内容冲突并进入更正/新版本路径。补不同账户、账户由空变非空、同金额不同精度表示的契约测试。

## 3. 仍未闭合的配套功能

这些项目需纳入下一次签收，但未在本轮另行开展浏览器或数据库攻击验证：

| 项目 | 当前证据 | 影响 |
|---|---|---|
| 复核员读取退款账 | RefundController 类级权限仅 ANALYST/ADMIN；ledger 无 REVIEWER 例外 | REVIEWER 在案件详情可看到退款面板，但读取账本会受权限限制；读写权限应分别设置 |
| 拟议接续的前端路径 | ReviewView 仍先用当前 readiness 阻断，再发不含 continuationTasks 的预检；新模拟结果未形成完整交互 | 新增模拟计算不等于合法“确认并接续”可从页面执行 |
| 任务追溯 | ReviewService 接续仍传 originReviewId=null | 难以精确定位是哪次决定承接的义务 |
| 历史档案 | 已改为读取当前采用 payload，但仍取 unit.currentSubmissionId、最新 basis、当前 Claim；没有按指定 reviewId 固定整套依赖 | 修复了草稿冒充提交，却尚不能证明 R1/R2 的分别回放 |
| RAG 真正失败门禁 | RagEvalReport 已增加 environmentFailure/rerankInvocations；A/B 集成断言仍未检查这两个字段 | 记录失败标志不等于 CI 会失败；此前假绿风险尚未在集成入口关闭 |
| 来源家族 | sourceFamilyOf 仅将 sourceSystem:sourceReference 转小写 | 不同引用的同源副本仍可能被算作不同家族；3 个归一化单测不能代表复制/转发来源追踪 |

G4 表中“RF-06 全链”“RF-24 复核失败回滚”“RF-30 回退”等标记，应按真实验证层重新标注。金额账行级测试不等同于事件→解释→独立复核→义务→历史档案全链；退款分配事务回滚不等同于复核/接续/outbox 联合回滚。

## 4. 实际执行、证据和边界

| 检查 | 实际结果 |
|---|---|
| 当前 HEAD 默认后端 `verify` | 491 总计，490 通过、1 跳过，0 失败/错误；增量构建及打包成功 |
| 前端测试 | 8 文件、66 项通过 |
| 前端类型/生产构建 | 通过；约 558 KB 分块体积提示仍在 |
| 对抗探针 | **10/10 复现目标异常行为**（评估器/金额账 6 项、工作流 4 项）；最终日志 probes-corrected-final.log。这不是“业务正确”通过数 |
| 真实基础设施集成 | 未重跑；本轮连接检查 Redis 6379、PGVector 5433 不可用 |
| 浏览器 | 未执行本轮 E2E，未复用上轮 7/7 结果冒充本轮结果 |

证据保留在 [本轮证据目录](/D:/JCode/.tmp/adversarial-20260909)：

- backend-verify.log：当前提交默认测试及打包。
- frontend-test.log、frontend-build.log：前端执行记录。
- AdversarialReview20260909Test.java：核验顺序、授权到期、手工 POSTED、累计/未分配金额、账户幂等探针。
- AdversarialWorkflow20260909Test.java：退款后依据不变、过期授权最终排除、持久化矛盾 Claim、定向核验失效后重新提交。
- probes-execution.log：初始 8 个探针运行记录。
- probes-final.log：补充阶段记录，包括旧夹具查询语义造成的 1 个探针错误；不是最终验收结果。
- probes-corrected-final.log：按真实仓储筛选语义校正后的最终探针记录。

运行命令主干为 Maven 3.9.9、Java 21、离线仓库、fork javac、`maven.compiler.proc=none`、工作区 java.io.tmpdir。默认 verify 在加入探针之前执行。探针执行选择：

```text
-Dtest=AdversarialReview20260909Test,AdversarialWorkflow20260909Test#probe*
```

临时探针编译在生成 class 后关闭本地 Maven 缓存 JAR 时触发 Windows AccessDeniedException；确认 class 更新时间及方法已生成后，用同一依赖执行 `surefire:test`。编译命令的非零结果保留，不写成“干净全量编译通过”。临时测试副本在证据目录留存，正式测试源码和编译目录中的临时探针在结束前移除。

最终核对：HEAD 仍为 a1f4be4；Git 仅新增本审查报告，业务代码与原有测试无变更。未启动或修改原有数据库、Redis、PGVector 服务。

## 5. 建议的修复与复验顺序

第一批先修 A9-01/02/05/06：统一当前有效事实、采用关系与最终复核；把错误放行探针反转为必须拒绝，并保留有效补核验、正常授权、正常退款的成功对照。

第二批修 A9-03/04/08：建立可信事件来源和原分配金额口径，明确保留异常事实与接受有效分配的区别，完成累计/并发/幂等/部分分配测试。

第三批补 A9-07 及配套入口：从公开 API 创建预警、冻结范围、录入核验、提交解释、独立复核、接续任务到按 reviewId 回放档案，使用真实数据库和三角色浏览器测试。修复后再执行集成全套及真实 RAG 门禁，不能只增加 DTO 和断言数量后签收。
