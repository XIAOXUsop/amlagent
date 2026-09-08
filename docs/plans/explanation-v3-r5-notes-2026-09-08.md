# R5 隔离数据库验收记录（v3 闭环批次 1）

日期：2026-09-08。
依据：[闭环补齐方案](explanation-v3-closure-plan-2026-09-08.md) R1~R5、
[再次验收](../reviews/explanation-v3-reacceptance-2026-09-08.md) RC-01~14。

## 1. 本次交付

### 1.1 隔离 MySQL 首次全绿（R5 前置达成）

Docker Desktop 启动后（本机此前 daemon 未运行），`docker compose up -d` 全部容器健康
（mysql:8.0 / redis:7 / pgvector / prometheus / grafana）。
集成测试隔离机制核实：`IntegrationTestDatabase` 强制 `aml_[a-z0-9_]+_test` schema 白名单，
DROP/CREATE 仅作用于隔离 schema，不触碰 `aml_agent` 业务库。

- **默认测试：450 通过 / 0 失败 / 1 跳过**
- **集成测试（-Pintegration-test）：28 通过 / 0 失败**——本项目集成测试首次全绿
  （上轮验收时为 25 错误/未执行）

### 1.2 集成测试暴露并修复的真实缺陷

隔离数据库首次跑通暴露了 3 类此前被单测 mock 掩盖的问题：

| 缺陷 | 根因 | 修复 |
|---|---|---|
| Hibernate 校验拒绝：`claimed_sha256`/`content_sha256`/`scope_digest`/`basis_digest`/`input_digest` found char, expecting varchar | V27 列定义 CHAR(64)，实体默认 VARCHAR；单测不连库不可见 | 实体 5 个摘要字段加 `columnDefinition = "CHAR(64)"` 对齐 V27 |
| Hibernate 校验拒绝：`explanation_issue_review.original_severity` found varchar, expecting tinyint | V28 是 VARCHAR(32)（正确），实体枚举字段漏 `@Enumerated(EnumType.STRING)`（默认 ORDINAL→TINYINT） | 补注解 |
| Spring 上下文失败：12 个多构造器 Bean "No default constructor"（25 个集成错误的总根因） | 多构造器且全无 @Autowired 时 Spring 无法选择；单构造器自动注入约定掩盖了其余 Bean；集成测试从未跑通过所以未暴露 | 12 个 Bean（AgentOutputValidator、RagEvaluator、InvestigationService、CaseIntakeService、RAG 7 个、LegalIndexPublicationGate）全参主构造器补 @Autowired |

### 1.3 R4 前置：复核预检接口（RC-08）

- `ReviewService.reviewPrecheck`：只读模拟，不创建任务不改状态。输出：变更前 token 一致性、
  案件/复核版本、拟议接续计划逐项覆盖差异（原任务存在性/状态/完成标准）、
  **未被计划引用的 OPEN 决策支持任务清单**（接续后仍会阻断）、v2 决策表快照（reviewer 视角含自审）。
- `EnhancedDueDiligenceService` 新增只读 `lookupTask`/`openTasks`（预检专用，不经写路径）。
- `POST /api/reviews/{caseId}/prechecks` 端点；前端 `reviewPrecheck` API + ReviewView
  最终决定提交前预检（token 陈旧/未接续任务在提交前提示，不产生半完成状态）。
- 防回归：`ReviewServiceTest` 2 项（预检暴露计划差异+token 陈旧；预检零写入）。

## 2. RC 系列验证状态

| 用例 | 状态 | 说明 |
|---|---|---|
| RC-02 来源不可用真实落库 | **通过**（实体/迁移层） | V31 CHECK 随迁移真实落库；Flyway 全链 V1~V31 通过 Hibernate 校验。行级断言待专项集成用例 |
| RC-09 并发竞争 | **通过** | 集成套件含原子租约/条件更新并发用例（ReliabilityWorkflowTest 等） |
| RC-13 交期跨期 | **通过** | 单元层 Clock 重评已覆盖；集成层由 FlywayValidate 链路保障 schema |
| RC-01 补件协议 | **通过**（后端） | 服务层测试覆盖；页面 E2E 属 R5 尾批 |
| RC-03/05/06/07 服务层语义 | **已通过**（A6 修复批次） | 真实库行级断言待专项用例 |
| RC-04/08/10/11/12/14 | 部分待补 | RC-08 预检已交付；RC-04 AlertScopePort、RC-10/11/12 专项集成用例、RC-14 角色 E2E 未实施 |

## 3. 下一步（闭环方案剩余）

1. **专项集成用例**：A6-06 行级落库（UNAVAILABLE 无摘要行真实存在 + CHECK 生效）、
   TP-27 真实库幂等重放（epoch 实际推进）、RC-10 失败注入回滚（复核/接续/审计）。
2. **R2 完整版**：Claim 写入链（PUT /claims、verification-actions、ClaimEvidenceLink）、
   AlertScopePort 逐预警范围冻结（RC-04）、sourceFamily 独立性（TP-08）。
3. **R4 完整版**：逐事实键义务绑定、ReviewView 接续计划录入与预检联动（前端录入 UI）。
4. **RC-14**：三角色真实认证 E2E。

## 4. 验证口径

- 默认：450 通过 / 0 失败 / 1 跳过（跳过 = AgentEvalLiveTest，默认不调真实模型）。
- 集成：28 通过 / 0 失败（真实 MySQL V1~V31 迁移 + Hibernate 校验 + 并发/回滚/可靠工作流）。
- 前端：66 通过 + vue-tsc/Vite 构建通过。
