package com.bank.aml.dossier;

import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.CaseLogEntity;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.review.ManualReview;
import com.bank.aml.review.ManualReviewRepository;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceEvidence;
import com.bank.aml.review.EnhancedDueDiligenceEvidenceRepository;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.InvestigationEvidenceLink;
import com.bank.aml.investigation.InvestigationEvidenceLinkRepository;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.operations.CaseOperationsService;
import com.bank.aml.operations.CaseOperationsView;
import com.bank.aml.reporting.SuspiciousTransactionReport;
import com.bank.aml.reporting.SuspiciousTransactionReportRepository;
import com.bank.aml.sanction.SanctionCandidateReview;
import com.bank.aml.sanction.SanctionCandidateReviewRepository;
import com.bank.aml.tools.ToolExecutionTraceEntity;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/** 聚合案件、快照元数据、工作流、工具轨迹和人工复核记录，生成可校验的调查档案。 */
@Service
public class CaseDossierService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final CaseRepository caseRepository;
    private final CaseLogRepository caseLogRepository;
    private final CaseExecutionRepository executionRepository;
    private final ToolExecutionTraceRepository toolTraceRepository;
    private final ManualReviewRepository reviewRepository;
    private final InvestigationSnapshotRepository snapshotRepository;
    private final SanctionCandidateReviewRepository sanctionReviewRepository;
    private final EnhancedDueDiligenceRequestRepository enhancedDueDiligenceRepository;
    private final EnhancedDueDiligenceEvidenceRepository enhancedDueDiligenceEvidenceRepository;
    private final SuspiciousTransactionReportRepository suspiciousTransactionReportRepository;
    private final AmlAlertRepository alertRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final InvestigationEvidenceLinkRepository investigationEvidenceRepository;
    private final AlertInvestigationCoverageRepository alertCoverageRepository;
    private final CaseOperationsService caseOperationsService;
    private final com.bank.aml.explanation.AlertExplanationUnitRepository explanationUnitRepository;
    private final com.bank.aml.explanation.ExplanationClaimRepository explanationClaimRepository;
    private final com.bank.aml.explanation.ExplanationIssueRepository explanationIssueRepository;
    private final com.bank.aml.explanation.VerificationBasisRepository explanationBasisRepository;
    private final ObjectMapper objectMapper;

    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              ObjectMapper objectMapper) {
        this(caseRepository, caseLogRepository, executionRepository, toolTraceRepository, reviewRepository,
                snapshotRepository, null, null, null, null, null, null, null, null, null,
                null, null, null, null, objectMapper);
    }

    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              SanctionCandidateReviewRepository sanctionReviewRepository,
                              ObjectMapper objectMapper) {
        this(caseRepository, caseLogRepository, executionRepository, toolTraceRepository, reviewRepository,
                snapshotRepository, sanctionReviewRepository, null, null, null,
                null, null, null, null, null, null, null, null, null, objectMapper);
    }

    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              SanctionCandidateReviewRepository sanctionReviewRepository,
                              EnhancedDueDiligenceRequestRepository enhancedDueDiligenceRepository,
                              ObjectMapper objectMapper) {
        this(caseRepository, caseLogRepository, executionRepository, toolTraceRepository, reviewRepository,
                snapshotRepository, sanctionReviewRepository, enhancedDueDiligenceRepository, null, null,
                null, null, null, null, null, null, null, null, null, objectMapper);
    }

    @Autowired
    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              SanctionCandidateReviewRepository sanctionReviewRepository,
                              EnhancedDueDiligenceRequestRepository enhancedDueDiligenceRepository,
                              EnhancedDueDiligenceEvidenceRepository enhancedDueDiligenceEvidenceRepository,
                              SuspiciousTransactionReportRepository suspiciousTransactionReportRepository,
                              AmlAlertRepository alertRepository,
                              InvestigationHypothesisRepository hypothesisRepository,
                              InvestigationEvidenceLinkRepository investigationEvidenceRepository,
                              AlertInvestigationCoverageRepository alertCoverageRepository,
                              CaseOperationsService caseOperationsService,
                              ObjectMapper objectMapper) {
        this(caseRepository, caseLogRepository, executionRepository, toolTraceRepository, reviewRepository,
                snapshotRepository, sanctionReviewRepository, enhancedDueDiligenceRepository,
                enhancedDueDiligenceEvidenceRepository, suspiciousTransactionReportRepository,
                alertRepository, hypothesisRepository, investigationEvidenceRepository,
                alertCoverageRepository, caseOperationsService,
                null, null, null, null, objectMapper);
    }

    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              SanctionCandidateReviewRepository sanctionReviewRepository,
                              EnhancedDueDiligenceRequestRepository enhancedDueDiligenceRepository,
                              EnhancedDueDiligenceEvidenceRepository enhancedDueDiligenceEvidenceRepository,
                              SuspiciousTransactionReportRepository suspiciousTransactionReportRepository,
                              AmlAlertRepository alertRepository,
                              InvestigationHypothesisRepository hypothesisRepository,
                              InvestigationEvidenceLinkRepository investigationEvidenceRepository,
                              AlertInvestigationCoverageRepository alertCoverageRepository,
                              CaseOperationsService caseOperationsService,
                              com.bank.aml.explanation.AlertExplanationUnitRepository explanationUnitRepository,
                              com.bank.aml.explanation.ExplanationClaimRepository explanationClaimRepository,
                              com.bank.aml.explanation.ExplanationIssueRepository explanationIssueRepository,
                              com.bank.aml.explanation.VerificationBasisRepository explanationBasisRepository,
                              ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.executionRepository = executionRepository;
        this.toolTraceRepository = toolTraceRepository;
        this.reviewRepository = reviewRepository;
        this.snapshotRepository = snapshotRepository;
        this.sanctionReviewRepository = sanctionReviewRepository;
        this.enhancedDueDiligenceRepository = enhancedDueDiligenceRepository;
        this.enhancedDueDiligenceEvidenceRepository = enhancedDueDiligenceEvidenceRepository;
        this.suspiciousTransactionReportRepository = suspiciousTransactionReportRepository;
        this.alertRepository = alertRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.investigationEvidenceRepository = investigationEvidenceRepository;
        this.alertCoverageRepository = alertCoverageRepository;
        this.caseOperationsService = caseOperationsService;
        this.explanationUnitRepository = explanationUnitRepository;
        this.explanationClaimRepository = explanationClaimRepository;
        this.explanationIssueRepository = explanationIssueRepository;
        this.explanationBasisRepository = explanationBasisRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CaseDossier export(Long caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));

        ParsedReport parsedReport = parseReport(caseEntity.getReportJson());
        InvestigationSnapshotEntity snapshot = caseEntity.getSnapshotId() == null ? null
                : snapshotRepository.findById(caseEntity.getSnapshotId()).orElse(null);

        CaseDossier.Content content = new CaseDossier.Content(
                caseSummary(caseEntity),
                parsedReport.status(),
                parsedReport.value(),
                snapshot == null ? null : snapshotMetadata(snapshot),
                caseLogRepository.findByCaseIdOrderByCreatedAtAsc(caseId).stream().map(this::workflowLog).toList(),
                executionRepository.findByCaseIdOrderByStartedAtAsc(caseId).stream().map(this::checkpoint).toList(),
                toolTraceRepository.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(caseId).stream()
                        .map(this::toolTrace).toList(),
                reviewRepository.findByCaseIdOrderByCreatedAtAsc(caseId).stream().map(this::reviewRecord).toList(),
                enhancedDueDiligenceRepository == null ? List.of()
                        : enhancedDueDiligenceRepository.findByCaseIdOrderByRoundNoAsc(caseId)
                        .stream().map(this::enhancedDueDiligenceRecord).toList(),
                suspiciousTransactionReportRepository == null ? null
                        : suspiciousTransactionReportRepository.findByCaseId(caseId)
                        .map(this::suspiciousTransactionReportRecord).orElse(null),
                sanctionReviewRepository == null ? List.of()
                        : sanctionReviewRepository.findByCustomerIdOrderByCreatedAtAsc(caseEntity.getCustomerId())
                        .stream().map(this::sanctionReviewRecord).toList(),
                alertRepository == null ? List.of()
                        : alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                        .map(this::alertRecord).toList(),
                hypothesisRepository == null ? List.of()
                        : hypothesisRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                        .map(this::hypothesisRecord).toList(),
                investigationEvidenceRepository == null ? List.of()
                        : investigationEvidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
                        .map(this::investigationEvidenceRecord).toList(),
                alertCoverageRepository == null ? List.of()
                        : alertCoverageRepository.findByCaseIdOrderByAlertIdAsc(caseId).stream()
                        .map(this::alertCoverageRecord).toList(),
                caseOperationsService == null ? null : caseOperationsRecord(caseOperationsService.get(caseId)),
                explanationSection(caseEntity, caseId));

        return new CaseDossier("1.7", "INTERNAL_CONFIDENTIAL", Instant.now(), "SHA-256",
                sha256(content), content);
    }

    /** 解释核验档案段（v3 §11）：历史档案保持原貌；无解释数据（v0/v1 案件）返回 null。 */
    private CaseDossier.ExplanationSection explanationSection(CaseEntity caseEntity, Long caseId) {
        if (caseEntity.getInvestigationContractVersion() < 2 || explanationUnitRepository == null) {
            return null;
        }
        List<CaseDossier.ExplanationUnitRecord> units = explanationUnitRepository
                .findByCaseIdOrderByIdAsc(caseId).stream()
                .map(unit -> new CaseDossier.ExplanationUnitRecord(unit.getId(), unit.getAlertId(),
                        unit.getPolicyCode(), unit.getDraftRevision(), unit.getCurrentSubmissionId(),
                        null, unit.getDraftJson(), unit.getCreatedBy(), unit.getUpdatedAt()))
                .toList();
        List<CaseDossier.ExplanationClaimRecord> claims = explanationClaimRepository == null ? List.of()
                : explanationClaimRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(claim -> new CaseDossier.ExplanationClaimRecord(claim.getId(), claim.getUnitId(),
                        claim.getClaimCode(), claim.getStatus(), claim.getImportance(),
                        claim.getJudgement(), claim.getMethodNote(), claim.getLimitations(),
                        claim.getNotApplicableReason(), claim.getClaimRevision(), claim.getUpdatedBy()))
                .toList();
        List<CaseDossier.ExplanationIssueRecord> issues = explanationIssueRepository == null ? List.of()
                : explanationIssueRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(issue -> new CaseDossier.ExplanationIssueRecord(issue.getId(), issue.getUnitId(),
                        issue.getIssueKey(), issue.getSeverity().name(), issue.getDescription(),
                        issue.getDisposition().name(), issue.getDispositionReason(),
                        issue.getResolvedBy(), issue.getConfirmedBy(), issue.getRevision()))
                .toList();
        List<CaseDossier.ExplanationBasisRecord> bases = explanationBasisRepository == null ? List.of()
                : explanationBasisRepository.findTopByCaseIdOrderByBasisRevisionDesc(caseId)
                .map(basis -> List.of(new CaseDossier.ExplanationBasisRecord(basis.getId(),
                        basis.getBasisRevision(), basis.getScopeJson(), basis.getScopeDigest(),
                        basis.getBasisDigest(), basis.getSourceCutoff(), basis.getCreatedBy())))
                .orElse(List.of());
        return new CaseDossier.ExplanationSection(units, claims, issues, bases);
    }

    private CaseDossier.CaseSummary caseSummary(CaseEntity entity) {
        return new CaseDossier.CaseSummary(entity.getId(), entity.getCustomerId(), entity.getCustomerName(),
                entity.getAlertRule(), entity.getStatus(), entity.getRawRiskLevel(), entity.getRiskLevel(),
                entity.getSummary(), entity.getReportSource(), entity.getModelProvider(), entity.getModelName(),
                entity.isModelFallback(), entity.getExecutionVersion(), entity.getReviewRevision(),
                entity.getInvestigationContractVersion(),
                entity.getReviewDisposition(), entity.getReviewReasonCode(), entity.getReviewedAt(),
                entity.getRetryCount(), entity.getFailureCode(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CaseDossier.SnapshotMetadata snapshotMetadata(InvestigationSnapshotEntity entity) {
        return new CaseDossier.SnapshotMetadata(entity.getSnapshotId(), entity.getExecutionVersion(),
                entity.getAsOfTime(), entity.getSourceSystem(), entity.getSourceVersion(),
                entity.getLegalIndexVersion(), entity.getSourceDigest(), entity.getAlertsDigest(),
                frozenAlerts(entity), entity.getCreatedAt());
    }

    /**
     * 从加密归档载荷中提取本次执行实际冻结的预警引用。
     * 旧快照（无预警事实）返回空列表；载荷损坏时拒绝导出，不能把损坏记录伪装成“无预警”。
     */
    private List<CaseDossier.FrozenAlertRef> frozenAlerts(InvestigationSnapshotEntity entity) {
        if (entity.getAlertsDigest() == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(
                    com.bank.aml.security.SensitivePayloadCipher.decrypt(entity.getPayloadCiphertext()));
            JsonNode alerts = root.path("alerts");
            if (!alerts.isArray()) {
                throw new IllegalStateException("尽调快照归档缺少预警事实");
            }
            List<CaseDossier.FrozenAlertRef> refs = new ArrayList<>();
            for (JsonNode alert : alerts) {
                refs.add(new CaseDossier.FrozenAlertRef(
                        alert.path("alertId").isNumber() ? alert.get("alertId").asLong() : null,
                        alert.path("externalAlertId").isTextual() ? alert.get("externalAlertId").asText() : null,
                        alert.path("alertRevision").isInt() ? alert.get("alertRevision").asInt() : 0));
            }
            return List.copyOf(refs);
        } catch (Exception e) {
            throw new IllegalStateException("尽调快照预警事实解析失败：" + entity.getSnapshotId(), e);
        }
    }

    private CaseDossier.WorkflowLog workflowLog(CaseLogEntity entity) {
        return new CaseDossier.WorkflowLog(entity.getId(), entity.getStage(), entity.getContent(), entity.getCreatedAt());
    }

    private CaseDossier.ExecutionCheckpoint checkpoint(CaseExecution entity) {
        return new CaseDossier.ExecutionCheckpoint(entity.getExecutionVersion(), entity.getStage(), entity.getStatus(),
                entity.getStartedAt(), entity.getCompletedAt(), entity.getDurationMs(), entity.getErrorCode());
    }

    private CaseDossier.ToolTrace toolTrace(ToolExecutionTraceEntity entity) {
        return CaseDossier.ToolTrace.from(entity, parseEvidenceIds(entity.getEvidenceIdsJson()));
    }

    private CaseDossier.ReviewRecord reviewRecord(ManualReview entity) {
        return new CaseDossier.ReviewRecord(entity.getId(), entity.getReviewerId(), entity.getAgentRiskLevel(),
                entity.getGuardrailRiskLevel(), entity.getReviewerRiskLevel(), entity.getDecision(),
                entity.getReasonCode(), entity.getComment(),
                entity.getReviewRevision(), entity.getCaseStatusBefore(), entity.getCaseStatusAfter(),
                entity.getCreatedAt(), entity.getCompletedAt());
    }

    private CaseDossier.SanctionReviewRecord sanctionReviewRecord(SanctionCandidateReview entity) {
        return new CaseDossier.SanctionReviewRecord(entity.getCandidateFingerprint(), entity.getCandidateName(),
                entity.getListType(), entity.getMatchScore(), entity.getAlgorithmDecision(),
                entity.getReviewDecision(), entity.getReviewerId(), entity.getComment(),
                entity.getReviewRevision(), entity.getCreatedAt());
    }

    private CaseDossier.EnhancedDueDiligenceRecord enhancedDueDiligenceRecord(
            EnhancedDueDiligenceRequest entity) {
        return new CaseDossier.EnhancedDueDiligenceRecord(entity.getId(), entity.getRoundNo(), entity.getReasonCode(),
                parseRequiredDossierList(entity.getRequiredItemsJson(), "enhancedDueDiligence.requiredItems"),
                entity.getRequestedBy(), entity.getRequestedAt(), entity.getAssignedTo(), entity.getAssignedUnit(),
                entity.getDueAt(), entity.getStatus().name(), entity.getRevision(), entity.getResponseSummary(),
                parseDossierList(entity.getEvidenceReferencesJson(), "enhancedDueDiligence.evidenceReferences"),
                entity.getRespondedBy(), entity.getRespondedAt(), entity.getResolvedAt(), entity.getCancelledBy(),
                entity.getCancelledAt(), entity.getCancellationReason(),
                enhancedDueDiligenceEvidenceRepository == null || entity.getId() == null ? List.of()
                        : enhancedDueDiligenceEvidenceRepository.findByRequestIdOrderByIdAsc(entity.getId())
                        .stream().map(this::enhancedDueDiligenceEvidenceRecord).toList());
    }

    private CaseDossier.EnhancedDueDiligenceEvidenceRecord enhancedDueDiligenceEvidenceRecord(
            EnhancedDueDiligenceEvidence entity) {
        return new CaseDossier.EnhancedDueDiligenceEvidenceRecord(entity.getId(),
                "EDD-EVIDENCE-" + entity.getId(), entity.getRequiredItemCode(), entity.getSourceSystem(),
                entity.getSourceReference(), entity.getContentSha256(), entity.getCapturedBy(), entity.getCapturedAt());
    }

    private CaseDossier.SuspiciousTransactionReportRecord suspiciousTransactionReportRecord(
            SuspiciousTransactionReport entity) {
        return new CaseDossier.SuspiciousTransactionReportRecord(entity.getId(), entity.getReviewId(),
                entity.getStatus().name(), entity.getReportReason(), entity.getCreatedBy(), entity.getRevision(),
                entity.getExternalReference(), entity.getSubmittedBy(), entity.getSubmittedAt(), entity.getReturnedBy(),
                entity.getReturnedAt(), entity.getReturnReason(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CaseDossier.AlertRecord alertRecord(AmlAlert entity) {
        return new CaseDossier.AlertRecord(entity.getId(), entity.getExternalAlertId(), entity.getCustomerId(),
                entity.getRuleCode(), entity.getScenarioCode(), entity.getHitReason(), entity.getOccurredAt(),
                entity.getStatus(), entity.getRevision(), entity.getResolutionReason(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CaseDossier.HypothesisRecord hypothesisRecord(InvestigationHypothesis entity) {
        List<String> required = entity.getRequiredEvidenceTypes() == null
                || entity.getRequiredEvidenceTypes().isBlank() ? List.of()
                : java.util.Arrays.stream(entity.getRequiredEvidenceTypes().split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        return new CaseDossier.HypothesisRecord(entity.getId(), entity.getScenarioCode(),
                entity.getHypothesisCode(), entity.getTitle(), entity.getInvestigationQuestion(), required,
                entity.getStatus(), entity.getRationale(), entity.getRevision(), entity.getCreatedBy(),
                entity.getUpdatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CaseDossier.InvestigationEvidenceRecord investigationEvidenceRecord(InvestigationEvidenceLink entity) {
        return new CaseDossier.InvestigationEvidenceRecord(entity.getId(), entity.getHypothesisId(),
                entity.getEvidenceType(), entity.getEvidenceReference(), entity.getStance(),
                entity.getFindingSummary(), entity.getCreatedBy(), entity.getCreatedAt());
    }

    private CaseDossier.AlertCoverageRecord alertCoverageRecord(AlertInvestigationCoverage entity) {
        return new CaseDossier.AlertCoverageRecord(entity.getAlertId(), entity.getHypothesisId(),
                entity.getConclusion(), entity.getAnalysisSummary(), entity.getRevision(), entity.getUpdatedBy(),
                entity.getUpdatedAt());
    }

    private CaseDossier.CaseOperationsRecord caseOperationsRecord(CaseOperationsView view) {
        return new CaseDossier.CaseOperationsRecord(view.priority(), view.priorityScore(), view.priorityReasons(),
                view.priorityPolicy(), view.phase(), view.responsibleRole(), view.assignedTo(), view.assignedUnit(), view.clockStartedAt(),
                view.dueAt(), view.slaPolicy());
    }

    private ParsedReport parseReport(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) return new ParsedReport("MISSING", null);
        try {
            return new ParsedReport("VALID", objectMapper.readTree(reportJson));
        } catch (JsonProcessingException ignored) {
            // 不把无法解析的原始文本塞进档案，避免异常模型输出越过结构化边界。
            return new ParsedReport("INVALID", null);
        }
    }

    private List<String> parseEvidenceIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<String> ids = objectMapper.readValue(value, STRING_LIST);
            return ids == null ? List.of() : ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    /** 档案业务字段不能在损坏时降级为空列表，否则会形成“看似完整”的错误档案。 */
    private List<String> parseDossierList(String value, String field) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<String> items = objectMapper.readValue(value, STRING_LIST);
            if (items == null) return List.of();
            return items.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("案件档案字段损坏：" + field, e);
        }
    }

    private List<String> parseRequiredDossierList(String value, String field) {
        List<String> items = parseDossierList(value, field);
        if (items.isEmpty()) {
            throw new IllegalStateException("案件档案字段损坏：" + field + " 不能为空");
        }
        return items;
    }

    private String sha256(CaseDossier.Content content) {
        try {
            byte[] canonical = objectMapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("案件档案序列化失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    private record ParsedReport(String status, JsonNode value) {
    }
}
