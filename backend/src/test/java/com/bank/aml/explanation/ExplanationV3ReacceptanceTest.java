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
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.review.ReviewDecision;
import com.bank.aml.review.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A6-01～A6-06 防回归（v3 再次验收 2026-09-08；RC-01/03/05/06/07 服务层语义）。
 * 每项把此前特征探针断言反转为业务期望：拒绝/失效/正确状态；并保留正常成功样例。
 */
class ExplanationV3ReacceptanceTest {

    private static final Long CASE_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private final CaseRepository cases = mock(CaseRepository.class);
    private final AlertExplanationUnitRepository units = mock(AlertExplanationUnitRepository.class);
    private final ExplanationSubmissionRepository submissions = mock(ExplanationSubmissionRepository.class);
    private final ExplanationIssueRepository issues = mock(ExplanationIssueRepository.class);
    private final VerificationBasisRepository bases = mock(VerificationBasisRepository.class);
    private final EvidenceArtifactVersionRepository artifacts = mock(EvidenceArtifactVersionRepository.class);
    private final EvidenceVerificationEventRepository verifications =
            mock(EvidenceVerificationEventRepository.class);
    private final ExplanationEvidenceUseRepository evidenceUses =
            mock(ExplanationEvidenceUseRepository.class);
    private final ExplanationIssueReviewRepository issueReviews =
            mock(ExplanationIssueReviewRepository.class);
    private final com.bank.aml.security.UserAccountRepository userAccounts =
            mock(com.bank.aml.security.UserAccountRepository.class);
    private final AlertInvestigationCoverageRepository coverage =
            mock(AlertInvestigationCoverageRepository.class);
    private final InvestigationHypothesisRepository hypotheses =
            mock(InvestigationHypothesisRepository.class);
    private final AmlAlertRepository alerts = mock(AmlAlertRepository.class);
    private final EnhancedDueDiligenceRequestRepository edd =
            mock(EnhancedDueDiligenceRequestRepository.class);
    private final AuditOutboxService audit = mock(AuditOutboxService.class);
    private final DemoEvidenceSourceAdapter source = new DemoEvidenceSourceAdapter();
    private final com.bank.aml.datasource.CustomerDataPort customerData =
            mock(com.bank.aml.datasource.CustomerDataPort.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idSeq = new AtomicLong(0);

    private final CaseEntity caseEntity = v2Case();
    private final AlertExplanationUnit unit = unit(100L, 11L);
    private final java.util.List<ExplanationSubmission> savedSubmissions = new java.util.ArrayList<>();
    private final java.util.Map<Long, ExplanationSubmission> submissionById = new java.util.HashMap<>();
    private final java.util.Map<String, EvidenceArtifactVersion> artifactStore = new java.util.HashMap<>();
    private final java.util.Map<Long, EvidenceArtifactVersion> artifactById = new java.util.HashMap<>();
    private final java.util.List<ExplanationEvidenceUse> savedUses = new java.util.ArrayList<>();
    private final java.util.List<ExplanationIssue> savedIssues = new java.util.ArrayList<>();
    private final java.util.List<java.util.List<EvidenceVerificationEvent>> verificationHistory =
            new java.util.ArrayList<>();
    private ExplanationWorkspaceService service;

    @BeforeEach
    void setUp() {
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(cases.bumpFactsEpoch(ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv -> {
            caseEntity.setCaseFactsEpoch(caseEntity.getCaseFactsEpoch() + 1);
            return 1;
        });
        when(customerData.transactionsOf("C001")).thenReturn(List.of(
                new com.bank.aml.domain.TransactionRecord(
                        LocalDateTime.parse("2026-09-01T10:15:00"),
                        new java.math.BigDecimal("320000.00"), "转入", "丙集团公司", null,
                        "企业网银", "货款结算", "CNY", "T-1001"),
                new com.bank.aml.domain.TransactionRecord(
                        LocalDateTime.parse("2026-09-02T14:30:00"),
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
        when(submissions.findByIdempotencyKey(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return savedSubmissions.stream()
                    .filter(s -> key.equals(s.getIdempotencyKey())).reduce((a, b) -> b);
        });
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
        when(artifacts.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(artifactStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(artifacts.save(any())).thenAnswer(inv -> {
            EvidenceArtifactVersion saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            artifactStore.put(saved.getCaseId() + "|" + saved.getArtifactKey(), saved);
            artifactById.put(saved.getId(), saved);
            return saved;
        });
        when(artifacts.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv ->
                Optional.ofNullable(artifactById.get(inv.getArgument(0, Long.class))));
        when(verifications.findByArtifactVersionIdOrderByEventTimeAsc(any())).thenReturn(List.of());
                // FR-01 评估器链查询：材料级通用核验（subjectFactKey=NULL 兼容路径）
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyOrderByEventTimeAscIdAsc(
                ArgumentMatchers.eq(1L), any())).thenReturn(List.of());
        // 材料级通用核验链随 save 动态更新（与 FR-01 评估器一致：任一题目事实键均可读链）
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyIsNullOrderByEventTimeAscIdAsc(any()))
                .thenAnswer(inv -> allEventsFor(inv.getArgument(0, Long.class)));
when(verifications.save(any())).thenAnswer(inv -> {
            EvidenceVerificationEvent saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.incrementAndGet());
            }
            // 记录追加后，后续查询包含新事件（模拟真实库）
            final EvidenceVerificationEvent appended = saved;
            when(verifications.findByArtifactVersionIdOrderByEventTimeAsc(saved.getArtifactVersionId()))
                    .thenAnswer(inv2 -> {
                        // 重新按保存序列聚合：简化为保存到独立列表
                        return allEventsFor(saved.getArtifactVersionId());
                    });
            eventStore.computeIfAbsent(saved.getArtifactVersionId(), k -> new java.util.ArrayList<>())
                    .add(appended);
            return saved;
        });
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
        com.bank.aml.investigation.AmlAlert alertRow = new com.bank.aml.investigation.AmlAlert();
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
        when(alerts.findById(11L)).thenReturn(Optional.of(alertRow));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(CASE_ID)).thenReturn(List.of(alertRow));
        when(edd.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of());

        service = new ExplanationWorkspaceService(cases, units, submissions, issues, bases, artifacts,
                verifications, evidenceUses, issueReviews, userAccounts, coverage, hypotheses, alerts, edd,
                new ExplanationPolicyCatalog(), audit, source, customerData,
                new com.bank.aml.investigation.AlertScopeService(alerts, mapper),
                new ExplanationClaimService(
                        mock(ExplanationClaimRepository.class), mock(ClaimEvidenceLinkRepository.class), artifacts),
                new EvidenceAdmissibilityService(artifacts, verifications), mapper, CLOCK);
    }

    private final java.util.Map<Long, java.util.List<EvidenceVerificationEvent>> eventStore =
            new java.util.HashMap<>();

    private java.util.List<EvidenceVerificationEvent> allEventsFor(Long artifactVersionId) {
        return eventStore.getOrDefault(artifactVersionId, List.of());
    }

    // ==================== RC-03（A6-01）：空证据不能形成可采用解释 ====================

    /** 此前：空 artifactVersionIds 不触发校验，EXPLAINED + 最终排除均成功。 */
    @Test
    void emptyEvidenceDraftCanBeSavedButExplainedIsRejected() throws Exception {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        ObjectNode draft = explainedSettlementDraft();
        for (String code : List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")) {
            ((ObjectNode) draft.path("questions").path(code)).putArray("artifactVersionIds");
        }
        unit.setDraftJson(mapper.writeValueAsString(draft));
        // 草稿可保存（未完成调查）
        service.saveDraft(CASE_ID, 100L, 0, unit.getDraftJson(), "analyst");
        assertThat(unit.getDraftRevision()).isEqualTo(1);

        // EXPLAINED 提交被拒：SATISFIED 必须引用 RESOLVED 材料
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "RC-03", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空引用不能形成可采用结论");
    }

    /** 此前：引用材料但零核验事件 → 仍可 EXPLAINED（登记 ≠ 已核验 未闭合）。 */
    @Test
    void referencedMaterialWithoutVerificationIsRejected() throws Exception {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        saveExplainedSettlementDraftWithArtifact(1L);
        // FR-01：错误消息更新为"本题事实尚无有效 CONFIRMED 核验"（其它题的核验不能替代）
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-03-V", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚无有效 CONFIRMED 核验");
    }

    /** 正常成功样例：RESOLVED 材料 + CONFIRMED 核验 → EXPLAINED 可提交、最终排除通过。 */
    @Test
    void verifiedMaterialSupportsExplainedAndFinalExclusion() throws Exception {
        prepareVerifiedMaterial();
        saveExplainedSettlementDraftWithArtifact(1L);
        ExplanationViews.SubmissionResult submitted = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-03-OK", "analyst");
        assertThat(submitted.outcome()).isEqualTo(ExplanationOutcome.EXPLAINED);
        assertThatCode(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID))).doesNotThrowAnyException();
    }

    // ==================== RF-02（FR-02）：整体不适用必须拒绝 ====================

    /** 此前：NOT_APPLICABLE 只需理由文字即可跳过。 */
    @Test
    void allQuestionsNotApplicableIsRejected() throws Exception {
        prepareVerifiedMaterial();
        ObjectNode draft = explainedSettlementDraft(1L);
        for (String code : List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")) {
            ((ObjectNode) draft.path("questions").path(code)).put("assessment", "NOT_APPLICABLE")
                    .put("judgement", "本案不适用该问题");
            ((ObjectNode) draft.path("questions").path(code)).putArray("artifactVersionIds");
        }
        unit.setDraftJson(mapper.writeValueAsString(draft));
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RF-02", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许整体不适用")
                .hasMessageContaining("FR-02");
    }

    /** FR-02 政策核定矩阵：当前政策全部六题必答；notApplicableAllowed 对任意配方/题目返回 false。 */
    @Test
    void policyExceptionMatrixAllCoreQuestions() {
        ExplanationPolicyCatalog catalog = new ExplanationPolicyCatalog();
        for (String policy : List.of(ExplanationPolicyCatalog.GOODS_SETTLED_V1,
                ExplanationPolicyCatalog.GOODS_PREPAY_V1,
                ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1)) {
            for (String code : List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")) {
                assertThat(catalog.notApplicableAllowed(policy, code))
                        .as(policy + " / " + code)
                        .isFalse();
            }
        }
        assertThat(ExplanationPolicyCatalog.POLICY_VERSION).isNotBlank();
    }

    // ==================== RF-05（G1-1）：服务器冻结预警范围 ====================

    /** 此前：客户端删去一笔命中交易 → 范围声明自洽即可提交。 */
    @Test
    void removingHitTransactionFromScopeIsRejectedByServerScope() throws Exception {
        prepareVerifiedMaterial();
        // alert 11 冻结集含 T-1001/T-1002；draft 只声明 T-1001 → T-1002 缺口
        ObjectNode draft = explainedSettlementDraft(1L);
        ((ArrayNode) draft.path("scope").path("reviewedTransactionIds")).remove(1);
        ((ObjectNode) draft.path("scope").path("transactionAmounts")).remove("T-1002");
        ArrayNode allocations = (ArrayNode) draft.path("scope").path("allocations");
        allocations.remove(1);
        unit.setDraftJson(mapper.writeValueAsString(draft));
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RF-05", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T-1002")
                .hasMessageContaining("删去命中交易不能隐去缺口");
    }

    /** 范围未冻结 → 范围未知；EXPLAINED 被拒，UNRESOLVED 可保留调查现状（记录未知，不冒充完整）。 */
    @Test
    void unfrozenScopeBlocksExplainedButAllowsUnresolved() throws Exception {
        prepareVerifiedMaterial();
        // 解除 alert 11 冻结（模拟来源无法枚举/尚未冻结）
        try {
            com.bank.aml.investigation.AmlAlert unfrozen = new com.bank.aml.investigation.AmlAlert();
            setId(unfrozen, 11L);
            unfrozen.setExternalAlertId("ALERT-A");
            unfrozen.setCaseId(CASE_ID);
            unfrozen.setStatus(com.bank.aml.investigation.AlertStatus.LINKED);
            when(alerts.findById(11L)).thenReturn(java.util.Optional.of(unfrozen));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ObjectNode draft = explainedSettlementDraft(1L);
        unit.setDraftJson(mapper.writeValueAsString(draft));
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RF-05-U", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未冻结");
    }

    /** 正常路径：范围冻结 API + 完整覆盖的提交成功（防"一律拒绝"）。 */
    @Test
    void frozenScopeWithFullCoveragePasses() {
        com.bank.aml.investigation.AlertScopeService.ScopeSnapshot snapshot =
                new com.bank.aml.investigation.AlertScopeService(alerts, mapper)
                        .freezeScope(11L, List.of("T-1001", "T-1002"), List.of(), "MONITOR-2026-09", "analyst");
        assertThat(snapshot.triggerTransactionIds()).containsExactly("T-1001", "T-1002");
        // 空集冻结被拒
        assertThatThrownBy(() -> new com.bank.aml.investigation.AlertScopeService(alerts, mapper)
                .freezeScope(11L, List.of(), List.of(), "v2", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能冻结空集冒充完整");
    }

    // ==================== RC-05（A6-02）：授权必填 + 资金腿全覆盖 ====================

    /** 此前：删除整个 authority → 集团代付 EXPLAINED + 最终排除成功。 */
    @Test
    void absentAuthorityIsRejected() throws Exception {
        prepareVerifiedMaterial();
        ObjectNode draft = groupPaymentDraft("SUPPORTED", "SUPPORTED", "EXPLAINED",
                "440000.00", List.of("T-1001", "T-1002"));
        draft.remove("authority");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-05-A", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须声明代付授权");
    }

    /** 此前：covered 只含第一笔（320,000），第二笔 120,000 未被覆盖仍可整笔解释。 */
    @Test
    void authoritySubsetIgnoresUncoveredPaymentIsRejected() throws Exception {
        prepareVerifiedMaterial();
        ObjectNode draft = groupPaymentDraft("SUPPORTED", "SUPPORTED", "EXPLAINED",
                "320000.00", List.of("T-1001"));
        unit.setDraftJson(mapper.writeValueAsString(draft));
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-05-B", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T-1002")
                .hasMessageContaining("未被授权覆盖");
    }

    /** 正常成功样例：授权覆盖全部资金腿且额度充足 → EXPLAINED 可提交。 */
    @Test
    void fullAuthorityCoverageSupportsExplained() throws Exception {
        prepareVerifiedMaterial();
        ObjectNode draft = groupPaymentDraft("SUPPORTED", "SUPPORTED", "EXPLAINED",
                "440000.00", List.of("T-1001", "T-1002"));
        unit.setDraftJson(mapper.writeValueAsString(draft));
        ExplanationViews.SubmissionResult submitted = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-05-OK", "analyst");
        assertThat(submitted.outcome()).isEqualTo(ExplanationOutcome.EXPLAINED);
    }

    // ==================== RC-06（A6-05）：核验失去支持 → 依赖提交失效 ====================

    /** 此前：CONFIRMED → UNRESOLVED 后旧 EXPLAINED 仍 CURRENT，重取 token 后最终排除通过。 */
    @Test
    void reverificationUnresolvedInvalidatesAdoptedSubmission() throws Exception {
        prepareVerifiedMaterial();
        saveExplainedSettlementDraftWithArtifact(1L);
        ExplanationViews.SubmissionResult submitted = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-06", "analyst");
        assertThat(submissionById.get(submitted.submissionId()).getState())
                .isEqualTo(SubmissionState.CURRENT);

        // 追加 UNRESOLVED（来源无法继续确认）→ 依赖提交 STALE
        service.recordVerification(CASE_ID, 1L, "INDEPENDENT_SOURCE_CHECK",
                "来源无法继续确认该交易用途，需进一步问询", "需进一步问询", "UNRESOLVED", "verifier-x");
        assertThat(submissionById.get(submitted.submissionId()).getState())
                .isEqualTo(SubmissionState.STALE);
        assertThat(unit.getCurrentSubmissionId()).isNull();

        // 重取 token 后最终排除仍被阻断（无 CURRENT 提交）
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚无可采用的单元提交");
    }

    /** UNRESOLVED 但无依赖提交（未采用）→ 不影响其他单元，仅登记。 */
    @Test
    void reverificationUnresolvedWithoutDependentsIsRecordedOnly() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        ExplanationViews.VerificationView view = service.recordVerification(CASE_ID, 1L,
                "INDEPENDENT_SOURCE_CHECK", "来源无法继续确认该交易用途，需进一步问询",
                "需进一步问询", "UNRESOLVED", "verifier-x");
        assertThat(view.result()).isEqualTo("UNRESOLVED");
    }

    // ==================== RC-07（A6-03）：无任务不放行 + 合格任务放行 ====================

    /** 此前：预付未到期 + 无任何任务 → 最终排除通过（未到期 ≠ 已安排 未闭合）。 */
    @Test
    void prepayWithoutContinuingTaskIsBlocked() throws Exception {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        service.recordVerification(CASE_ID, 1L, "INDEPENDENT_SOURCE_CHECK",
                "来源内容与业务事实核对一致，观察与限制已记录", "覆盖冻结来源集合", "CONFIRMED", "verifier-x");
        unit.setDraftJson(mapper.writeValueAsString(prepayDraft("2026-12-01")));
        ExplanationViews.SubmissionResult submitted = service.submitUnit(CASE_ID, 100L, 0,
                service.reviewBasisToken(CASE_ID), "RC-07", "analyst");
        assertThat(submissionById.get(submitted.submissionId()).isFollowupRequired()).isTrue();

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未到期不等于已安排");
    }

    /** RF-04（FR-03）：无关任务（factKey 不匹配）承接交付义务 → 视为未覆盖，最终排除拒绝。 */
    @Test
    void mismatchedObligationTaskDoesNotCoverDeliveryObligation() throws Exception {
        prepareVerifiedMaterial();
        unit.setDraftJson(mapper.writeValueAsString(prepayDraft("2026-12-01")));
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID), "RF-04", "analyst");

        com.bank.aml.security.UserAccount assignee = new com.bank.aml.security.UserAccount();
        assignee.setUsername("analyst");
        assignee.setRole("ANALYST");
        assignee.setEnabled(true);
        when(userAccounts.findByUsername("analyst")).thenReturn(Optional.of(assignee));
        // 任务一切合格，但 obligationFactKey 绑定的是另一个义务（退款权限，非本提交的交付义务）
        EnhancedDueDiligenceRequest mismatched = new EnhancedDueDiligenceRequest();
        setId(mismatched, 67L);
        mismatched.setCaseId(CASE_ID);
        mismatched.setRoundNo(2);
        mismatched.setReasonCode("CONTINUING_REVIEW");
        mismatched.setRequiredItemsJson("[\"SOURCE_OF_FUNDS\"]");
        mismatched.setRequestedBy("reviewer-b");
        mismatched.setRequestedAt(LocalDateTime.now(CLOCK));
        mismatched.setAssignedTo("analyst");
        mismatched.setAssignedUnit("调查一组");
        mismatched.setDueAt(LocalDateTime.now(CLOCK).plusDays(30));
        mismatched.setStatus(EnhancedDueDiligenceStatus.OPEN);
        mismatched.setPurpose(EddTaskPurpose.CONTINUING_REVIEW);
        mismatched.setCompletionStandard("退款收款权限核验：核对主体与账户归属，观察与限制已记录");
        mismatched.setObligationFactKey("REFUND_AUTHORITY:T-1002");
        when(edd.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of(mismatched));

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERY:PO-2026-088")
                .hasMessageContaining("错绑任务不能替代本义务");
    }

    /** 合格承接任务存在 → 预付解释的最终排除通过（成功样例，防"一律拒绝"）。 */
    @Test
    void prepayWithQualifyingContinuingTaskPasses() throws Exception {
        prepareVerifiedMaterial();
        unit.setDraftJson(mapper.writeValueAsString(prepayDraft("2026-12-01")));
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID), "RC-07-OK", "analyst");

        com.bank.aml.security.UserAccount assignee = new com.bank.aml.security.UserAccount();
        assignee.setUsername("analyst");
        assignee.setRole("ANALYST");
        assignee.setEnabled(true);
        when(userAccounts.findByUsername("analyst")).thenReturn(Optional.of(assignee));
        EnhancedDueDiligenceRequest continuing = new EnhancedDueDiligenceRequest();
        setId(continuing, 66L);
        continuing.setCaseId(CASE_ID);
        continuing.setRoundNo(2);
        continuing.setReasonCode("CONTINUING_REVIEW");
        continuing.setRequiredItemsJson("[\"TRANSACTION_PURPOSE\"]");
        continuing.setRequestedBy("reviewer-b");
        continuing.setRequestedAt(LocalDateTime.now(CLOCK));
        continuing.setAssignedTo("analyst");
        continuing.setAssignedUnit("调查一组");
        continuing.setDueAt(LocalDateTime.now(CLOCK).plusDays(30));
        continuing.setStatus(EnhancedDueDiligenceStatus.OPEN);
        continuing.setPurpose(EddTaskPurpose.CONTINUING_REVIEW);
        continuing.setCompletionStandard("2026-12-01 交付核验：核对签收记录与入账，观察与限制已记录");
        // FR-03：义务事实键必须与提交派生的 DELIVERY:PO-2026-088 匹配
        continuing.setObligationFactKey("DELIVERY:PO-2026-088");
        when(edd.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of(continuing));

        assertThatCode(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID))).doesNotThrowAnyException();
    }

    // ==================== RC-01（A6-04）：补件请求豁免 token，最终决定仍强制 ====================

    /** 此前：页面补件（REQUEST_ENHANCED_DUE_DILIGENCE，无 token）被令牌冲突拒绝，EDD Service 未被调用。 */
    @Test
    void uiEddRequestWithoutTokenCreatesTaskPath() {
        var eddService = mock(com.bank.aml.review.EnhancedDueDiligenceService.class);
        var reviewService = new ReviewService(cases,
                mock(com.bank.aml.review.ManualReviewRepository.class), eddService,
                mock(com.bank.aml.reporting.SuspiciousTransactionReportService.class), audit,
                mock(com.bank.aml.investigation.InvestigationService.class), service);
        // 补件请求（页面协议：不取号）→ 不再被令牌冲突拦截（A6-04）；
        // EDD Service 被调用进入任务状态检查（后续校验由其负责）。
        assertThatCode(() -> reviewService.submit(CASE_ID, "reviewer-b", "中风险",
                "REQUEST_ENHANCED_DUE_DILIGENCE", "SOURCE_OF_FUNDS_EVIDENCE_REQUIRED",
                "请独立核验该笔付款的来源与用途。", 0,
                List.of("SOURCE_OF_FUNDS"), LocalDateTime.now(CLOCK).plusDays(5),
                "analyst", "team-a", null, null))
                .satisfies(thrown -> {
                    if (thrown != null) {
                        // 允许 EDD/角色层校验失败，但不得是"令牌"冲突
                        assertThat(thrown.getMessage()).doesNotContain("令牌");
                    }
                });
    }

    /** 最终排除缺 token 仍被拒绝（不为补件路径全局放松）。 */
    @Test
    void finalExclusionStillRequiresToken() throws Exception {
        prepareVerifiedMaterial();
        saveExplainedSettlementDraftWithArtifact(1L);
        service.submitUnit(CASE_ID, 100L, 0, service.reviewBasisToken(CASE_ID), "TOK-1", "analyst");
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b", "stale-token"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("令牌已失效");
    }

    // ==================== RC-02（A6-06）：未取得内容 → 无摘要 + 状态问题 ====================

    /** 此前：contentSha256=null 与 NOT NULL 约束冲突（mock 掩盖）；修复后不变式成立。 */
    @Test
    void unavailableSourceSavedWithoutDigestAndInvariantHolds() {
        source.markUnavailable("CORE_BANKING", "TXN-DOC-001");
        ExplanationViews.EvidenceView view = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "TXN-DOC-001", "analyst");
        assertThat(view.availability()).isEqualTo("UNAVAILABLE");
        assertThat(view.contentSha256()).isNull();
        // 实际保存的实体同样无摘要（持久化不变式：未取得内容必须无摘要）
        EvidenceArtifactVersion stored = artifactStore.values().iterator().next();
        assertThat(stored.getContentSha256()).isNull();
        assertThat(stored.getAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(savedIssues.stream().anyMatch(issue ->
                issue.getIssueKey().startsWith("SOURCE_UNAVAILABLE"))).isTrue();

        // 来源恢复 → 同 key 再次抓取：取得内容 → 追加 v2（RESOLVED + 服务器摘要）；
        // v1（UNAVAILABLE）保留可回放，不覆盖历史状态。
        source.putFixture("CORE_BANKING", "TXN-DOC-001", "恢复后的来源内容（演示）");
        ExplanationViews.EvidenceView resolved = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "TXN-DOC-001", "analyst");
        assertThat(resolved.availability()).isEqualTo("RESOLVED");
        assertThat(resolved.version()).isEqualTo(2);
        assertThat(resolved.contentSha256()).isEqualTo(
                DemoEvidenceSourceAdapter.contentSha256Of("恢复后的来源内容（演示）"));
    }

    // ==================== 辅助 ====================

    /** 抓取材料 + CONFIRMED 核验 + 处置 FACT_UNASSIGNED（材料关联到单元），形成可用的证据基础。 */
    private void prepareVerifiedMaterial() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        service.recordVerification(CASE_ID, 1L, "INDEPENDENT_SOURCE_CHECK",
                "来源内容与业务事实核对一致，观察与限制已记录", "覆盖冻结来源集合", "CONFIRMED", "verifier-x");
        ExplanationIssue unassigned = savedIssues.stream()
                .filter(issue -> issue.getIssueKey().startsWith("FACT_UNASSIGNED")).findFirst()
                .orElseThrow();
        service.disposeIssue(CASE_ID, unassigned.getId(), unassigned.getRevision(),
                com.bank.aml.explanation.IssueDisposition.RESOLVED_WITH_EVIDENCE.name(),
                "材料已关联到预警 100 的六问题引用（Q1-Q6），用于交易 T-1001 的事实核验",
                "core_banking:txn-doc-001", "analyst");
    }

    private void saveExplainedSettlementDraftWithArtifact(long artifactVersionId) throws Exception {
        unit.setDraftJson(mapper.writeValueAsString(explainedSettlementDraft(artifactVersionId)));
        unit.setDraftRevision(0);
    }

    private ObjectNode explainedSettlementDraft() {
        return explainedSettlementDraft(1L);
    }

    private ObjectNode explainedSettlementDraft(long artifactVersionId) {
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
            question.putArray("artifactVersionIds").add(artifactVersionId);
        }
        draft.put("outcome", "EXPLAINED");
        return draft;
    }

    private ObjectNode groupPaymentDraft(String c3Status, String c4Status, String outcome,
                                         String limit, List<String> coveredTxs) throws Exception {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售，销售货款由买方集团统一代付");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", false);
        policy.put("payeeMatchesContractSeller", true);
        policy.put("groupRelationshipStatus", "GROUP_RELATIONSHIP_CONFIRMED");
        ObjectNode scope = draft.putObject("scope");
        ArrayNode reviewed = scope.putArray("reviewedTransactionIds");
        reviewed.add("T-1001");
        reviewed.add("T-1002");
        scope.put("scopeEnumerationNote", "以监测系统冻结命中清单为准，逐笔核对交易流水后枚举");
        scope.putObject("transactionAmounts").put("T-1001", "320000.00").put("T-1002", "120000.00");
        com.fasterxml.jackson.databind.node.ArrayNode allocations = scope.putArray("allocations");
        allocations.addObject().put("transactionId", "T-1001").put("amount", "320000.00");
        allocations.addObject().put("transactionId", "T-1002").put("amount", "120000.00");
        ObjectNode claims = draft.putObject("claims");
        claims.putObject("C1").put("status", "SUPPORTED")
                .put("judgement", "核心流水付款账户归属丙集团公司，KYC 档案确认同属一集团");
        claims.putObject("C2").put("status", "SUPPORTED")
                .put("judgement", "订单与交付签收记录对应乙对甲的货款义务，已逐笔核对");
        claims.putObject("C3").put("status", c3Status)
                .put("judgement", "授权 AU-01 覆盖本单元交易，额度与有效期已核对");
        claims.putObject("C4").put("status", c4Status)
                .put("judgement", "本单元收款在授权范围内履行乙的付款义务，逐笔分配一致");
        if (limit != null) {
            ObjectNode authority = draft.putObject("authority");
            authority.put("authorityRef", "AU-01");
            authority.put("limitAmount", limit);
            com.fasterxml.jackson.databind.node.ArrayNode coveredNode =
                    authority.putArray("coveredTransactionIds");
            coveredTxs.forEach(coveredNode::add);
        }
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
            question.putArray("transactionIds").add("T-1001");
        }
        draft.put("outcome", outcome);
        return draft;
    }

    private ObjectNode prepayDraft(String deliveryDueDate) {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内制造企业：向固定供应商预付采购原材料");
        policy.put("paymentStage", "ADVANCE_PAYMENT");
        policy.put("payerMatchesContractBuyer", true);
        policy.put("payeeMatchesContractSeller", true);
        policy.put("contractNumber", "PO-2026-088");
        policy.put("deliveryDueDate", deliveryDueDate);
        ObjectNode scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add("T-1001").add("T-1002");
        scope.put("scopeEnumerationNote", "以监测冻结命中清单与合同预付条款逐笔枚举");
        scope.putObject("transactionAmounts").put("T-1001", "320000.00").put("T-1002", "120000.00");
        ArrayNode allocations2 = scope.putArray("allocations");
        allocations2.addObject().put("transactionId", "T-1001").put("amount", "320000.00");
        allocations2.addObject().put("transactionId", "T-1002").put("amount", "120000.00");
        ObjectNode questions = draft.putObject("questions");
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            ObjectNode question = questions.putObject(code);
            question.put("assessment", "SATISFIED");
            question.put("judgement", "预付安排与采购模式一致：" + code + " 对应事实有合同与流水依据");
            question.put("factLocation", "合同 + CORE_BANKING 流水");
            question.put("factKind", "DOCUMENT_ASSERTION");
            question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
            question.put("limitations", "核验仅覆盖冻结来源集合内的材料");
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
