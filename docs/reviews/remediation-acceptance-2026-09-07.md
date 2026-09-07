# 修改成果验收报告（2026-09-07）

验收对象：`D:\JCode` 当前工作区。HEAD 为 `3ab1c08`，仍包含多轮未提交修改；本报告以文末文件哈希定位本次验收版本。

依据：[第三轮验收报告](remediation-acceptance-v3-2026-09-05.md)、[业务优化计划 v3 的 A0](../plans/aml-business-optimization-plan-v3-2026-09-05.md)、当前实施记录。

## 1. 结论

**部分通过，尚不能关闭 A0 或判定整体交付验收通过。**

上一轮 A3-01 所列迁移测试代码错误已逐项修正；A3-02 的“提交冲突且刷新失败后重开丢失草稿”路径已有正式测试通过。新增页面级草稿还通过了本次独立补充验证：普通提交网络失败后恢复、明确取消后清理、成功后清理。

仍有一项已复现的 P2：保存已成功、随后刷新失败时，界面错误声称“更新失败、草稿已保留”，重新打开却显示旧依据。另有真实 MySQL 迁移、事务并发及全栈角色流程尚未实跑，不能用默认单测替代这些验收证据。

本次执行代码审查、默认测试、构建和临时组件探针，仅新增本报告及工作区临时验证文件，未修改业务源码或业务数据。B1–B6 业务优化工作包不属于本次 A0 修复验收的已交付成果。

## 2. 上轮问题逐项核对

| 上轮问题或要求 | 本次结果 | 验收边界 |
|---|---|---|
| V25 插入错误引用 V26 新列 | 代码审查通过 | `insertCoverageV25()` 不再包含 `hypothesis_revision` |
| 报告复核外键使用写死 ID | 代码审查通过 | 先插入 `manual_review`，再把实际生成的 ID 传入报告夹具；已对照表结构 |
| SQL NULL 被 JDBC 读成 0 | 代码审查通过 | `queryLong()` 检查 `wasNull()` |
| 外部预警编号读错结果列 | 代码审查通过 | 使用 `queryColumnSet(..., "external_alert_id")` |
| 迁移前伪造健康版本绑定 | 代码审查通过 | V25 夹具不含版本绑定；迁移后显式更新对照记录，历史 NULL 记录另行断言 |
| V26 第六条归档查询未执行 | 测试代码补齐 | V25/V26 分别要求 5/6 条 SELECT，逐条执行并检查结果；尚未真实连接 MySQL 运行 |
| 假设冲突刷新失败后重开恢复 | 正式组件测试通过 | 已加入页面级 `hypothesisDrafts`，存储目标结论及依据；正式用例覆盖失败后重开及再次冲突确认 |
| 普通提交失败后的草稿恢复 | 独立探针通过 | 在同一个“重申判断”入口重开，输入完整恢复，不产生额外提交 |
| 成功/取消后清理草稿 | 独立探针通过 | 使用相同目标结论重开验证，避免切换目标结论导致未清理草稿也能通过测试 |
| 保存成功但刷新失败 | 不通过 | 见 A4-01 |

因此，A3-01 可记录为“静态修复完成、数据库实跑待验”；A3-02 的原始复现路径可关闭，但相邻异常路径还需要 A4-01 修复。

## 3. A4-01 [P2] 保存成功后的刷新失败，被错误当成提交失败

主要位置：[CaseDetailView.vue:744](../../frontend/src/views/CaseDetailView.vue#L744)，同一异常捕获分支在第 754–758 行。

### 触发过程与实际结果

1. HOLD 案件有已确认假设，页面当前版本为 2，显示旧判断依据。
2. 分析员点击“重申判断”，输入新的依据。
3. `updateInvestigationHypothesis()` 成功返回版本 3；页面删除对应草稿。
4. 随后的 `reloadInvestigation()` 因网络错误失败。
5. 刷新异常落入提交的 catch，显示“假设更新失败……你输入的判断依据已保留”；没有成功提示。
6. 再次点击同一入口，弹框预填旧的数据库依据，而不是刚保存的输入。

本轮通过挂载真实 Vue SFC、点击实际按钮、控制 API 返回的组件探针复现上述全过程。这里没有证据表明服务端已保存内容丢失；问题是界面误报保存结果、继续显示旧事实，并对已经删除的草稿作出错误承诺。分析员可能因此进行不必要的重试，或基于旧依据再次确认判断。

覆盖编辑第 888–920 行也存在相同的“提交成功 → 删除草稿 → 刷新 → 共用提交 catch”结构，应在同一修复中核对；本轮动态复现针对假设路径，覆盖路径仅完成静态识别。

### 修改要求与通过标准

- 分开处理写入请求失败与写入成功后的刷新失败。只有前者进入提交失败/版本冲突恢复流程。
- 写入明确成功后，刷新失败应提示“已保存，但最新调查事实加载失败”，提供重新加载路径；不要声称更新失败或仍有未保存草稿。
- 刷新成功前应明确标记当前事实过期或限制继续编辑，避免把旧版本和旧依据当作当前事实再次提交。不得自动重放已经成功的写入。
- 对假设与覆盖分别加入“写入成功 + 读取刷新失败”的正式测试；断言写入只发生一次、提示正确，并检查重新加载后的版本/依据。
- 保持现有普通提交失败、409 冲突、明确取消与成功清理用例通过。不要简单把草稿删除语句后移，就把成功写入重新描述成未保存内容。

## 4. 实际执行记录

| 验证 | 实际结果 |
|---|---|
| 前端 `npm.cmd test` | 6 个文件、37 项通过 |
| 前端 `npm.cmd run build` | 类型检查与生产构建成功；存在 Element Plus 分块超过 500 kB 的构建提示，不作为本次阻断 |
| 后端默认 Maven `test` | 382 项：381 通过，0 失败、0 错误、1 跳过，BUILD SUCCESS |
| 被跳过的测试 | `AgentEvalLiveTest`，因为未设置 `RUN_LIVE_AGENT_EVAL`；不是数据库集成测试只跳过一项 |
| 本次独立组件探针 | 4 项断言通过：3 项验证正确行为，1 项验证 A4-01 缺陷能够复现；不能把这 4 项解释为功能全部通过 |
| MySQL V25→V26 迁移与查询 | 未执行；默认 Maven 排除 `integration` 标签 |
| 数据库并发、Worker/Outbox、角色全栈闭环 | 本轮未执行 |
| Docker 环境复查 | CLI 存在，`docker_engine` 命名管道不存在，守护进程不可连接；另有 Docker 配置读取权限提示 |

后端使用本机 JDK 21、缓存 Maven 3.9.9 和离线仓库；测试 JVM 临时目录设为工作区路径，并设置 `junit.jupiter.tempdir.cleanup.mode.default=NEVER` 规避沙箱临时目录清理问题。未删减断言或排除额外测试。

后端执行命令（工作目录 `backend`）：

```powershell
$env:JAVA_HOME = 'C:\Users\asus\.jdks\corretto-21.0.10'
& 'C:\Users\asus\.m2\wrapper\dists\apache-maven-3.9.9-bin\556b3776\apache-maven-3.9.9\bin\mvn.cmd' -o -s D:/JCode/.tmp/review-maven-settings.xml '-Dmaven.repo.local=C:\Users\asus\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:/JCode/.tmp/review-java' '-Djunit.jupiter.tempdir.cleanup.mode.default=NEVER' test
```

证据文件：

- 后端日志：`.tmp/acceptance-20260907-backend.log`。
- 临时探针：`.tmp/Acceptance20260907Probe.spec.ts`；日志：`.tmp/acceptance-20260907-probe.log`。
- 探针运行时放在 `frontend/src/views/Acceptance20260907Probe.spec.ts`，执行 `node frontend/node_modules/vitest/vitest.mjs run --root frontend src/views/Acceptance20260907Probe.spec.ts`；运行后已移出正式源码目录。

## 5. 集成验收与测试覆盖待补事项

1. **真实迁移验收仍是门槛。** 在明确隔离的 MySQL 测试实例上运行 `-Pintegration-test -Dtest=InvestigationContractMigrationTest test`，保留实际用例数量、数据库版本与完整日志。仓库通过 `test.included-groups` / `test.excluded-groups` 配置标签，优先使用已经定义的 profile，不要把默认测试成功当成该测试已执行。该测试会重建专用 schema。
2. **归档迁移证据还不完整。** 当前所谓 `legacySnapshotId` 在 `insertV26Fixtures()` 内、V26 迁移后插入 NULL 摘要，只能验证第六条查询能列出 NULL/非 NULL 记录，不能证明真实迁移前旧归档经过升级仍保留原貌。建议把旧快照放入 V25 阶段，升级后断言 payload、source digest、主键及新摘要 NULL 均符合预期；新快照仍在 V26 后创建。这是测试覆盖限制，本轮未发现对应生产迁移破坏。
3. **完成既定 W3/A0 集成场景。** 补齐改判与复核竞争、归并与 Worker 抢占、审计 Outbox 回滚、旧 Worker 终态保护，以及 ANALYST/REVIEWER 完整流程的实际执行证据。恢复依赖环境后仍需运行并判断结果，不能直接标为通过。

建议顺序：修复 A4-01 并补正式测试 → 完善旧归档迁移夹具 → 执行隔离集成与角色闭环 → 关闭 A0。后续 B1–B6 继续按原业务计划安排，本报告不将其提前计为已实现。

## 6. 验收版本指纹

| 文件 | SHA-256 |
|---|---|
| `frontend/src/views/CaseDetailView.vue` | `A42709448EA739FF06619F38FA2F09FE7A7120CB88EB8D7DDEBFDFA286AEC9F2` |
| `frontend/src/views/CaseDetailView.spec.ts` | `FAE2E1B658A21C70692A845DA88D6A4B8CC63749AC627CE6CA67293D26DDE348` |
| `backend/src/test/java/com/bank/aml/config/InvestigationContractMigrationTest.java` | `41F688F67BACCFEF2200F62695C21330268C9E165826EFCBAC8922071501DF5A` |
| `docs/ops/investigation-impact-queries-v26.sql` | `146A19CE22EAA3B444429CCC5B52F34BBADF93E2CF860D71F52FB0734605EBA6` |

上述文件继续修改后，需要重新验收对应受影响路径，不能沿用本报告作为新版本通过证明。
