# ExplanationWorkspaceService 拆分：实测边界与执行顺序

> 制定日期：2026-09-18
> 状态：**尚未执行**——本文只把抽取边界测清楚，供后续按步执行

## 为什么要先测再拆

`ExplanationWorkspaceService` 现在 **2536 行**、**28 个公开方法**、
**25 个构造注入的协作者**、24 个私有静态 helper。

原先的记录里写的是 1324 行——那已经过期了。这个规模本身就是问题：
任何一次改动都要在两百多行的方法里找位置，而"这个方法到底依赖哪些协作者"
在阅读时完全看不出来。

**但不要一次性重写。** 下面每一块都可以独立抽取、独立验证；
分步做，每一步都有现成的测试网兜着。

## 前置：先把静态 helper 提到共享工具类

`text` / `text(默认值)` / `stringList` / `parseEnum` / `dedupe` 是私有静态方法，
被服务内**大量**位置使用，而待抽取的几块也都用到它们。

因此第一步不是抽业务，而是：

```
新增 ExplanationJson（package-private 工具类）
  ├─ text(JsonNode, String)
  ├─ text(JsonNode, String, String)
  ├─ stringList(JsonNode, String)
  ├─ parseEnum(Class<E>, String, String)
  └─ dedupe(List<String>)
```

服务里的私有实现删掉、改为调用它。这一步**零行为变化**，
`ExplanationWorkspaceServiceTest` 与集成用例足以覆盖。
先做它，后面每一块抽取都不必再拖动这些 helper。

## 待抽取的单元（按"好做 → 难做"排序）

### ① 事实字典（最容易，零依赖）

| 项 | 值 |
|---|---|
| 位置 | `factQuestion` / `factSuggestedSource` / `factSuggestedAction` / `factAlternative`（约 992–1027） |
| 规模 | ~40 行 |
| 依赖 | 无（纯静态查表） |
| 目标 | `ExplanationFactCatalog` |

价值不大但零风险，适合作为"抽取流程本身"的第一次彩排。

### ② 就绪度评估

| 项 | 值 |
|---|---|
| 位置 | `evaluateReadiness` 两个重载（1324–1434）+ `UnitAssessmentRow` |
| 规模 | ~110 行 |
| 依赖 | **实测**：`issueRepository`、`eddRepository`、`ExplanationDecisionRules` |
| 目标 | `ExplanationReadinessEvaluator` |

判定逻辑本身已经抽进 `ExplanationDecisionRules`（纯函数），
这里剩下的是"把数据取出来喂给它"——边界清楚。

### ③ 草稿校验（最大的一块）

| 项 | 值 |
|---|---|
| 位置 | `validateDraftContent`（1704–1959，256 行）+ `validateGroupPaymentClaims`（1449–1587，139 行）+ `DraftSummary` + `SCOPE_ISSUE_KEY` |
| 规模 | ~420 行 |
| 依赖 | **实测**：`validateDraftContent` → `issueRepository`、`artifactRepository`、`policyCatalog`、`admissibilityService`、`alertScopeService`、`clock`；`validateGroupPaymentClaims` → `claimService`、`paymentAuthorityFactService`、`clock` |
| 目标 | `ExplanationDraftValidator` |

这一块抽完，服务的协作者从 25 降到 **18**——是三块里收益最大的。
代价是引入一个 8 依赖的类，所以**必须放在 ①② 之后**：
先证明抽取流程本身没问题，再动最大的一块。

### ④ 范围与覆盖

| 项 | 值 |
|---|---|
| 位置 | `validateObligationCoverage`（1128–1243）、`deriveObligationKeys`（1244–1261）、`applyCoverageFromSubmission`（1960–1987）、`resetCoverageToPending` |
| 规模 | ~230 行 |
| 依赖 | **实测**：`validateObligationCoverage` 用到 `clock`、`eddRepository`、`caseRepository`、`submissionRepository` |

与 ② 共享一部分判定，且 `eddRepository` 在两处都出现，
建议在 ② 做完之后再评估两者是否应合并成一个"就绪度与覆盖"组件。

### ⑤ 代付缺口登记

| 项 | 值 |
|---|---|
| 位置 | `registerGroupPaymentGaps`（1605–1703） |
| 规模 | ~100 行 |
| 依赖 | **实测**：`submissionRepository`、`objectMapper` |

与 ③ 同源（都围绕集团代付草稿），可在 ③ 里一并处理。

## 每一步的验收标准

沿用计划里的原话，逐条可查：

- Controller 接口**不变**（`ExplanationController` 的签名与路径一个都不动）；
- 现有测试全部通过（`ExplanationWorkspaceServiceTest` + 集成用例）；
- 为提取出的组件**补充直接单测**——否则它只是换了个地方，没有被单独验证过；
- `git diff` 能证明这是**职责移动**而不是业务语义重写：
  方法体应当逐行可对应，不允许"顺手改个判断条件"；
- 抽完之后，没有任何一个类同时直接依赖大量 Repository、决策规则、审计与 DTO 装配器。

## 顺序

```
前置（ExplanationJson） → ① 事实字典 → ② 就绪度 → ③⑤ 草稿校验与代付缺口 → ④ 范围与覆盖
```

每一步单独提交、单独跑测试。任何一步让测试变红且十分钟内说不清原因，
就退回上一步——这类重构的价值全在"可回退"，而不是"一次做完"。
