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

## 前置：静态 helper ✅ 已完成

`ExplanationJson`（`text` / `bool` / `stringList` / `intList` / `parseEnum` / `dedupe`）
已经从服务里提出去，服务 2536 → 2493 行，调用点靠静态导入保持不动。
零行为变化，549 项单元测试通过。

下面是当时记录的原始计划。

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

### ① 事实字典 ✅ 已完成

| 项 | 值 |
|---|---|
| 位置 | `factQuestion` / `factSuggestedSource` / `factSuggestedAction` / `factAlternative`（约 992–1027） |
| 规模 | ~40 行 |
| 依赖 | 无（纯静态查表） |
| 目标 | `ExplanationFactCatalog` |

已抽成 `ExplanationFactCatalog`（四个方法、零依赖）。服务 2493 行。

### ② 就绪度评估

| 项 | 值 |
|---|---|
| 位置 | `evaluateReadiness` 两个重载（1324–1434）+ `UnitAssessmentRow` |
| 规模 | ~110 行 |
| 依赖 | **实测**：`issueRepository`、`eddRepository`、`ExplanationDecisionRules` |
| 目标 | `ExplanationReadinessEvaluator` |

判定逻辑本身已经抽进 `ExplanationDecisionRules`（纯函数），
这里剩下的是"把数据取出来喂给它"——边界清楚。

### ③ 草稿校验（最大的一块，**但实测下来不建议整体抽**）

| 项 | 值 |
|---|---|
| 位置 | `validateDraftContent`（1711–1966，256 行）+ `validateGroupPaymentClaims`（1456–1594，139 行）+ `DraftSummary` + `SCOPE_ISSUE_KEY` |
| 规模 | ~400 行 |
| 表面依赖 | **实测**：`issueRepository`、`artifactRepository`、`policyCatalog`、`admissibilityService`、`alertScopeService`、`claimService`、`paymentAuthorityFactService`、`clock` |
| **连带依赖** | 这两个方法还调用 6 个服务私有方法：`parseDateOrNull`(17 行)、`ensureIssue`(18)、`inputDigestOf`(23)、`serverTransactionAmounts`(18)、`serverTransactionDates`(18)、`requireAmount`(12)——它们自己又用到 `objectMapper` 与 `customerDataPort` |
| **合计** | **10 个协作者、约 500 行** |

**结论：不要把它整体抽成一个类。** 那样只是把"25 个依赖的类"换成
"10 个依赖的类"——问题换了个地方住，没有消失。

如果要做，正确的切法是先切出一条更细的线：

```
ExplanationServerFacts（1 个协作者：customerDataPort）
  ├─ serverTransactionAmounts
  ├─ serverTransactionDates
  └─ requireAmount / parseDateOrNull
```

把这条线切出来之后，草稿校验剩下的部分才降到 9 个协作者左右——
那时再决定值不值得抽。**在切出这条线之前，这一块保持原样。**

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
[已完成] 前置（ExplanationJson）
[已完成] ① 事实字典（ExplanationFactCatalog，零依赖）
[已完成] ③a 权威交易事实（ExplanationServerFacts，1 个协作者）
  → ② 就绪度
  → ③b 草稿校验（③a 之后才评估）
  → ④ 范围与覆盖
```

每一步单独提交、单独跑测试。任何一步让测试变红且十分钟内说不清原因，
就退回上一步——这类重构的价值全在"可回退"，而不是"一次做完"。

## 最重要的一个发现：按职责抽，依赖数降不下来

抽完前置与 ①② 之后回头测了一下：服务里 **26 个协作者，每一个都被引用至少 4 次**。

这意味着**按职责逐个抽取永远不会减少声明的依赖数**——抽走一块，
那些 Repository 仍然被其余方法用着，声明照旧。抽完 ③ 也一样：
它用到的 8 个协作者里，没有一个只服务于它。

而且 ② 就绪度评估实测是 **5 个协作者、162 行**（116 行的主体 + 4 个被牵连的私有方法：
`linkedAlertCount` / `unitId` / `contributorsOf` / `unitView`）。
和 ③ 是同一个形状。

所以计划里那条验收标准——"不再有一个类同时直接依赖大量 Repository、决策规则、
审计和 DTO 装配器"——**靠渐进抽取达不到**。要真的达到，只有两条路：

1. **按数据源切**，而不是按职责切：把"所有用 customerDataPort 的地方"一次性搬走，
   而不是"草稿校验"这种按语义划的块。这是一次大得多的重构，
   且会把一个连贯的语义切成两半——代价可能比收益大；
2. **接受它就是一个"读若干来源、算若干结论"的编排者**，
   转而去压它的**行数与单方法长度**（本轮做的正是这件事）。

本轮的判断是选 2。理由：这个类的问题不是"依赖太多"——
它本来就要综合案件、预警、材料、问题、补充尽调这些来源；
问题是**单个方法长到两百多行**，读的时候看不出一个方法到底碰了哪些数据。
抽取让"这块逻辑归谁"变清楚了，这已经是可验证的改善。

## 一条结论

**"抽出去"不等于"变简单"。** 这一轮实测出来的三件事都指向同一处：
③ 表面 8 个协作者、实际 10 个；② 表面 3 个、实际 5 个；
而前置那一步（静态 helper）之所以干净，是因为它**零依赖**。

判据应该是"这块逻辑有没有独立的变化理由"，不是协作者数量——
因为按这个服务的数据形状，协作者数量根本降不下来（见上一节）。
把一个 500 行的方法组换个文件放、却仍在 10 个来源之间跳，
那只是把复杂度挪了个位置。
