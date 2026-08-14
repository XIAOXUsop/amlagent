# Snapshot First 尽调执行模型

## 目标

一次工单执行中，Agent 推理、工具数据采集与 Guardrails 校验共享同一份冻结业务事实，
消除"Agent 读 T1 时刻数据、Guardrails 读 T2 时刻数据"的时序不一致。

## 核心组件

| 组件 | 职责 |
|---|---|
| `InvestigationSnapshot` | 不可变快照：客户、交易、股权、制裁原始领域对象 + `RiskContext` + `sourceDigest`/`legalIndexVersion`/`asOfTime` |
| `InvestigationSnapshotFactory` | Agent 推理前一次性读取数据源并冻结快照，计算 SHA-256 `sourceDigest` |
| `SnapshotToolSuite` | 四个 `@Tool` 只读快照（交易/股权/制裁），不访问 `CustomerDataPort`；法规检索读 RAG 索引 |
| `DueDiligenceAgentFactory` | 按工单动态创建绑定快照工具的 AiServices Agent |
| `RiskFactAssembler.assembleFrom` | 纯函数，从冻结原始数据派生 `RiskContext` |

## 执行流程

```
CustomerDataPort / Legal Index
        ↓
InvestigationSnapshotFactory（冻结 customer/transactions/shareholdings/sanctions + riskFacts + sourceDigest）
        ↓
SnapshotToolSuite（只读快照）
        ↓
DueDiligenceAgentFactory 创建本工单 Agent → 推理
        ↓
GuardrailEngine.apply(snapshot, report)（同一快照）
```

## 关键不变量

1. 快照在 Agent 调用**之前**创建。
2. Agent 工具与 Guardrails 不再二次访问可变业务数据。
3. `sourceDigest` 由冻结领域对象确定性 SHA-256，证明 Agent 与 Guardrails 同源。
4. 不同 `executionVersion` 不得复用旧快照。

## 验证

`backend/src/test/java/com/bank/aml/agent/InvestigationSnapshotTest.java`：
- 快照创建后修改数据源，工具仍返回快照旧值（`verify transactionsOf` 仅调用一次）
- 相同事实产生稳定 `sourceDigest`
- 不同 `executionVersion` 产生不同 `snapshotId`
