# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Vue 3 + Vite + Element Plus（按需引入）+ Vue Router + Vitest + Playwright；后端 Spring Boot 3 / LangChain4j / MySQL / Redis / PGVector。

## Users

商业银行反洗钱（AML）领域的三类操作者，演示环境中各有一个内置账号：

- **ANALYST**（分析员）：创建预警工单、触发尽调、查看 Agent 报告并回传补充尽调材料索引。
- **REVIEWER**（复核员）：对 Agent 结论做人工处置（确认可疑 / 合理排除 / 发起补充尽调），并完成可疑交易报告登记与退回补正。
- **ADMIN**（管理员）：评测体系、规则回归、RAG 质量与冻结评测、队列运维。

> 推断标注：该项目用于秋招简历 / 面试演示，无真实银行接入；角色与流程来自任务书与设计文档。

## Product Purpose

用 AI Agent 完成高风险客户的智能尽调：自动采集交易、股权、制裁、法规证据，生成结构化风险报告，并交给人工复核形成可审计的合规闭环。同时内置评测体系证明模型的 RAG 检索质量、规则一致性与 Agent 工单效果。

## Positioning

AI 尽调 Agent + 规则护栏 + 人工复核 + 冻结评测的可审计闭环：快照不可变、消息可靠（Outbox/死信/租约 fencing）、评测基线冻结（freezeId 一次性）、Cookie CSRF 安全。演示价值在于"工程能力可感知"：溯源、版本、指标、安全控制都是可见的产品资产。

## Operating Context

本地演示 / 面试演示环境。后端（8080）+ 前端 Vite（5173）+ Docker 依赖（MySQL/Redis/PGVector）。演示客户 C001/C002/C003（张伟/王强/李娜）。核心链路：登录 → 创建工单 → Agent 工作流（规划→采集→推理→护栏→报告）→ 风险评级 → HOLD 转人工复核 → 合理排除；或发起补充尽调 → 分析员回传结构化证据 → 再复核；确认可疑则进入待报送 → 登记外部受理编号 → 结案/退回补正。

## Capabilities and Constraints

- 三角色权限（ANALYST/REVIEWER/ADMIN），前端路由守卫 + 后端 `@PreAuthorize` 双层。
- 人工复核使用业务处置语义（确认可疑 / 排除预警 / 补充尽调），结论绑定原因码与分析记录；补充尽调形成带材料清单、实名分派、截止时间、逾期标识和多轮状态的可追踪任务，证据元数据必须完整覆盖所需材料。
- Cookie 认证（HttpOnly JWT）+ Cookie CSRF（XSRF-TOKEN）。
- 工单状态机：PENDING/RUNNING/DONE/HOLD/REPORT_PENDING/FAILED/RETRY_WAIT；确认可疑先进入 REPORT_PENDING，外部报送受理后才进入 DONE，非法迁移返回 409。
- Agent 工作流：快照（Snapshot First）、工具轨迹（脱敏）、规则护栏、法规 RAG 检索。
- 可解释制裁筛查：数据库只负责候选召回，再按证件号、姓名别名、相似度和主体类型输出分值、原因与处置结论；REVIEWER/ADMIN 可追加确认、排除或补充材料决定，revision 防止并发覆盖。
- 调查档案交付：工单详情可导出包含报告、快照元数据、工作流、工具轨迹、人工复核历史、补充尽调分派/证据/撤销信息和可疑报告状态的 JSON 档案，并附 SHA-256 内容摘要；关键业务字段损坏时拒绝生成不完整档案。
- 高影响审计采用事务内 Outbox + 幂等投递，审计登记失败时业务操作同步回滚。
- 风险优先运营队列使用版本化确定性策略，将预警场景、多预警聚合、护栏评级和待报送状态转换为可解释优先级；调查、人工复核、补充尽调、报告报送分别计算 SLA，并按当前角色隔离责任队列。
- 评测体系：规则回归、RAG 检索质量（Recall@5/MRR/P95）、Agent DEV/TEST（freezeId 一次性冻结）。
- 可靠消息：事务性 Outbox + Redis Streams + 死信 + 租约 fencing + 幂等。
- 前端文案为中文；演示账号 admin/admin123、reviewer/reviewer123、analyst/analyst123。

## Brand Commitments

- 名称：AML 智能反洗钱尽调 Agent 平台（保留）。
- 视觉方向（用户确认，binding）：**视觉重构为"监管 / 金融安全 + AI 工程展示"的深色科技感面板**；品牌强调色用金 / 深红 / 翡翠绿区分风险等级；突出评测指标、溯源 / 版本可视化和 Agent 工作流；适合简历与面试演示。

## Evidence on Hand

- 演示数据：Mock 客户、交易 / 股权 / 制裁事实、法规文档（`backend/data/legal/*.md`）、评测数据集（DEV/TEST 分片）。
- 现有前端：登录、工单中心 / 详情、人工复核、评测中心四个页面，Element Plus 默认蓝白风格（将作为反参考替换）。
- 现有能力有单元 / 集成 / E2E 测试支撑（Playwright 7 用例通过）。

## Product Principles

1. **工程能力可感知**：评测指标、快照溯源、模型 / 法规版本、安全控制都以可见的产品资产呈现，而非藏在后台。
2. **风险分级清晰**：金 / 深红 / 翡翠绿对应高 / 升级 / 低风险，状态与评级一眼可辨。
3. **可审计闭环**：AI 结论与人工决定分层展示，来源（Agent / 规则降级）、版本、证据链都可追溯。
4. **演示即真实**：深色面板与数据可视化服务于"讲得清、看得懂"，面试时无需额外解释。

## Accessibility & Inclusion

未建立产品级无障碍标准；重构时保持文本对比度 ≥ WCAG AA、不依赖颜色作为唯一传达手段。
