# 商业银行智能反洗钱（AML）与高风险客户尽调 Agent 平台

一个基于 **Java 21 + Spring Boot 3 + LangChain4j** 的企业级反洗钱尽调 Agent 平台：
接收反洗钱系统预警工单后，可靠地调度 Agent 工作流，自动完成交易画像、股权穿透、制裁名单筛查、
监管法规检索、风险研判和结构化报告生成；使用独立于大模型的 Guardrails 规则护栏校验最终结论，
并将高风险工单转入**人工复核闭环**。具备**可评测、可追溯、可恢复、可观测、安全可控**的企业级能力：
JWT 三角色认证、Outbox+Redis Streams 可靠任务、混合 RAG 证据追溯、配置化规则护栏、固定测试集评测、
Prometheus/Grafana 监控与自动化测试。

## 核心工作流

```
预警工单触发
  → Transactional Outbox + Redis Streams 可靠任务队列（幂等 / 重试 / 死信 / 崩溃恢复）
  → 任务规划 Planner      拆解子任务（交易画像 / 股权穿透 / 黑名单 / 法规匹配）
  → 工具调用 Tool Engine  并行调用四类数据工具（LangChain4j @Tool）
  → 混合 RAG 法规比对     向量 + 关键词召回 + RRF 融合，证据 ID 可追溯
  → 深度风险推理          模型综合研判，输出风险点与评级
  → Guardrails 规则护栏   配置化规则强制修正（一级制裁 → 高风险 + 转人工 HOLD）
  → 结构化报告            含证据链与法规证据 ID，实时 SSE 推送到前端
  → Agent / 规则 / RAG评测 独立案例夹具运行真实模型，原始结果与 Guardrails 分开计分
```

## 当前验证结果

| 验证项目 | 结果 | 数据性质 |
|---|---|---|
| 风险规则回归 | 100 条独立期望的合成边界案例 | 覆盖合法跨境/夜间负例、交易模式、UBO、数据缺失和制裁；不调用 LLM |
| 一级制裁规则漏报 | **0 / 5** | 合成规则案例 |
| RAG 法规检索评测（35 条） | Recall@5 **94.3%**、Top3 命中率 **94.3%**、MRR **94.3** | 从法规库自动生成的同源问题集；混合召回 + bge-reranker 精排 |
| 检索耗时 | P95 **20ms**（rerank 前召回） | 本地环境实测 |
| 独立 Agent 案例集 | 15 条（DEV 9 / TEST 6） | AI 辅助人工整理的合成案例，待领域专家复核 |
| DeepSeek 真实 Agent DEV（v2 → v5） | 原始风险准确率 **44.4% → 100%**；Guardrails 后 **77.8% → 100%**；高风险召回率 **40% → 100%**；无效输出 **2/9 → 0/9** | 9 条冻结合成 DEV（`PENDING_DOMAIN_REVIEW`）；2026-08-12/13 本地实测 |
| v5 工具与证据覆盖 | 必需工具召回率 **100%**；法规 evidenceId 召回率 **100%**；端到端任务通过率 **66.7%**；strictPass **0**（5 次重复调用） | 详见 `DeepSeek真实Agent评测报告-二轮对比.md` |
| 首轮工具与证据覆盖（v2） | 必需工具召回率 **94.4%**；法规 evidenceId 召回率 **77.8%** | 失败集中在隐藏法规关键词导致的无效重试，已通过 v5 工具契约修复 |

> 规则回归结果只用于验证 Guardrails 和风险规则，不代表大模型准确率。Agent 数字来自 DeepSeek 对 9 条合成 DEV 的迭代基线，v5 指标为调优集结果（可能过拟合），需以冻结的隐藏 TEST 分片验证泛化能力；数据集标签仍待领域专家复核（`PENDING_DOMAIN_REVIEW`），因此不等同生产准确率。当前 RAG 指标来自同源自动生成问题集，后续将使用独立人工问题集复测。

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
| 代码基线 | `master / 18a2bbc`（本地分支超前 origin/master 若干提交） |
| Prompt 版本 | `aml-dd-agent-v5-manual-review-consistency` |
| 模型 | `deepseek-v4-flash`（多提供商可切换） |
| 数据集 | `agent-cases-v1.json`（15 条：DEV 9 / TEST 6，`PENDING_DOMAIN_REVIEW`） |
| 数据集哈希 | 由 `AgentEvalDatasetLoader` 启动时计算（SHA-256，TEST 冻结审计） |
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
        model-name: deepseek-chat
```

| 提供商 | type | base-url | model-name |
|---|---|---|---|
| DeepSeek | openai-compatible | https://api.deepseek.com | deepseek-chat |
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
    evaluation/           规则回归、独立 Agent 案例集、RAG 评测与评测报告
    agent/                AiServices Agent、报告 DTO、Guardrails（规则驱动）
    tools/                四个 @Tool（交易/股权/黑名单/法规）
    rag/                  法规导入（evidenceId）、混合检索（向量+关键词+RRF）
    datasource/           Mock 数据层 + JPA 实体/仓库
    service/              工作流编排、规则兜底报告、SSE 推送
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
| POST | /api/eval/rag | RAG 检索评测（Recall@5/Top3/MRR/P95，ADMIN） |
| GET | /api/eval/agent/status | 真实 Agent 评测就绪状态及数据集概览（ADMIN） |
| POST | /api/eval/agent/dev | 运行真实模型 DEV 评测；Mock/fallback 直接标记无效（ADMIN） |
| POST | /api/eval/agent/test | 运行冻结的隐藏 TEST 分片（最终评测，标准答案冻结，ADMIN） |
| GET | /api/eval/agent/dataset | 独立 Agent 案例集元信息（含 datasetHash），不返回 TEST 标准答案（ADMIN） |
| GET | /api/eval/reports?evalType=RULE_REGRESSION\|AGENT_DEV\|AGENT_TEST | 按类型查询历史评测报告（ADMIN） |
| GET | /api/reviews/pending | 待复核队列（REVIEWER/ADMIN） |
| POST | /api/reviews/{id} | 提交复核决定（APPROVE/REJECT/ESCALATE，REVIEWER/ADMIN） |
| GET | /api/reviews/{id} | 工单复核记录 |
| GET | /api/reviews/stats | 复核反馈统计（一致率等） |
| GET | /api/queues/dead | 死信队列查看（ADMIN） |
| POST | /api/queues/dead/{caseId}/replay | 死信重放（ADMIN） |
| GET | /actuator/prometheus | Prometheus 指标（放行） |
| GET | /swagger-ui.html | OpenAPI 文档（放行） |
| GET | /api/cases/customers | 演示客户（脱敏，不含证件号） |
| GET | /api/agent/ping | LLM 连通性验证 |

## 关键设计

- **可靠异步任务**：Transactional Outbox（工单与事件同事务，`caseId:eventType:executionVersion` 幂等键防重复发布）→ Redis Streams 消费组 → 条件更新抢占（`executionVersion`）→ 版本化租约 + Worker 心跳（心跳/完成/失败均绑定 worker+version，防旧 Worker 污染新执行版本）→ 指数退避重试（RETRY_WAIT）→ 死信队列 → Pending 超时接管（服务重启任务不丢失）。重试、接管、死信重放统一走 Outbox，消除数据库提交与 Redis 投递之间的双写丢失窗口。
- **Guardrails 配置化**：`risk_rule` 表驱动（DSL 条件表达式 + 优先级 + 生效时间），决策可解释（ruleCode / version / evidence / 动作），一级制裁命中零漏报并强制转人工。
- **RAG 证据追溯**：法规片段带唯一 `evidenceId`，向量 + 关键词 + RRF 混合召回，报告引用可回溯到具体条款原文。
- **召回 + 精排两步走**：混合召回 top-20 候选，本地 bge-reranker（Cross-Encoder）精排 top-3，Recall@5 由 82.9% 提升至 94.3%。
- **分层评测**：固定种子生成 100 条规则回归输入，各场景期望结果显式定义且基线固定为低风险，覆盖困难负例并隔离验证 Guardrails 升级行为；RAG 同源问题集仅用于检索回归。真实 Agent 指标来自独立 DEV 夹具，避免将规则结果误标为模型能力。
- **独立 Agent 案例集**：首版 15 条版本化合成案例与规则代码完全分离，覆盖正常交易、跨境夜间、拆分交易、复杂 UBO、名单精确/误命中、数据缺失和提示注入；DEV/TEST 分片独立，TEST 标准答案不经接口暴露。当前标签状态为 `PENDING_DOMAIN_REVIEW`，不宣称专家金标。
- **真实 Agent DEV 评测**：每例动态创建独立 AiServices 与冻结工具夹具，校验客户 ID / 姓名 / 证件号及法规查询主题并记录并发调用轨迹；同时保留原始模型契约通过率与 Guardrails 后端到端任务通过率，报告风险准确率、高风险召回、人工升级、结构化代码覆盖、法规 evidenceId 引用、工具精度、禁错检测、P50/P95 与 Token。模型异常进入严格分母，Mock 或 fallback 不计质量指标；持久化副本对风险、代码、工具名使用闭集白名单并移除模型原文、身份和查询参数。
- **DeepSeek 工具调用兼容**：V4 默认 thinking 模式要求多轮回传 `reasoning_content`；当前基线显式使用非思考模式，保证 LangChain4j 多轮工具调用稳定且温度设置有效。
- **多数据源隔离**：MySQL 业务库（@Primary，JPA）与 PostgreSQL 向量库（pgDataSource，仅 RAG）通过显式 DataSource 分离，避免自动配置冲突。
- **Port/Adapter 数据解耦**：领域模型（`CustomerProfile`/`TransactionRecord`/`ShareholdingRecord`/`SanctionRecord`）与数据源分离，`CustomerDataPort` 接口隔离 Mock 与真实数据源；工具/Service/Guardrails 依赖 Port 而非 Mock 实现，生产核心包不再引用 `datasource.mock` 内部类型。
- **统一尽调快照**：`InvestigationSnapshot` 在 Agent 推理前一次性冻结风险事实（含 `snapshotId`/`asOfTime`/来源版本），Agent 推理与 Guardrails 校验共享同一份数据，避免数据源在两者之间变化导致的不一致。
- **Mock 可插拔数据层**：`MockDataSource` 内置交易/股权/黑名单演示数据；接入真实系统时替换实现即可，工具签名不变。
- **Mock 模型 agentic 循环**：无 API Key 时 Mock 模型模拟多轮工具调用，保证链路离线可演示。
- **本地 embedding**：DeepSeek 无官方 embedding API，默认用 all-MiniLM-L6-v2 离线向量化，可在配置中切换中文 embedding 服务。

## 自动化测试

```bash
cd backend
./mvnw test                        # 单元测试
./mvnw test -Dgroups=integration   # 集成测试（复用本机 Docker 服务）
```

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
- **Token 计量**：`ChatModelTokenListener` 记录每次模型调用的 Token 数，指标 `aml_llm_token_total` / `aml_llm_request_total`
- **成本路由**：`CostRouter` 按预警规则复杂度分级（SIMPLE/COMPLEX），`aml.cost-routing.rule-fallback-enabled=true` 时 SIMPLE 工单跳过 LLM 直接规则引擎（零模型成本，默认关闭不影响评测）

### CI 评测回归（.github/workflows/ci.yml）
- `unit-test` job：push/PR 自动跑确定性单元测试与规则回归（无需模型/网络）
- `integration-test` job：`workflow_dispatch` 手动触发，service 容器启动 MySQL/Redis/pgvector 跑集成测试

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
