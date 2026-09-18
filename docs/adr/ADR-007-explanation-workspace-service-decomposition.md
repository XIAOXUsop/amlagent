# ADR-007：`ExplanationWorkspaceService` 的拆分口径，以及已实测的边界

- 状态：Accepted
- 日期：2026-09-19

## 背景

`ExplanationWorkspaceService` 已经涨到 **2423 行 / 71 个方法 / 25 个注入依赖**，同时直接持有
大量 Repository、决策规则、审计与 DTO 装配。下一步实施方案（§2.4 任务 A5）要求按职责拆分，
并给了两个硬约束：**原有 Controller 接口不变**、**一次只提取一个职责，每次保持现有 API 和测试不变**。

在动手之前先量了一遍各候选职责的耦合面，结论改变了拆分的做法，值得记下来。

## 决策

### 1. 先提「无副作用 + 协作者自成一组的校验」

已完成第一步：`GroupPaymentClaimValidator`（代付配方 claims/authority 的事实约束）。

挑它的依据是量出来的，不是感觉：

- 整块只被 `validateDraftContent` 调用**一次**；
- 只用一个私有工具方法 `parseDateOrNull`（全类仅此一处使用）；
- 依赖面是自成一组的四个协作者（claimService / paymentAuthorityFactService / serverFacts / clock）；
- **不写任何东西**——它只抛异常，不落库、不发审计。这是它能被干净切开的关键。

搬移必须**逐字相同**，并且要能证明。做法：把旧、新两处的方法体都做「去注释 + 去空白」
归一，再按花括号配对提取比对：

```
validate         原 5193 字符 / 新 5193 字符   ✅ 逐字相同
parseDateOrNull  原  187 字符 / 新  187 字符   ✅ 逐字相同
```

### 2. 剩余部分**不能**靠逐方法提取降低耦合——这是实测的

对剩下几个大方法逐个量了「迁出后哪些依赖会变成它独占」：

| 候选 | 行数 | 依赖数 | 迁出后独占的依赖 |
|---|---:|---:|---|
| `validateDraftContent` | 256 | 8 | **无** |
| `captureEvidence` | 121 | 4 | **无** |
| `submitUnit` | 109 | 5 | **无** |
| `registerGroupPaymentGaps` | 99 | 3 | **无** |
| `nextActions` | 97 | 5 | **无** |

**没有一个方法的依赖是独占的。** 原因是耦合穿过的是共享助手而不是方法簇：
`ensureIssue` 一个助手就被 **14 处**调用，散落在草稿校验、证据采集、降级处置等各个方向。
另有一组「查询与装配」方法（`unitView`/`issueView`/`evidenceView`/`deriveContributors` 等）
合计只有 **112 行**，却要带上 5 个 Repository——其中 `issueRepository` 与
`submissionRepository` 在簇外各还有 **22 处**使用，迁出去等于只换来一个更长的构造器。

所以：逐方法提取只会**把代码挪个地方**，不会让任何一个类少依赖一点东西——
而「不再有一个类同时直接依赖大量 Repository、决策规则、审计和 DTO 装配器」是 A5 的验收项之一。
按现在的做法继续走下去，验收项永远达不成。

### 3. 要达成验收项，得按**事务/聚合边界**拆，而不是按方法簇

即拆成命令侧与查询侧（读写分离），各自持有自己真正需要的 Repository，
`ExplanationWorkspaceService` 退化成薄门面。这件事的前置条件是先决定
**`ensureIssue` 这类共享副作用助手的归属**——它现在横跨两侧，不先定下来，
拆出来的两侧会各自再长出一份。

## 结果

本次只做第 1 步，并把它做扎实：`ExplanationWorkspaceService` 2423 → 2272 行，
新增 `GroupPaymentClaimValidator` 与它的 **23 项直接单测**。

那些单测补的正好是原先测不到的地方：服务级那 5 个用例（TP-06/09/10/11/12）
**都到不了 `authority.facts` 那一段**——只要 C3 不是 SUPPORTED 或 authority 缺失就先抛了，
于是「facts 必须非空」「付款时点有效性（RF-18/RF-19）」「服务器来源取不到付款发生日」
这几段此前没有任何测试覆盖。

## 发布约束

- 拆分期间 **Controller 接口与现有测试不许变**；每次提取都要能证明是职责移动
  （用上面那套「去注释去空白后比对」的办法，不接受"看起来是搬过去的"）。
- 第 3 步（命令/查询拆分）**不要**在没有先决定共享副作用助手归属的情况下开工。
- 拆分不改变任何业务语义。任何语义调整都要有独立理由与独立测试，不夹在搬运里。
