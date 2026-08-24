# 隐藏 TEST 盲测协议

## 目标

DEV 用于调优（可重复执行）；仓库内 6 条 `DEMO_TEST` 只用于演示测试治理流程，不是专家金标。
正式 TEST 由非开发者领域专家在仓库外独立标注，只在代码、Prompt、规则、数据全部冻结后运行一次，验证泛化能力，
避免针对测试集调优导致指标泄漏。

## 运行门控

- `/api/eval/agent/test` 需显式设置环境变量 `RUN_HIDDEN_AGENT_EVAL=true`，否则拒绝（普通 Web 页面无法运行）。
- 必须通过 `AML_HIDDEN_TEST_PATH` 加载外部数据集；数据集和每条案例的 `reviewStatus` 都必须为
  `DOMAIN_EXPERT_APPROVED`，否则应用启动即失败。
- 前端 EvalDashboard 不提供"运行 TEST"按钮，仅 DEV 可重复执行。
- TEST 报告只返回聚合指标（`aggregateOnly()`），不返回逐案例 `expectedRisk`/`requiredFindingCodes` 金标。

## 冻结要素

| 要素 | 记录方式 |
|---|---|
| 代码基线 | Git commit SHA |
| Prompt 版本 | `aml-dd-agent-v7-production-contract-final-decision` |
| 数据集哈希 | `AgentEvalDatasetLoader.datasetHash()`（内置数据与外部 TEST 的组合 SHA-256） |
| 规则快照 | `risk_rule` 表 + Seeder |
| 模型 | provider / model / temperature |

## 正式运行流程

1. 确定性测试 + 工作流集成测试 + 前端构建全部通过；
2. Git 工作区清洁，记录 commit；
3. 由两名领域专家独立标注、第三人裁决争议，并由审核人将外部数据集状态改为 `DOMAIN_EXPERT_APPROVED`；
4. 设置 `AML_HIDDEN_TEST_PATH`、`RUN_HIDDEN_AGENT_EVAL=true`、`BUILD_GIT_SHA` 与真实模型 Key；
5. 运行一次 TEST，保存聚合报告与脱敏审计报告；
6. README 只写真实结果，不挑选最好的一次。

## 结果解释规则

- TEST 低于 DEV 属正常，不修改报告掩盖；
- TEST 失败只做误差归因，不立即调 v5；如需改 Prompt，宣布 v5 结束并建立新版本数据切分；
- `DOMAIN_EXPERT_APPROVED` 仅表示标注流程完成，仍不等同于真实银行生产准确率。
