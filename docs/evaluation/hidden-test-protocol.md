# 隐藏 TEST 盲测协议

## 目标

DEV 用于调优（可重复执行）；TEST 只在代码、Prompt、规则、数据全部冻结后运行一次，验证泛化能力，
避免针对测试集调优导致指标泄漏。

## 运行门控

- `/api/eval/agent/test` 需显式设置环境变量 `RUN_HIDDEN_AGENT_EVAL=true`，否则拒绝（普通 Web 页面无法运行）。
- 前端 EvalDashboard 不提供"运行 TEST"按钮，仅 DEV 可重复执行。
- TEST 报告只返回聚合指标（`aggregateOnly()`），不返回逐案例 `expectedRisk`/`requiredFindingCodes` 金标。

## 冻结要素

| 要素 | 记录方式 |
|---|---|
| 代码基线 | Git commit SHA |
| Prompt 版本 | `aml-dd-agent-v5-manual-review-consistency` |
| 数据集哈希 | `AgentEvalDatasetLoader.datasetHash()`（SHA-256） |
| 规则快照 | `risk_rule` 表 + Seeder |
| 模型 | provider / model / temperature |

## 正式运行流程

1. 确定性测试 + 工作流集成测试 + 前端构建全部通过；
2. Git 工作区清洁，记录 commit；
3. 设置 `RUN_HIDDEN_AGENT_EVAL=true` 与真实模型 Key；
4. 运行一次 TEST，保存聚合报告与脱敏审计报告；
5. README 只写真实结果，不挑选最好的一次。

## 结果解释规则

- TEST 低于 DEV 属正常，不修改报告掩盖；
- TEST 失败只做误差归因，不立即调 v5；如需改 Prompt，宣布 v5 结束并建立新版本数据切分；
- 数据集标签保持 `PENDING_DOMAIN_REVIEW`，不宣称生产准确率。
