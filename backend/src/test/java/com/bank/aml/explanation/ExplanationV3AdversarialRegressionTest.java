package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对抗性审查防回归（2026-09-08 第三方代付 v3 首批审查产物）：
 * 覆盖 TP-27 幂等重放（epoch 与摘要职责分离）、来源适配器 UNAVAILABLE、
 * canExclude/canConfirm 自审一致性、内容变更触发依赖失效、跨单元授权重复声明。
 */
class ExplanationV3AdversarialRegressionTest {

    private static final Long CASE_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private CaseRepository cases = mock(CaseRepository.class);
    private AlertExplanationUnitRepository units = mock(AlertExplanationUnitRepository.class);
    private ExplanationSubmissionRepository submissions = mock(ExplanationSubmissionRepository.class);
    private ExplanationIssueRepository issues = mock(ExplanationIssueRepository.class);
    private VerificationBasisRepository bases = mock(VerificationBasisRepository.class);
    private EvidenceArtifactVersionRepository artifacts = mock(EvidenceArtifactVersionRepository.class);
    private EvidenceVerificationEventRepository verifications = mock(EvidenceVerificationEventRepository.class);
    private ExplanationEvidenceUseRepository evidenceUses = mock(ExplanationEvidenceUseRepository.class);
    private ExplanationIssueReviewRepository issueReviews = mock(ExplanationIssueReviewRepository.class);
    private com.bank.aml.security.UserAccountRepository userAccounts =
            mock(com.bank.aml.security.UserAccountRepository.class);
    private AlertInvestigationCoverageRepository coverage = mock(AlertInvestigationCoverageRepository.class);
    private InvestigationHypothesisRepository hypotheses = mock(InvestigationHypothesisRepository.class);
    private AmlAlertRepository alerts = mock(AmlAlertRepository.class);
    private EnhancedDueDiligenceRequestRepository edd = mock(EnhancedDueDiligenceRequestRepository.class);
    private AuditOutboxService audit = mock(AuditOutboxService.class);
    private DemoEvidenceSourceAdapter source = new DemoEvidenceSourceAdapter();
    private com.bank.aml.datasource.CustomerDataPort customerData =
            mock(com.bank.aml.datasource.CustomerDataPort.class);
    private ObjectMapper mapper = new ObjectMapper();
    private com.bank.aml.investigation.AmlAlert alertRow;

    private final CaseEntity caseEntity = v2Case();
    private final AlertExplanationUnit unit = unit(100L, 11L);
    private final java.util.List<ExplanationSubmission> savedSubmissions = new java.util.ArrayList<>();
    private final java.util.Map<String, EvidenceArtifactVersion> artifactStore = new java.util.HashMap<>();
    private final java.util.Map<Long, EvidenceArtifactVersion> artifactById = new java.util.HashMap<>();
    private final java.util.List<ExplanationEvidenceUse> savedUses = new java.util.ArrayList<>();
    private final java.util.List<ExplanationIssue> savedIssues = new java.util.ArrayList<>();
    private ExplanationWorkspaceService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(caseEntity));
        // 关键：模拟真实 bumpFactsEpoch 推进实体上的 epoch（先前 mock 不改实体，掩盖过缺陷）
        when(cases.bumpFactsEpoch(ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv -> {
            caseEntity.setCaseFactsEpoch(caseEntity.getCaseFactsEpoch() + 1);
            return 1;
        });
        when(customerData.transactionsOf("C001")).thenReturn(List.of(
                new com.bank.aml.domain.TransactionRecord(
                        java.time.LocalDateTime.parse("2026-09-01T10:15:00"),
                        new java.math.BigDecimal("320000.00"), "转入", "丙集团公司", null,
                        "企业网银", "货款结算", "CNY", "T-1001"),
                new com.bank.aml.domain.TransactionRecord(
                        java.time.LocalDateTime.parse("2026-09-02T14:30:00"),
                        new java.math.BigDecimal("120000.00"), "转入", "丙集团公司", null,
                        "企业网银", "货款结算", "CNY", "T-1002")));
        when(units.findByIdAndCaseId(100L, CASE_ID)).thenReturn(Optional.of(unit));
        when(units.findById(100L)).thenReturn(Optional.of(unit));
        when(units.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(unit));
        when(submissions.save(any())).thenAnswer(inv -> {
            ExplanationSubmission saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            savedSubmissions.add(saved);
            submissionById.put(saved.getId(), saved);
            return saved;
        });
        when(submissions.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(submissionById.get(inv.getArgument(0, Long.class))));
        when(submissions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(submissions.findByCaseIdAndStateOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenAnswer(inv -> savedSubmissions.stream()
                        .filter(s -> s.getState() == SubmissionState.CURRENT).toList());
        when(submissions.findTopByUnitIdOrderBySubmissionNoDesc(100L)).thenReturn(Optional.empty());
        when(issues.save(any())).thenAnswer(inv -> {
            ExplanationIssue saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            savedIssues.add(saved);
            return saved;
        });
        when(issues.findByCaseIdAndIssueKey(any(), any())).thenAnswer(inv -> savedIssues.stream()
                .filter(issue -> issue.getIssueKey().equals(inv.getArgument(1))).findFirst());
        when(issues.findByCaseIdOrderByIdAsc(CASE_ID)).thenAnswer(inv -> List.copyOf(savedIssues));
        when(issues.findByCaseIdAndUnitIdOrderByIdAsc(any(), any())).thenAnswer(inv ->
                savedIssues.stream()
                        .filter(issue -> inv.getArgument(1, Long.class).equals(issue.getUnitId()))
                        .toList());
        when(issues.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return savedIssues.stream().filter(issue -> id.equals(issue.getId())).findFirst();
        });
        java.util.Map<String, EvidenceArtifactVersion> artifactByCaseKey = artifactStore;
        when(artifacts.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(artifactByCaseKey.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(artifacts.save(any())).thenAnswer(inv -> {
            EvidenceArtifactVersion saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            artifactStore.put(saved.getCaseId() + "|" + saved.getArtifactKey(), saved);
            artifactById.put(saved.getId(), saved);
            return saved;
        });
        when(artifacts.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.ofNullable(artifactById.get(id));
        });
        com.bank.aml.explanation.EvidenceVerificationEvent preVerified =
                new com.bank.aml.explanation.EvidenceVerificationEvent();
        setId(preVerified, 900L);
        preVerified.setCaseId(CASE_ID);
        preVerified.setArtifactVersionId(1L);
        preVerified.setMethod("INDEPENDENT_SOURCE_CHECK");
        preVerified.setObservedFacts("来源内容与业务事实核对一致，观察与限制已记录");
        preVerified.setResult("CONFIRMED");
        preVerified.setActor("verifier-x");
        when(verifications.findByArtifactVersionIdOrderByEventTimeAsc(1L))
                .thenReturn(List.of(preVerified));
                // FR-01 评估器链查询：材料级通用核验（subjectFactKey=NULL 兼容路径）
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyOrderByEventTimeAscIdAsc(
                ArgumentMatchers.eq(1L), any())).thenReturn(List.of());
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyIsNullOrderByEventTimeAscIdAsc(1L))
                .thenReturn(List.of(preVerified));
when(verifications.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceUses.save(any())).thenAnswer(inv -> {
            ExplanationEvidenceUse saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            savedUses.add(saved);
            return saved;
        });
        when(evidenceUses.findByCaseIdAndArtifactVersionId(any(), any())).thenAnswer(inv -> {
            Long artifactVersionId = inv.getArgument(1);
            return savedUses.stream()
                    .filter(use -> artifactVersionId.equals(use.getArtifactVersionId())).toList();
        });
        when(bases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bases.findTopByCaseIdOrderByBasisRevisionDesc(CASE_ID)).thenReturn(Optional.empty());
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(coverage.findByAlertId(11L)).thenReturn(Optional.empty());
        when(hypotheses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hypotheses.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of());
        alertRow = new com.bank.aml.investigation.AmlAlert();
        setId(alertRow, 11L);
        alertRow.setExternalAlertId("ALERT-A");
        alertRow.setCaseId(CASE_ID);
        alertRow.setStatus(com.bank.aml.investigation.AlertStatus.LINKED);
        // G1-1/RF-05：冻结命中范围（含草稿使用的交易；范围缺口用例单独覆盖）
        try {
            alertRow.setTriggerTransactionIds(mapper.writeValueAsString(java.util.List.of("T-1001", "T-1002")));
            alertRow.setScopeSourceVersion("MONITOR-2026-09");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(alerts.findByCaseIdOrderByOccurredAtAsc(CASE_ID)).thenReturn(List.of(alertRow));
        when(alerts.findById(11L)).thenReturn(Optional.of(alertRow));
        when(edd.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any())).thenReturn(List.of());

        service = new ExplanationWorkspaceService(cases, units, submissions, issues, bases, artifacts,
                verifications, evidenceUses, issueReviews, userAccounts, coverage, hypotheses, alerts, edd,
                new ExplanationPolicyCatalog(), audit, source, customerData,
                new com.bank.aml.investigation.AlertScopeService(alerts, mapper),
                new ExplanationClaimService(
                        mock(ExplanationClaimRepository.class), mock(ClaimEvidenceLinkRepository.class), artifacts),
                new EvidenceAdmissibilityService(artifacts, verifications), mapper, CLOCK);
    }

    private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.Map<Long, ExplanationSubmission> submissionById = new java.util.HashMap<>();

    // ---- TP-27（v3 §9.2）：成功提交推进真实 epoch 后，同 requestId 重放返回原提交 ----

    @Test
    void idempotentReplaySurvivesEpochAdvance() throws Exception {
        captureCoreBankingMaterial();
        saveExplainedSettlementDraft();
        // 幂等索引由 save 后写入（模拟真实库）：save stub 中保存 byKey
        when(submissions.findByIdempotencyKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedSubmissions.stream()
                    .filter(s -> key.equals(s.getIdempotencyKey()))
                    .reduce((a, b) -> b);
        });

        ExplanationViews.SubmissionResult first = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst");
        long epochAfterSubmit = caseEntity.getCaseFactsEpoch();
        assertThat(epochAfterSubmit).isGreaterThan(0); // 提交已推进真实 epoch

        // 同 requestId 重放（payload 未变）：必须返回原提交，不得因 epoch 变化误判内容冲突
        ExplanationViews.SubmissionResult replay = service.submitUnit(CASE_ID, 100L,
                unit.getDraftRevision(), service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst");
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.messages().get(0)).contains("幂等重放");
    }

    /** 同幂等键 + 不同内容（草稿真变了）→ 仍要 409（TP-27 反向语义保留）。 */
    @Test
    void sameIdempotencyKeyWithRealContentChangeIsStillRejected() throws Exception {
        captureCoreBankingMaterial();
        saveExplainedSettlementDraft();
        when(submissions.findByIdempotencyKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedSubmissions.stream()
                    .filter(s -> key.equals(s.getIdempotencyKey()))
                    .reduce((a, b) -> b);
        });
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID),
                "IDEMP-CONTENT", "analyst");

        // 修订撤回后草稿内容真变了 → 同键重放必须 409
        service.amendUnit(CASE_ID, 100L, unit.getCurrentSubmissionId(), "analyst");
        ObjectMapper om = new ObjectMapper();
        ObjectNode draft = (ObjectNode) om.readTree(unit.getDraftJson());
        draft.put("outcome", "UNRESOLVED");
        draft.putArray("disclosedUnknowns").add("授权 AU-01 的独立确认尚未取得");
        unit.setDraftJson(om.writeValueAsString(draft));

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, unit.getDraftRevision(),
                service.reviewBasisToken(CASE_ID), "IDEMP-CONTENT", "analyst"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("不同提交内容");
    }

    // ---- 来源适配器 UNAVAILABLE（v3 §9）----

    @Test
    void sourceUnavailableIsRecordedNotFaked() {
        source.markUnavailable("CORE_BANKING", "TXN-DOC-001");
        ExplanationViews.EvidenceView view = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "TXN-DOC-001", "analyst");
        assertThat(view.availability()).isEqualTo("UNAVAILABLE");
        assertThat(view.contentSha256()).isNull();
        // 登记关键问题（不得就此形成最终决定）
        assertThat(savedIssues.stream().anyMatch(issue ->
                issue.getIssueKey().startsWith("SOURCE_UNAVAILABLE"))).isTrue();
    }

    // ---- canExclude/canConfirm 与自审一致（v3 §8）----

    @Test
    void canExcludeIsFalseWhenReviewerIsContributor() throws Exception {
        captureCoreBankingMaterial();
        saveExplainedSettlementDraft();
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID), "SELF-REVIEW", "analyst");
        // 贡献人=analyst（提交人）。以 analyst 视角评估 → canExclude 必须 false（与 blocker 一致）
        ExplanationWorkspaceService.InvestigationReadinessResult result =
                service.readinessResultForReviewer(CASE_ID, "analyst");
        assertThat(result.excludeBlockers()).anyMatch(blocker -> blocker.contains("实质贡献人"));
        assertThat(result.canExclude()).isFalse();
        assertThat(result.canConfirm()).isFalse();
        // 不同复核人视角不受影响
        ExplanationWorkspaceService.InvestigationReadinessResult independent =
                service.readinessResultForReviewer(CASE_ID, "reviewer-b");
        assertThat(independent.canExclude()).isTrue();
    }

    // ---- 内容变更（新版本）触发依赖失效（TP-16）----

    @Test
    void artifactContentChangeStalesDependentSubmissions() throws Exception {
        captureCoreBankingMaterial();
        saveExplainedSettlementDraft();
        when(submissions.findByIdempotencyKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedSubmissions.stream()
                    .filter(s -> key.equals(s.getIdempotencyKey()))
                    .reduce((a, b) -> b);
        });
        ExplanationViews.SubmissionResult submitted = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "CONTENT-CHANGE", "analyst");
        assertThat(submissionById.get(submitted.submissionId()).getState()).isEqualTo(SubmissionState.CURRENT);

        // 夹具内容被替换 → 同源再次抓取追加 v2；依赖 v1 的采用提交必须 STALE
        source.putFixture("CORE_BANKING", "TXN-DOC-001", "来源内容已被更正（演示）");
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");

        assertThat(submissionById.get(submitted.submissionId()).getState())
                .isEqualTo(SubmissionState.STALE);
        assertThat(unit.getCurrentSubmissionId()).isNull();
    }

    // ---- 跨单元授权重复声明（TP-31）----

    @Test
    void crossUnitDoubleAllocationIsRegisteredAsCriticalIssue() throws Exception {
        captureCoreBankingMaterial();
        // 单元 100 提交代付（声明 T-1001 被授权覆盖）
        AlertExplanationUnit unitA = unit;
        saveGroupPaymentDraft(unitA, List.of("T-1001", "T-1002"));
        when(submissions.findByIdempotencyKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedSubmissions.stream()
                    .filter(s -> key.equals(s.getIdempotencyKey()))
                    .reduce((a, b) -> b);
        });
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID), "DUP-A", "analyst");
        savedIssues.clear(); // 排除提交过程中的其他问题，聚焦跨单元检查

        // 单元 101（同案另一预警）再次声明覆盖 T-1001：
        // TP-31 语义修正（对抗性审查 D6 后再修正）：同一交易命中多个预警时复用解释引用、
        // 去重计量——共享命中不是重复用款，EXPLAINED 仍可提交；登记 CONTEXT_GAP 背景问题
        // 供人工核对去重口径。
        AlertExplanationUnit unitB = unit(101L, 12L);
        when(units.findByIdAndCaseId(101L, CASE_ID)).thenReturn(Optional.of(unitB));
        when(units.findById(101L)).thenReturn(Optional.of(unitB));
        // 预警 12 的冻结命中集与 11 相同（共享命中的同一批交易）
        when(alerts.findById(12L)).thenReturn(java.util.Optional.of(alertRow));
        saveGroupPaymentDraft(unitB, List.of("T-1001", "T-1002"));
        ExplanationViews.SubmissionResult unitBResult = service.submitUnit(CASE_ID, 101L, 0,
                service.reviewBasisToken(CASE_ID), "DUP-B", "analyst");
        assertThat(unitBResult.outcome()).isEqualTo(ExplanationOutcome.EXPLAINED);

        assertThat(savedIssues.stream().anyMatch(issue ->
                issue.getIssueKey().startsWith("AUTHORITY_SHARED_REFERENCE")
                        && issue.getSeverity() == IssueSeverity.CONTEXT_GAP)).isTrue();
    }

    // ---- 辅助 ----

    private void captureCoreBankingMaterial() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
    }

    private void saveExplainedSettlementDraft() throws Exception {
        ObjectNode draft = explainedSettlementDraft();
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(0);
    }

    private void saveGroupPaymentDraft(AlertExplanationUnit target, java.util.List<String> covered)
            throws Exception {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售，销售货款由买方集团统一代付");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", false);
        policy.put("payeeMatchesContractSeller", true);
        policy.put("groupRelationshipStatus", "GROUP_RELATIONSHIP_CONFIRMED");
        ObjectNode scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add("T-1001").add("T-1002");
        scope.put("scopeEnumerationNote", "以监测系统冻结命中清单为准，逐笔核对交易流水后枚举");
        scope.putObject("transactionAmounts").put("T-1001", "320000.00").put("T-1002", "120000.00");
        ArrayNode allocations = scope.putArray("allocations");
        allocations.addObject().put("transactionId", "T-1001").put("amount", "320000.00");
        allocations.addObject().put("transactionId", "T-1002").put("amount", "120000.00");
        ObjectNode claims = draft.putObject("claims");
        claims.putObject("C1").put("status", "SUPPORTED")
                .put("judgement", "核心流水付款账户归属丙集团公司，KYC 档案确认同属一集团");
        claims.putObject("C2").put("status", "SUPPORTED")
                .put("judgement", "订单与交付签收记录对应乙对甲的货款义务，已逐笔核对");
        claims.putObject("C3").put("status", "SUPPORTED")
                .put("judgement", "授权 AU-01 覆盖本单元交易，额度与有效期已核对");
        claims.putObject("C4").put("status", "SUPPORTED")
                .put("judgement", "本单元收款在授权范围内履行乙的付款义务，逐笔分配一致");
        ObjectNode authority = draft.putObject("authority");
        authority.put("authorityRef", "AU-01");
        authority.put("limitAmount", "440000.00");
        com.fasterxml.jackson.databind.node.ArrayNode coveredNode = authority.putArray("coveredTransactionIds");
        covered.forEach(coveredNode::add);
        ObjectNode questions = draft.putObject("questions");
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            ObjectNode question = questions.putObject(code);
            question.put("assessment", "SATISFIED");
            question.put("judgement", "代付链路核验：" + code + " 对应事实已由授权与流水核对支持");
            question.put("factLocation", "CORE_BANKING 流水 + 授权 AU-01");
            question.put("factKind", "OBSERVED_FACT");
            question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
            question.put("limitations", "核验仅覆盖冻结来源集合内的材料");
            question.putArray("artifactVersionIds").add(1L);
        }
        draft.put("outcome", "EXPLAINED");
        target.setDraftJson(mapper.writeValueAsString(draft));
        target.setDraftRevision(0);
    }

    private ObjectNode explainedSettlementDraft() {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", true);
        policy.put("payeeMatchesContractSeller", true);
        ObjectNode scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add("T-1001").add("T-1002");
        scope.put("scopeEnumerationNote", "以监测系统冻结命中清单为准，逐笔核对交易流水后枚举");
        scope.putObject("transactionAmounts").put("T-1001", "320000.00").put("T-1002", "120000.00");
        ArrayNode allocations = scope.putArray("allocations");
        allocations.addObject().put("transactionId", "T-1001").put("amount", "320000.00");
        allocations.addObject().put("transactionId", "T-1002").put("amount", "120000.00");
        ObjectNode questions = draft.putObject("questions");
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            ObjectNode question = questions.putObject(code);
            question.put("assessment", "SATISFIED");
            question.put("judgement", "事实已由来源核对支持，判断理由充分记录在案");
            question.put("factLocation", "CORE_BANKING 流水");
            question.put("factKind", "OBSERVED_FACT");
            question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
            question.put("limitations", "覆盖冻结来源集合");
            question.putArray("artifactVersionIds").add(1L);
        }
        draft.put("outcome", "EXPLAINED");
        return draft;
    }

    private CaseEntity v2Case() {
        CaseEntity entity = new CaseEntity();
        setId(entity, CASE_ID);
        entity.setStatus(CaseStatus.HOLD);
        entity.setInvestigationContractVersion(2);
        entity.setCaseFactsEpoch(0);
        entity.setCustomerId("C001");
        return entity;
    }

    private AlertExplanationUnit unit(Long id, Long alertId) {
        AlertExplanationUnit entity = new AlertExplanationUnit();
        setId(entity, id);
        entity.setCaseId(CASE_ID);
        entity.setAlertId(alertId);
        entity.setScopeRevision(1);
        entity.setDraftRevision(0);
        entity.setCreatedBy("analyst");
        return entity;
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
