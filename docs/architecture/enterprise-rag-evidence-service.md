# 企业级 RAG 法规证据服务

## 1. 设计目标

本模块的第一目标不是“总能返回一段文本”，而是为 AML Agent 提供**有权限、在适用期内、与问题相关、可引用、可复现、可回滚**的法规证据。若这些条件无法同时满足，系统必须显式拒答或转人工，不能把向量库中的最近邻伪装成法律依据。

质量优先级如下：

1. 访问控制与语料完整性；
2. 法规适用期、法域和版本正确性；
3. 关键处置有被引用条款的直接支持；
4. 召回率、排序质量与上下文效率；
5. 延迟与计算成本。

## 2. 在线检索链路

```text
服务端冻结的 topic/query/asOf/jurisdiction/accessScopes
  → Redis 缓存（key 含完整 indexVersion、权限、法域、日期、模型/精排版本）
  → PGVector 语义召回 + PostgreSQL 中文词项/短语召回
  → 授权和法域条件下推 + 有效期/可信状态二次门控
  → 可配置加权 RRF → 本地 Cross-Encoder 精排
  → 词法相关性阈值 → 近重复去除 → 单文档与总字符预算
  → SUPPORTED / INSUFFICIENT / NO_RELEVANT / ACCESS_DENIED / INDEX_UNAVAILABLE
  → 按 topic 冻结到 InvestigationSnapshot
  → Agent 只能按已规划 topic 读取对应证据包
  → 最终报告 evidenceId 归属、证据链一致性、关键处置法规支持校验
```

`RetrievalRequest` 是唯一的企业检索契约。访问范围由服务端根据调用者/工作流生成，不能由自然语言或模型输出生成。存储层预过滤减少无权限候选占满 Top-K 的风险；`EnterpriseLegalRetriever` 仍执行第二次 fail-closed 校验，形成纵深防御。

## 3. 索引供应链与发布状态机

索引身份不是单一语料哈希，而是以下字段的 SHA-256：

- corpusHash；
- chunkerVersion；
- metadataSchemaVersion；
- embedding provider/model/revision/modelHash/dimensions；
- distanceMetric。

因此即使法规正文不变，只要分块器、元数据协议或向量模型变化，也会构建新候选版本。发布流程：

```text
CANDIDATE → 获取中央构建租约 → 安全扫描 → 分块/Embedding
          → 写候选向量 → Smoke Search → 原子切换 active pointer → ACTIVE
失败：FAILED；旧 ACTIVE：RETIRED；清理中：PURGING
```

长构建期间后台心跳续租，并在写向量和切换指针前同步确认所有权。失去租约的旧实例不能继续发布。回滚只允许到曾成功发布的 `ACTIVE/RETIRED` 版本；活动版本、上一版本和最近两个退役版本受清理保护。

MySQL Manifest 与 PostgreSQL 向量表不假设分布式事务。清理采用可恢复 Saga：先提交 `PURGING`，再删除 PG 向量，最后删除 Manifest。中途故障会留下可重试状态，而不是伪造原子成功。

## 4. 入库安全与元数据

入库前拒绝符号链接、非常规文件、空文件、超大文件、控制字符、疑似密钥、身份证号和常见提示注入载荷。危险文件只保存文件名、SHA-256 和原因代码，不保存命中敏感原文；隔离记录先独立提交，再阻断构建。

法规按章/条切分，子块保留父标题，并记录：

- documentId、chunkId、evidenceId、contentDigest、sourceFile；
- parentSection、articleNumber、documentNumber；
- jurisdiction、effectiveFrom、effectiveTo、accessScopes、securityStatus；
- corpusVersion。

受控源文件可声明策略头：

```html
<!-- rag:jurisdiction=CN;effectiveFrom=2025-01-01;effectiveTo=2027-12-31;accessScopes=PUBLIC_LEGAL -->
```

当前只允许 `PUBLIC_LEGAL` 与 `AML_INTERNAL`，未知范围会阻断入库，避免文档自行声明高权限标签。

## 5. 评测与发布门槛

固定的 `rag-cases-v2.json` 与生产法规正文分离，包含业务改写问法和无答案问题。报告至少包含 Recall@5、Top3、MRR、nDCG@5、可回答性准确率、无答案拒答率、P95、数据集版本/哈希/复核状态。当前标签为 `PENDING_DOMAIN_REVIEW`，只可作为开发基线，不得宣称生产准确率。

2026-08-23 本机冷缓存基线：无精排链路 Recall@5/Top3 为 93.3%/93.3%、nDCG@5 84.2%、P95 135ms；启用本地 bge-reranker 且确认真实执行后为 100%/100%、nDCG@5 96.7%、P95 671ms。两者无答案拒答率均为 100%。该对比说明精排带来可观质量收益，也有不可忽略的 CPU 延迟，不能只公布质量数字。

建议在领域专家批准后启用以下灰度门槛：

| 指标 | 候选门槛 | 阻断条件 |
|---|---:|---:|
| Recall@5 | ≥ 90% | 低于当前 ACTIVE 超过 2 个百分点 |
| nDCG@5 | ≥ 80% | 低于当前 ACTIVE 超过 3 个百分点 |
| 无答案拒答准确率 | ≥ 95% | 任一高影响越权/错误法规案例未拒答 |
| P95 检索耗时 | 无精排 ≤ 300 ms；本地精排 ≤ 750 ms | 高于同模式当前 ACTIVE 50%，或超过对应绝对门槛 |
| ACL/有效期攻击集 | 100% | 任一泄露或过期法规被返回 |

候选先离线回放，再以 5% 影子流量比较结果（不影响生产回答），通过后按 5% → 25% → 100% 切换。任一硬门槛失败立即回滚上一版本。

## 6. 监控与告警

核心指标：

- `aml_rag_retrieval_total{status}`、`aml_rag_retrieval_duration_seconds`、`aml_rag_returned_hits`；
- `aml_rag_cache_hit_total`、`aml_rag_cache_miss_total`；
- `aml_rag_index_build_total{status}`、`aml_rag_index_build_duration_seconds`、`aml_rag_index_segments`。

建议告警：5 分钟 `INDEX_UNAVAILABLE > 0`；拒答率相对 7 日基线上升 2 倍；构建失败；活动索引为空；缓存命中率骤降；P95 超过当前运行模式门槛；出现隔离记录；审计只有 `STARTED` 且 10 分钟无最终状态。

日志不得记录查询原文、密钥命中原文或法规内可能存在的身份数据。检索状态、indexVersion、数量、耗时和稳定摘要足以排障。

## 7. 故障处置手册

### INDEX_UNAVAILABLE

1. 查看活动指针与 Manifest；
2. 若活动版本缺失但上一版本完整，管理员执行显式回滚；
3. 禁止临时关闭拒答门控；工单进入重试/人工流程；
4. 修复后运行固定评测与 ACL 攻击集再恢复。

### 候选构建失败

1. 根据 Manifest `failureCode` 和构建指标定位 embedding、租约或 PG 故障；
2. 确认旧 ACTIVE 仍可检索；
3. 若存在 `PURGING`，恢复 PG 后重复 cleanup；
4. 不手工改 active 指针或直接删向量。

### 语料被隔离

1. 管理员查看 `/api/admin/rag/quarantines` 的原因代码和文件摘要；
2. 在源仓库审核原文件，禁止把命中原文复制到工单或日志；
3. 修复并走双人审批后重新入库；
4. 不允许通过配置跳过扫描。

## 8. Embedding / Reranker 迁移协议

模型升级不能只修改配置名称。候选模型需记录制品哈希、版本、维度与距离度量，重建独立索引，并在同一冻结评测集上比较检索质量、拒答、延迟、内存和索引大小。只有质量硬门槛不退化且成本可接受时才灰度发布。

当前运行时实际提供 `all-MiniLM-L6-v2/384`；配置与实际模型不一致会启动失败，避免生成“名字变了、模型没变”的虚假迁移结果。中文专用 embedding 的接入与选型必须通过上述基准后再落地。
