# 商业银行智能反洗钱（AML）与高风险客户尽调 Agent 平台

一个基于 **Java 21 + Spring Boot 3 + LangChain4j** 的企业级反洗钱尽调 Agent 平台：
接收反洗钱系统预警工单后，可靠地调度 Agent 工作流，自动完成交易画像、股权穿透、制裁名单筛查、
监管法规检索、风险研判和结构化报告生成；使用独立于大模型的 Guardrails 规则护栏校验最终结论，
并将高风险工单转入**人工复核闭环**。具备**可评测、可追溯、可恢复、可观测、安全可控**的企业级能力：
JWT 三角色认证、Outbox+Redis Streams 可靠任务、混合 RAG 证据追溯、配置化规则护栏、固定测试集评测、
Prometheus/Grafana 监控与自动化测试，并提供受 ADMIN 权限保护的客户主数据维护与 Excel 导入能力。
名单筛查采用“模糊召回 → 身份要素评分 → 人工候选核验”三阶段流程；候选复核使用追加式 revision
防止并发覆盖，只有算法确定命中或人工确认的候选才进入 Guardrail。工单详情可导出带 SHA-256
完整性摘要的调查档案，集中交付快照元数据、工作流、工具轨迹、证据和两类人工复核历史。

平台同时提供面向 ADMIN 的“当前客户 AI 小助”：在客户详情页进行只读、多轮、流式分析。会话由后端绑定当前客户，
模型只接收脱敏冻结快照；七个工具均为只读且客户工具不接受 `customerId`。输入、跨 token 流式输出与最终回答经过
三层确定性防护，Redis 租约避免同会话并发，Redis Stream 支持 SSE 重放，MySQL 加密消息作为最终事实源。
公开银行知识与企业 AML 法规检索结果在每次 run 开始前冻结，回答引用只能来自该证据包。

## 核心工作流

```
预警工单触发
  → Transactional Outbox + Redis Streams 可靠任务队列（幂等 / 重试 / 死信 / 崩溃恢复）
  → 任务规划 Planner      拆解子任务（交易画像 / 股权穿透 / 黑名单 / 法规匹配）
  → 工具调用 Tool Engine  并行调用四类数据工具（LangChain4j @Tool）
  → 企业 RAG 法规比对     ACL/有效期门控 + 向量/中文词法召回 + 加权 RRF/精排，证据可追溯
  → 深度风险推理          模型综合研判，输出风险点与评级
  → Guardrails 规则护栏   配置化规则强制修正（一级制裁 → 高风险 + 转人工 HOLD）
  → 结构化报告            含证据链与法规证据 ID，实时 SSE 推送到前端
  → 调查档案导出          聚合流程/工具/快照/复核记录并生成 SHA-256 内容摘要
  → Agent / 规则 / RAG评测 独立案例夹具运行真实模型，原始结果与 Guardrails 分开计分
```

## 当前验证结果

| 验证项目 | 结果 | 数据性质 |
|---|---|---|
| 风险规则回归 | 100 条独立期望的合成边界案例 | 覆盖合法跨境/夜间负例、交易模式、UBO、数据缺失和制裁；不调用 LLM |
| 一级制裁规则漏报 | **0 / 5** | 合成规则案例 |
| RAG 法规检索评测 v2（18 条） | 无精排：Recall@5/Top3 **93.3%/93.3%**、MRR **81.1%**、nDCG@5 **84.2%**、无答案拒答 **100%**、P95 **135ms**；本地 bge 精排：**100%/100%**、MRR **95.6%**、nDCG@5 **96.7%**、拒答 **100%**、P95 **671ms** | 15 条业务改写 + 3 条无答案，真实 MySQL/PGVector/Redis，2026-08-23 本机冷缓存；`PENDING_DOMAIN_REVIEW`，仅为 DEV 基线 |
| 独立 Agent 案例集 | 15 条（DEV 9 / TEST 6） | AI 辅助人工整理的合成案例，待领域专家复核 |
| DeepSeek 真实 Agent DEV（v2 → v5） | 原始风险准确率 **44.4% → 100%**；Guardrails 后 **77.8% → 100%**；高风险召回率 **40% → 100%**；无效输出 **2/9 → 0/9** | 9 条冻结合成 DEV（`PENDING_DOMAIN_REVIEW`）；2026-08-12/13 本地实测 |
| v5 工具与证据覆盖 | 必需工具召回率 **100%**；法规 evidenceId 召回率 **100%**；端到端任务通过率 **66.7%**；strictPass **0**（5 次重复调用） | 详见 `DeepSeek真实Agent评测报告-二轮对比.md` |
| 首轮工具与证据覆盖（v2） | 必需工具召回率 **94.4%**；法规 evidenceId 召回率 **77.8%** | 失败集中在隐藏法规关键词导致的无效重试，已通过 v5 工具契约修复 |
| 当前客户 AI 小助确定性评测 | 70 条合成案例意图分类 **70/70**；15/15 攻击在模型前阻断；后端 242 项单测、22 项完整集成回归及 2 项助手安全增量、前端 15 项测试通过 | 2026-08-23 本机验证；新助手真实模型质量评测尚未执行，不宣称模型准确率 |

> 规则回归结果只用于验证 Guardrails 和风险规则，不代表大模型准确率。Agent 数字来自 DeepSeek 对 9 条合成 DEV 的迭代基线，v5 指标为调优集结果（可能过拟合），需以冻结的隐藏 TEST 分片验证泛化能力；Agent 与 RAG 数据集标签仍待领域专家复核（`PENDING_DOMAIN_REVIEW`），因此不等同生产准确率。

运行真实 Agent DEV 评测前，请在启动后端的同一终端设置模型密钥，例如 PowerShell：

```powershell
$env:DEEPSEEK_API_KEY = "your-key"
```

真实 Agent DEV 评测默认不会在普通测试中调用外部模型。确认 9 条数据均为可发送的合成案例后，可显式运行：

```powershell
$env:RUN_LIVE_AGENT_EVAL = "true"
./mvnw -Dtest=AgentEvalLiveTest test
```

脱敏后的 JSON 报告写入 `backend/target/agent-eval/`；不保存 API Key、客户姓名、证件号、原始工具参数或模型长文本。

未配置密钥时，`POST /api/eval/agent/dev` 会返回 `INVALID_MODEL_FALLBACK`，且所有质量指标分母为 0。

### 实验可复现性

真实 Agent 评测结果由以下版本要素锁定，可通过 `GET /api/eval/agent/dataset` 查看数据集 SHA-256：

| 要素 | 值 |
|---|---|
| 代码基线 | 以当前 Git commit 为准（运行报告中记录版本，不在 README 固化易过期哈希） |
| Prompt 版本 | `aml-dd-agent-v7-production-contract-final-decision` |
| 模型 | `deepseek-v4-flash`（多提供商可切换） |
| 数据集 | `agent-cases-v1.json`（15 条：DEV 9 / DEMO_TEST 6，`PENDING_DOMAIN_REVIEW`）；正式 TEST 从仓库外加载专家审批数据 |
| 数据集哈希 | 由 `AgentEvalDatasetLoader` 启动时计算（内置数据 + 可选外部 TEST 的组合 SHA-256） |
| 护栏规则 | `risk_rule` 表 + `RiskRuleSeeder`（确定性 DSL） |

> v5 的 100% 指标来自反复调优的 9 条 DEV，仅证明当前迭代在 DEV 上有效；需以冻结的隐藏 TEST 分片验证泛化能力，不宣称真实银行生产准确率。

## 技术栈

| 层级 | 选型 |
|---|---|
| Agent 框架 | Spring Boot 3.5 + LangChain4j 1.18（AiServices + @Tool 并行调用） |
| 大模型 | 多提供商可配置：DeepSeek / 通义千问 / OpenAI / Claude / Mock |
| 向量库（RAG） | PostgreSQL 16 + pgvector（法规条文向量检索） |
| 业务存储 | MySQL 8（工单、工作流日志）+ Redis 7 |
| Embedding | all-MiniLM-L6-v2（本地离线，可换中文 embedding） |
| 前端 | Vue 3 + Vite + Element Plus + ECharts（SSE 实时监控） |

## 快速启动

### 1. 启动依赖（Docker）

```bash
docker compose up -d
```

启动三个容器：MySQL(3307)、PostgreSQL+pgvector(5433)、Redis(6379)。
> 注：因本机 3306/5432 常被本机数据库占用，容器端口已避开，如需调整见 `docker-compose.yml`。

### 2. 启动后端（8080）

```bash
cd backend
./mvnw spring-boot:run          # 默认 Mock 模型（无 API Key，可离线演示）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # 启用真实 DeepSeek Key
```

> 项目内置 **Maven Wrapper（3.9.x）**，无需本机安装新版 Maven。
> 真实 API Key 只通过 `DEEPSEEK_API_KEY` 环境变量注入；项目配置文件只保留占位符。

### 3. 启动监控（可选）

```bash
docker compose up -d prometheus grafana
# Prometheus: http://localhost:9090   Grafana: http://localhost:3000 (admin/admin)
```

### 4. 启动前端（5173）

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**，使用以下账号登录：

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | ADMIN（全部权限，含评测） |
| reviewer | reviewer123 | REVIEWER（人工复核） |
| analyst | analyst123 | ANALYST（工单处理） |

登录后：选择客户 → 创建预警工单 → 实时查看 Agent 工作流推进与尽调报告；HOLD 工单可进入"人工复核"页面处置。

AI 小助仅在开发环境默认启用。ADMIN 可进入“客户管理 → 查看 → AI 小助”；生产环境必须显式设置
`AML_ASSISTANT_ENABLED=true`，否则入口与接口保持关闭。它不能修改客户、工单、账户或审核状态，也不能跨客户比较。

## 配置 LLM（可选）

默认未配置 API Key 时自动降级到 **Mock 模型**（可离线演示完整链路）。
接入真实模型：设置环境变量或直接修改 `backend/src/main/resources/application.yml`：

```yaml
aml:
  llm:
    active-provider: deepseek   # 切换 deepseek / openai / qwen / claude / mock
    providers:
      deepseek:
        type: openai-compatible
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY:}
        model-name: deepseek-v4-flash
```

| 提供商 | type | base-url | model-name |
|---|---|---|---|
| DeepSeek | openai-compatible | https://api.deepseek.com | deepseek-v4-flash |
| 通义千问 | openai-compatible | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen-plus |
| OpenAI | openai-compatible | https://api.openai.com/v1 | gpt-4o-mini |
| Claude | anthropic | — | claude-sonnet-4-6 |

## 演示客户

| 客户 | 特征 | 预期结果 |
|---|---|---|
| C001 张伟 | 夜间+跨境大额频繁、命中 OFAC 一级制裁 | 高风险 → 转人工（HOLD） |
| C002 王强 | 现金拆分存取、命中人行可疑名单 | 高风险 |
| C003 李娜 | 正常交易 | 低/中风险 |

## 目录结构

```
backend/                  Spring Boot 后端
  src/main/java/com/bank/aml/
    common/               ApiError、全局异常、枚举(CaseStatus/WorkflowStage)、异常分类
    messaging/            Transactional Outbox、Redis Streams 生产者/消费者、死信、Pending 接管
    workflow/             case_execution 阶段执行记录（检查点）
    risk/                 risk_rule 配置化规则 + RiskRuleEngine（DSL）
    sanction/             制裁候选召回后的身份匹配评分、解释与分级处置
    dossier/              案件调查档案聚合与 SHA-256 完整性摘要
    evaluation/           规则回归、独立 Agent 案例集、RAG 评测与评测报告
    agent/                AiServices Agent、报告 DTO、Guardrails（规则驱动）
    tools/                四个 @Tool（交易/股权/黑名单/法规）
    rag/                  法规导入（evidenceId）、混合检索（向量+关键词+RRF）
    datasource/           客户主数据 Port/Adapter、演示数据与 JPA 实体/仓库
    service/              工作流编排、客户管理、规则兜底报告、SSE 推送
    config/               LLM 多提供商工厂、双数据源、RAG/队列配置
  data/legal/             法规文档（启动时向量化入库）
frontend/                 Vue 3 界面（工单看板、工作流监控、报告）
docker-compose.yml        MySQL + PostgreSQL(pgvector) + Redis
```

## API 概览

> 除登录与监控端点外，其余接口需认证。认证使用 HttpOnly Cookie（登录后自动携带），也支持 `Authorization: Bearer <token>`；SSE 通过 Cookie 认证，JWT 不进入 URL/localStorage。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/login | 登录 `{username, password}` → JWT（放行） |
| POST | /api/cases | 创建工单 `{customerId, alertRule, autoProcess}`，自动写入 Outbox 触发尽调 |
| GET | /api/cases | 工单列表（DTO） |
| POST | /api/cases/{id}/process | 手动触发（幂等：已在执行/完成的工单忽略） |
| POST | /api/cases/{id}/retry | 人工重试（HOLD/FAILED 工单重新入队） |
| GET | /api/cases/{id}/events | 订阅工作流实时进度（SSE） |
| GET | /api/cases/{id}/executions | 阶段执行记录（检查点：阶段/耗时/输入输出） |
| GET | /api/cases/{id}/logs | 工作流日志（含触发规则） |
| GET | /api/cases/{id} | 工单详情（含报告 reportJson、执行版本） |
| GET | /api/queues/dead | 死信队列查看 |
| POST | /api/eval/rules | 确定性规则回归（不调用 LLM，ADMIN） |
| POST | /api/eval/rag | 独立 RAG DEV 评测（Recall@5/Top3/MRR/nDCG/拒答/P95，ADMIN） |
| GET | /api/admin/rag/indexes | 索引 Manifest 与发布状态（ADMIN） |
| POST | /api/admin/rag/indexes/{version}/rollback | 回滚到已发布索引（ADMIN，审计） |
| POST | /api/admin/rag/indexes/cleanup | 清理失败/退役候选（ADMIN，可恢复） |
| GET | /api/admin/rag/quarantines | 入库隔离记录（ADMIN） |
| GET | /api/eval/agent/status | 真实 Agent 评测就绪状态及数据集概览（ADMIN） |
| POST | /api/eval/agent/dev | 运行真实模型 DEV 评测；Mock/fallback 直接标记无效（ADMIN） |
| POST | /api/eval/agent/test | 运行冻结的隐藏 TEST 分片（最终评测，标准答案冻结，ADMIN） |
| GET | /api/eval/agent/dataset | 独立 Agent 案例集元信息（含 datasetHash），不返回 TEST 标准答案（ADMIN） |
| GET | /api/eval/reports?evalType=RULE_REGRESSION\|AGENT_DEV\|AGENT_TEST | 按类型查询历史评测报告（ADMIN） |
| GET | /api/reviews/pending | 待复核队列（REVIEWER/ADMIN） |
| POST | /api/reviews/{id} | 提交复核决定（APPROVE/REJECT/ESCALATE，REVIEWER/ADMIN） |
| GET | /api/reviews/{id} | 工单复核记录 |
| GET | /api/reviews/stats | 复核反馈统计（一致率等） |
| GET/POST | /api/admin/customers | 客户分页查询/新增（ADMIN） |
| PUT/DELETE | /api/admin/customers/{id} | 客户编辑/软删除（ADMIN） |
| PUT | /api/admin/customers/{id}/status | 启停客户（ADMIN） |
| POST | /api/admin/customers/import | Excel 批量导入，5MB/1000 行限制（ADMIN） |
| GET | /api/queues/dead | 死信队列查看（ADMIN） |
| POST | /api/queues/dead/{caseId}/replay | 死信重放（ADMIN） |
| GET | /actuator/prometheus | Prometheus 指标（仅 ADMIN） |
| GET | /swagger-ui.html | OpenAPI 文档（开发环境公开，生产环境关闭） |
| GET | /api/cases/customers | 演示客户（脱敏，不含证件号） |
| GET | /api/cases/stats | 工单全量状态统计（态势概览，跨分页） |
| GET | /api/agent/ping | LLM 连通性验证 |

## 关键设计

- **可靠异步任务**：Transactional Outbox（工单与事件同事务，`caseId:eventType:executionVersion` 幂等键防重复发布）→ **发布抢占（PENDING→PUBLISHING→PUBLISHED 原子状态机，多实例并发只允许一个发布器投递，杜绝重复/错投；崩溃残留由陈旧 Claim 30s 回收）** → Redis Streams 消费组 → 条件更新抢占（`executionVersion`）→ 版本化租约 + Worker 心跳（心跳/完成/失败均绑定 worker+version，防旧 Worker 污染新执行版本）→ 指数退避重试（RETRY_WAIT）→ 死信队列 → Pending 超时接管（服务重启任务不丢失）。重试、接管、死信重放统一走 Outbox，消除数据库提交与 Redis 投递之间的双写丢失窗口；Stream 按 MAXLEN 近似裁剪，防止已 ACK 消息长期驻留导致内存无限增长。
- **Guardrails 配置化**：`risk_rule` 表驱动（DSL 条件表达式 + 优先级 + 生效时间），决策可解释（ruleCode / version / evidence / 动作），一级制裁命中零漏报并强制转人工。规则加载带 60s TTL 缓存，避免每次护栏评估查库。
- **RAG 证据追溯**：结构化检索显式携带法域、适用时间和访问范围，存储层预过滤并二次 fail-closed 校验；法规片段带 `evidenceId`，按主题冻结到快照，关键处置必须由被引用条文直接支持。
- **可回滚索引供应链**：语料、分块、元数据、Embedding 制品与距离度量共同构成完整索引身份；中央租约/心跳构建、Smoke Test、原子发布、显式回滚、可恢复清理和管理员审计避免半成品污染在线检索。
- **知识投毒隔离**：入库前检测非常规/超大文件、控制字符、提示注入、疑似密钥和身份数据；只保存摘要与原因代码，隔离记录先提交再阻断构建。
- **召回 + 精排两步走**：PGVector + 中文词法加权 RRF 召回 top-20，本地 bge-reranker（Cross-Encoder）按长度分桶微批精排；固定 DEV 上 Recall@5 由 **93.3%** 提升到 **100%**，代价是本机冷缓存 P95 由 **135ms** 增至 **671ms**，因此质量与延迟分别设发布门槛，不隐藏成本。
- **分层评测**：固定种子生成 100 条规则回归输入，各场景期望结果显式定义且基线固定为低风险，覆盖困难负例并隔离验证 Guardrails 升级行为；RAG 同源问题集仅用于检索回归。真实 Agent 指标来自独立 DEV 夹具，避免将规则结果误标为模型能力。
- **独立 Agent 案例集**：首版 15 条版本化合成案例与规则代码完全分离，覆盖正常交易、跨境夜间、拆分交易、复杂 UBO、名单精确/误命中、数据缺失和提示注入；仓库内 DEV/DEMO_TEST 仅用于开发演示，正式 TEST 只从仓库外加载领域专家审批数据，且标准答案不经接口暴露。当前内置标签状态为 `PENDING_DOMAIN_REVIEW`，不宣称专家金标。
- **真实 Agent DEV 评测**：每例动态创建独立 AiServices 与冻结工具夹具，校验客户 ID / 姓名 / 证件号及法规查询主题并记录并发调用轨迹；同时保留原始模型契约通过率与 Guardrails 后端到端任务通过率，报告风险准确率、高风险召回、人工升级、结构化代码覆盖、法规 evidenceId 引用、工具精度、禁错检测、P50/P95 与 Token。模型异常进入严格分母，Mock 或 fallback 不计质量指标；持久化副本对风险、代码、工具名使用闭集白名单并移除模型原文、身份和查询参数。
- **DeepSeek 工具调用兼容**：V4 默认 thinking 模式要求多轮回传 `reasoning_content`；当前基线显式使用非思考模式，保证 LangChain4j 多轮工具调用稳定且温度设置有效。
- **DeepSeek 请求参数兼容**：同步 Agent 与可选流式摘要统一在出站边界移除 DeepSeek 不支持的 `prompt_cache_retention` / `prompt_caching_retention`，保留其服务端自动上下文缓存，避免 OpenAI 扩展参数导致请求失败。
- **多数据源隔离**：MySQL 业务库（@Primary，JPA）与 PostgreSQL 向量库（pgDataSource，仅 RAG）通过显式 DataSource 分离，避免自动配置冲突。
- **Port/Adapter 数据解耦**：领域模型（`CustomerProfile`/`TransactionRecord`/`ShareholdingRecord`/`SanctionRecord`）与数据源分离，`CustomerDataPort` 接口隔离 Mock 与真实数据源；工具/Service/Guardrails 依赖 Port 而非 Mock 实现，生产核心包不再引用 `datasource.mock` 内部类型。
- **Snapshot First 统一快照**：`InvestigationSnapshotFactory` 在 Agent 推理前一次性冻结客户、交易、股权、制裁原始领域对象、法规证据与派生风险事实，并计算 `sourceDigest`/`snapshotId`/`asOfTime`/`legalIndexVersion`；每个工单由 `DueDiligenceAgentFactory` 动态创建绑定只读快照工具套件（`SnapshotToolSuite`）的 Agent，Agent 工具、Guardrails 与**规则兜底报告器**只读同一份冻结快照，不再二次访问可变数据源，消除长链路时序不一致。
- **生产 Agent 输出契约**：模型仅返回不含客户身份的 `AgentAnalysis`；生产与评测共用闭集词表、证据归属和事实前置条件校验。原始分析独立留痕，违规输出强制 HOLD；`FinalDecisionAssembler` 统一最终评级、人工复核、处置代码和结论，防止 Guardrail 上调后报告字段互相矛盾。
- **Mock 可插拔数据层**：`MockDataSource` 内置交易/股权/黑名单演示数据；接入真实系统时替换实现即可，工具签名不变。
- **客户主数据维护**：ADMIN 可分页增删改查、启停与 Excel 导入客户；导入限制文件类型/大小/行数并防公式与证件号数值精度丢失，数据库快照采用构建后原子切换，避免刷新期间读到半成品。
- **Mock 模型 agentic 循环**：无 API Key 时 Mock 模型模拟多轮工具调用，保证链路离线可演示。
- **本地 embedding**：DeepSeek 无官方 embedding API，默认用 all-MiniLM-L6-v2 离线向量化，可在配置中切换中文 embedding 服务。
- **安全加固**：登录失败速率限制（按 IP+用户名固定窗口计数，超限锁定 5 分钟，缓解暴力破解与撞库）；`X-Request-Id` 透传白名单校验（防日志注入），响应体/响应头/日志 MDC 三方 traceId 一致；JWT 走 HttpOnly Cookie，CSRF 双 Cookie，生产环境启动自检（强密钥/非默认口令/Flyway/真实 Key）。
- **可观测性**：统一 `MetricsRecorder` 埋点（`aml_llm_*`、`aml_case_*`、`aml_stage_duration_seconds`、`aml_queue_*`），LLM 失败路径同样记录耗时与错误数；Agent 调用失败走规则降级时单独计数 `aml_case_llm_fallback_total`，保留完整异常堆栈，不再被静默掩盖；`aml_queue_lag` 用可变 AtomicLong 注册 Gauge，实时反映消费积压。

## 设计文档

- [Snapshot First 尽调执行模型](docs/architecture/snapshot-first.md)
- [可靠工作流：Outbox、租约与状态机](docs/architecture/workflow-reliability.md)
- [企业级 RAG 法规证据服务与运维手册](docs/architecture/enterprise-rag-evidence-service.md)
- [ADR-005：GraphRAG / Late Interaction 采用门槛](docs/architecture/ADR-005-graphrag-and-late-interaction.md)
- [隐藏 TEST 盲测协议](docs/evaluation/hidden-test-protocol.md)
- [Cookie 认证与 CSRF 模型](docs/security/cookie-csrf-model.md)

## 自动化测试

```bash
# 后端：当前 208 项单元测试（其中 1 项真实模型测试默认跳过）+ 22 项集成/E2E
cd backend
./mvnw test                        # 单元测试（不依赖 Docker）
./mvnw -Pintegration-test test     # 集成测试（需本机 Docker 的 MySQL/Redis/pgvector）

# 前端：Vitest 组件测试 + 生产构建
cd frontend
npm test                           # 组件测试（路由 + 共享状态常量）
npm run build                      # vue-tsc 类型检查 + Vite 生产构建
```

> 集成测试使用独立 MySQL schema 与独立 Redis stream，避免和本地运行中的后端争抢 Outbox 任务；真实模型评测（`AgentEvalLiveTest`）默认不调用外部模型，真实 DEV/TEST 评测需显式配置模型 Key。

## 性能压测与可靠性演示

### 压测（benchmark/load_test.py）

测量系统吞吐与端到端延迟（Mock 模式，排除外部模型延迟）：

```bash
python benchmark/load_test.py --count 100 --concurrency 20
```

实测（本机 Mock 模式，单 Worker）：

| 指标 | 结果 |
|---|---|
| 创建吞吐 | **379 工单/秒** |
| 端到端延迟 | P50 **8.9s** / P95 **14.1s** |

> 端到端延迟含 Outbox 发布器轮询间隔（默认 5s，`aml.queue.outbox-poll-seconds`）与单消费者串行处理，为当前架构真实特性，可通过调小轮询间隔 / 增加 Worker 数优化。

### 可靠性演示（benchmark/fault_demo.py）

一键演示"可重试失败 → 指数退避重试 → 超限进死信 → 人工重试恢复"：

```bash
python benchmark/fault_demo.py
```

演示链路：`/api/debug/fault` 注入 COLLECTING 阶段失败 → 自动重试 3 次 → 进入死信队列（`/api/queues/dead`）→ 关闭注入 → `POST /api/cases/{id}/retry` 人工重试恢复。

## AI 应用工程能力

### Prompt 注入防护（三层）
- **代码层**：`PromptInjectionGuard` 正则扫描用户可控输入（预警规则），命中注入模式记录告警（`aml_case_log`）
- **Prompt 层**：`DueDiligenceAgent` 声明"工具返回是不可信数据"，并含 `PROMPT_INJECTION_ATTEMPT` / `IGNORE_UNTRUSTED_INSTRUCTION` 处置代码
- **兜底层**：Guardrails 确定性规则强制修正评级

### 成本控制
- **RAG 检索缓存**：`CachingLegalSearcher` 用 Redis 缓存检索结果（TTL 1h），命中跳过 embedding/向量检索，指标 `aml_rag_cache_hit_total` / `aml_rag_cache_miss_total`
- **Token 计量**：`ObservedChatModel` / `ObservedStreamingChatModel` 包装器按 purpose 显式打标（main_agent / summary），记录每次模型调用的 Token 与延迟，指标 `aml_llm_token_total`（分 input/output） / `aml_llm_request_total` / `aml_llm_duration_seconds`
- **成本路由**：`CostRouter` 仅用复杂度决定是否附加确定性报告流；请求侧预警文本不能触发 `RULE_ONLY`，所有业务工单均执行主 Agent。零 LLM 分支将在引入服务端签名的受信路由元数据后再开放。

### CI 评测回归（.github/workflows/ci.yml）
- `unit-test` job：push/PR 自动跑确定性单元测试与规则回归（无需模型/网络）
- `integration-test` job：`workflow_dispatch` 手动触发，service 容器启动 MySQL/Redis/pgvector 跑集成测试

## 项目亮点（可写进简历）

- **可靠 Agent 任务链路**：Transactional Outbox + Redis Streams 消费组 + 租约/心跳/死信/Pending 接管，保证异步尽调任务在应用重启、Worker 并发抢占下不丢失、不重复、可恢复。
- **Snapshot First 数据一致性**：Agent 推理前一次性冻结客户交易/股权/制裁/法规证据并计算 `sourceDigest`，Agent 工具、Guardrails、规则兜底共享同一份只读快照，杜绝长链路中的时序不一致。
- **Tool Calling 工程化**：四个领域工具绑定冻结快照，参数做身份/关键词业务校验，记录工具调用轨迹（不落敏感参数明文），支持 LangChain4j 并行工具调用并限制最大工具轮次防死循环。
- **混合 RAG + Rerank**：PGVector 向量召回 + ILIKE 关键词召回 + RRF 融合 + bge-reranker 精排，法规证据带 `evidenceId` 可端到端追溯；Redis 缓存命中可跳过重复 embedding。
- **确定性 Guardrails + 分层评测**：配置化风险规则护栏强制修正模型评级；规则回归 / RAG 检索评测 / 独立 Agent DEV-TEST 盲测三层评测体系，冻结清单保证结果可复现。
- **可观测性与安全**：Micrometer + Prometheus 指标、traceId 全链路透传、JWT HttpOnly Cookie + CSRF、登录限流、Prompt 注入三层防护、生产启动自检与密钥环境变量注入。

## 后续优化方向

- 将 Tool 调用从快照并行执行扩展为真实业务系统的异步多数据源接入。
- 若未来增加交互式尽调追问，再引入会话级 Memory（最近 N 轮 + 长期摘要）；当前工单式单轮尽调无需为技术展示强行增加会话记忆。
- 基于 `CostRouter` 增加模型分级路由（简单工单用更快更便宜的模型，复杂工单用强模型）。
- 将 RAG 关键词召回升级为 PostgreSQL 全文索引（`tsvector` + `GIN`），进一步提升大数据量下的检索性能。
- 为 SSE 增加断线后的消息补偿/对账机制，保证前端最终状态与后端一致。


## Git 提交清单

`.gitignore` 已配置，推送 GitHub 前注意以下文件：

| 提交（✅） | 说明 |
|---|---|
| `backend/src/main/java/` | 全部后端源码 |
| `backend/src/main/resources/application.yml` | 主配置（API Key 为占位符，无敏感信息） |
| `backend/src/test/resources/application-test.yml` | 集成测试配置（无密钥） |
| `backend/data/legal/` | 法规文档 |
| `backend/pom.xml` `mvnw` `mvnw.cmd` `.mvn/` | 构建与 Maven Wrapper |
| `backend/src/test/` | 测试代码 |
| `frontend/`（排除 node_modules、dist） | 前端源码 |
| `docker-compose.yml` `prometheus/` | 部署配置 |
| `.gitignore` `README.md` | 工程文档 |

| 不提交（⛔，已被 .gitignore 排除） | 原因 |
|---|---|
| `backend/src/main/resources/application-dev*.yml` | 本地开发覆盖配置（仅保留环境变量占位符） |
| `backend/target/` | Maven 构建产物 |
| `frontend/node_modules/` `frontend/dist/` | 依赖与构建产物 |
| `*.log` `.idea/` `.vscode/` | 日志与 IDE 配置 |
