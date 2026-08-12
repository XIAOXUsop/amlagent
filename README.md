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
  → Agent / RAG 评测      固定测试集量化召回率 / 准确率 / 延迟
```

## 真实评测指标（P3 实测）

| 评测项 | 结果 |
|---|---|
| Agent 风险评测（100 条固定案例） | 高风险召回率 **100%**、准确率 **100%**、低风险误报率 **0%** |
| 一级制裁名单漏报 | **0 / 5**（零漏报） |
| 风险决策耗时 | P50 **2ms** / P95 **3ms** |
| RAG 法规检索评测（35 条） | Recall@5 **82.9%**、Top3 命中率 **71.4%**、MRR **64.6** |
| 检索耗时 | P95 **20ms** |
| 结构化输出 / 工具调用成功率 | 100% |

> 指标来自 `POST /api/eval/run` 与 `POST /api/eval/rag` 的真实评测输出（Mock 确定性模式，可复现）。

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
> 真实 API Key 放在 `application-dev.yml`（已被 `.gitignore` 排除，不提交 GitHub）。

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
    evaluation/           案例集生成、Agent/RAG 评测器、评测报告
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

> 除登录与监控端点外，其余接口需携带 `Authorization: Bearer <token>`；SSE 用 `?token=`。

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
| POST | /api/eval/run | Agent 风险评测（高风险召回/误报率/混淆矩阵/一级制裁漏报，ADMIN） |
| POST | /api/eval/rag | RAG 检索评测（Recall@5/Top3/MRR/P95，ADMIN） |
| GET | /api/eval/reports | 历史评测报告（ADMIN） |
| GET | /api/reviews/pending | 待复核队列（REVIEWER/ADMIN） |
| POST | /api/reviews/{id} | 提交复核决定（APPROVE/REJECT/ESCALATE，REVIEWER/ADMIN） |
| GET | /api/reviews/{id} | 工单复核记录 |
| GET | /api/reviews/stats | 复核反馈统计（一致率等） |
| GET | /api/queues/dead | 死信队列查看 |
| GET | /actuator/prometheus | Prometheus 指标（放行） |
| GET | /swagger-ui.html | OpenAPI 文档（放行） |
| GET | /api/cases/customers | 演示客户 |
| GET | /api/agent/ping | LLM 连通性验证 |

## 关键设计

- **可靠异步任务**：Transactional Outbox（工单与事件同事务）→ Redis Streams 消费组 → 条件更新抢占实现业务幂等（`executionVersion`）→ 指数退避重试 → 死信队列 → Pending 超时接管（服务重启任务不丢失）。
- **Guardrails 配置化**：`risk_rule` 表驱动（DSL 条件表达式 + 优先级 + 生效时间），决策可解释（ruleCode / version / evidence / 动作），一级制裁命中零漏报并强制转人工。
- **RAG 证据追溯**：法规片段带唯一 `evidenceId`，向量 + 关键词 + RRF 混合召回，报告引用可回溯到具体条款原文。
- **固定评测集**：程序化生成 100 条 Agent 案例与 35 条 RAG 检索问题，一键输出准确率/召回率/混淆矩阵/延迟，支持版本对比。
- **多数据源隔离**：MySQL 业务库（@Primary，JPA）与 PostgreSQL 向量库（pgDataSource，仅 RAG）通过显式 DataSource 分离，避免自动配置冲突。
- **Mock 可插拔数据层**：`MockDataSource` 内置交易/股权/黑名单演示数据；接入真实系统时替换实现即可，工具签名不变。
- **Mock 模型 agentic 循环**：无 API Key 时 Mock 模型模拟多轮工具调用，保证链路离线可演示。
- **本地 embedding**：DeepSeek 无官方 embedding API，默认用 all-MiniLM-L6-v2 离线向量化，可在配置中切换中文 embedding 服务。

## 自动化测试

```bash
cd backend
./mvnw test                        # 单元测试（19 个）
./mvnw test -Dgroups=integration   # 集成测试（复用本机 Docker 服务）
```

## Git 提交清单

`.gitignore` 已配置，推送 GitHub 前注意以下文件：

| 提交（✅） | 说明 |
|---|---|
| `backend/src/main/java/` | 全部后端源码 |
| `backend/src/main/resources/application.yml` | 主配置（API Key 为占位符，无敏感信息） |
| `backend/src/main/resources/application-prod/` `application-test.yml` | 测试配置（无密钥） |
| `backend/data/legal/` | 法规文档 |
| `backend/pom.xml` `mvnw` `mvnw.cmd` `.mvn/` | 构建与 Maven Wrapper |
| `backend/src/test/` | 测试代码 |
| `frontend/`（排除 node_modules、dist） | 前端源码 |
| `docker-compose.yml` `prometheus/` | 部署配置 |
| `.gitignore` `README.md` | 工程文档 |

| 不提交（⛔，已被 .gitignore 排除） | 原因 |
|---|---|
| `backend/src/main/resources/application-dev*.yml` | 含真实 DeepSeek API Key |
| `backend/target/` | Maven 构建产物 |
| `frontend/node_modules/` `frontend/dist/` | 依赖与构建产物 |
| `*.log` `.idea/` `.vscode/` | 日志与 IDE 配置 |
