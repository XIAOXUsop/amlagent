package com.bank.aml.dossier;

import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.CaseLogEntity;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.review.ManualReview;
import com.bank.aml.review.ManualReviewRepository;
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
    private final ObjectMapper objectMapper;

    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              ObjectMapper objectMapper) {
        this(caseRepository, caseLogRepository, executionRepository, toolTraceRepository, reviewRepository,
                snapshotRepository, null, objectMapper);
    }

    @Autowired
    public CaseDossierService(CaseRepository caseRepository,
                              CaseLogRepository caseLogRepository,
                              CaseExecutionRepository executionRepository,
                              ToolExecutionTraceRepository toolTraceRepository,
                              ManualReviewRepository reviewRepository,
                              InvestigationSnapshotRepository snapshotRepository,
                              SanctionCandidateReviewRepository sanctionReviewRepository,
                              ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.executionRepository = executionRepository;
        this.toolTraceRepository = toolTraceRepository;
        this.reviewRepository = reviewRepository;
        this.snapshotRepository = snapshotRepository;
        this.sanctionReviewRepository = sanctionReviewRepository;
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
                sanctionReviewRepository == null ? List.of()
                        : sanctionReviewRepository.findByCustomerIdOrderByCreatedAtAsc(caseEntity.getCustomerId())
                        .stream().map(this::sanctionReviewRecord).toList());

        return new CaseDossier("1.0", "INTERNAL_CONFIDENTIAL", Instant.now(), "SHA-256",
                sha256(content), content);
    }

    private CaseDossier.CaseSummary caseSummary(CaseEntity entity) {
        return new CaseDossier.CaseSummary(entity.getId(), entity.getCustomerId(), entity.getCustomerName(),
                entity.getAlertRule(), entity.getStatus(), entity.getRawRiskLevel(), entity.getRiskLevel(),
                entity.getSummary(), entity.getReportSource(), entity.getModelProvider(), entity.getModelName(),
                entity.isModelFallback(), entity.getExecutionVersion(), entity.getReviewRevision(),
                entity.getRetryCount(), entity.getFailureCode(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CaseDossier.SnapshotMetadata snapshotMetadata(InvestigationSnapshotEntity entity) {
        return new CaseDossier.SnapshotMetadata(entity.getSnapshotId(), entity.getExecutionVersion(),
                entity.getAsOfTime(), entity.getSourceSystem(), entity.getSourceVersion(),
                entity.getLegalIndexVersion(), entity.getSourceDigest(), entity.getCreatedAt());
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
                entity.getGuardrailRiskLevel(), entity.getReviewerRiskLevel(), entity.getDecision(), entity.getComment(),
                entity.getReviewRevision(), entity.getCaseStatusBefore(), entity.getCaseStatusAfter(),
                entity.getCreatedAt(), entity.getCompletedAt());
    }

    private CaseDossier.SanctionReviewRecord sanctionReviewRecord(SanctionCandidateReview entity) {
        return new CaseDossier.SanctionReviewRecord(entity.getCandidateFingerprint(), entity.getCandidateName(),
                entity.getListType(), entity.getMatchScore(), entity.getAlgorithmDecision(),
                entity.getReviewDecision(), entity.getReviewerId(), entity.getComment(),
                entity.getReviewRevision(), entity.getCreatedAt());
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
