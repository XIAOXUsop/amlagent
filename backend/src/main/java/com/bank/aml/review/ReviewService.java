package com.bank.aml.review;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.reporting.SuspiciousTransactionReportService;
import com.bank.aml.investigation.InvestigationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工复核服务：待复核队列、提交复核决定、反馈统计。
 */
@Service
public class ReviewService {

    private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("低风险", "中风险", "高风险");
    private static final int MAX_COMMENT_LENGTH = 500;
    private static final int MIN_ANALYSIS_LENGTH = 10;

    private final CaseRepository caseRepository;
    private final ManualReviewRepository reviewRepository;
    private final EnhancedDueDiligenceService enhancedDueDiligenceService;
    private final SuspiciousTransactionReportService reportService;
    private final AuditOutboxService auditOutbox;
    private final InvestigationService investigationService;
    /** v2 解释核验服务：决策表、依据令牌与自审限制（未启用 v2 的案件不经过它）。 */
    private final com.bank.aml.explanation.ExplanationWorkspaceService explanationWorkspaceService;

    public ReviewService(CaseRepository caseRepository, ManualReviewRepository reviewRepository,
                         EnhancedDueDiligenceService enhancedDueDiligenceService,
                         SuspiciousTransactionReportService reportService,
                         AuditOutboxService auditOutbox,
                         InvestigationService investigationService,
                         com.bank.aml.explanation.ExplanationWorkspaceService explanationWorkspaceService) {
        this.caseRepository = caseRepository;
        this.reviewRepository = reviewRepository;
        this.enhancedDueDiligenceService = enhancedDueDiligenceService;
        this.reportService = reportService;
        this.auditOutbox = auditOutbox;
        this.investigationService = investigationService;
        this.explanationWorkspaceService = explanationWorkspaceService;
    }

    /** 待复核队列：所有 HOLD 工单 */
    public List<CaseEntity> pending() {
        return caseRepository.findByStatusOrderByCreatedAtAsc(CaseStatus.HOLD);
    }

    /**
     * 提交复核决定：
     * 确认可疑/排除预警 → 工单完成；请求强化尽调 → 保持 HOLD，revision 自增。
     * <p>reviewerRiskLevel / decision 使用闭集校验（400）；所有决策均通过 reviewRevision 乐观锁
     * 条件更新，旧 revision 返回 409，避免并发复核冲突。
     */
    @Transactional
    public ManualReview submit(Long caseId, String reviewerId, String reviewerRiskLevel,
                               String decision, String reasonCode, String comment, int expectedReviewRevision,
                               List<String> requiredItems, LocalDateTime dueAt,
                               String assignedTo, String assignedUnit) {
        return submit(caseId, reviewerId, reviewerRiskLevel, decision, reasonCode, comment,
                expectedReviewRevision, requiredItems, dueAt, assignedTo, assignedUnit, null, null);
    }

    /** v2 扩展入口：携带复核依据令牌与义务接续计划（§10.1/§8.2；同一事务，任一失败整体回滚）。 */
    @Transactional
    public ManualReview submit(Long caseId, String reviewerId, String reviewerRiskLevel,
                               String decision, String reasonCode, String comment, int expectedReviewRevision,
                               List<String> requiredItems, LocalDateTime dueAt,
                               String assignedTo, String assignedUnit,
                               String reviewBasisToken,
                               List<EnhancedDueDiligenceService.ContinuationTaskPlan> continuationTasks) {
        if (reviewerRiskLevel == null || !ALLOWED_RISK_LEVELS.contains(reviewerRiskLevel.trim())) {
            throw new IllegalArgumentException("请选择有效的复核评级（低风险 / 中风险 / 高风险）");
        }
        reviewerRiskLevel = reviewerRiskLevel.trim();
        ReviewDecision normalizedDecision = ReviewDecision.parse(decision);
        ReviewReasonCode normalizedReason = ReviewReasonCode.parse(reasonCode, normalizedDecision);
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("复核意见过长（最多 " + MAX_COMMENT_LENGTH + " 字）");
        }
        if (comment == null || comment.trim().length() < MIN_ANALYSIS_LENGTH) {
            throw new IllegalArgumentException("请记录具体分析过程（至少 " + MIN_ANALYSIS_LENGTH + " 个字符）");
        }
        comment = comment.trim();

        // 与分析员材料提交争用同一案件行锁，避免结案和任务提交跨表交错。
        CaseEntity c = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        if (c.getStatus() != CaseStatus.HOLD) {
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.HOLD));
        }
        boolean v2Case = c.getInvestigationContractVersion() == 2;
        if (c.getInvestigationContractVersion() >= 3) {
            throw new WorkflowStateConflictException(caseId, c.getStatus(),
                    java.util.Set.of(CaseStatus.HOLD));
        }
        // v2 义务接续在同一事务内先行完成（§8.2.2）：创建 CONTINUING_REVIEW + 接替 OPEN 补件任务；
        // 任一写入失败整体回滚，随后按已接续状态校验任务门槛与决策表。
        if (v2Case && continuationTasks != null && !continuationTasks.isEmpty()) {
            if (normalizedDecision != ReviewDecision.CONFIRM_SUSPICIOUS) {
                throw new IllegalArgumentException("义务接续仅支持确认可疑路径；当前关键未知不能借接续放行排除");
            }
            enhancedDueDiligenceService.transferObligations(caseId, reviewerId, LocalDateTime.now(),
                    continuationTasks, null);
        }
        enhancedDueDiligenceService.validateReviewDecision(caseId, normalizedDecision, requiredItems, dueAt,
                assignedTo, assignedUnit);
        if (v2Case) {
            // v2：决策表 + 依据令牌 + 实质贡献人自审限制（§7/§9/§10）。
            explanationWorkspaceService.validateReadyForReview(c, normalizedDecision, reviewerId,
                    reviewBasisToken);
        } else {
            investigationService.validateReadyForReview(c, normalizedDecision);
        }

        ManualReview review = new ManualReview();
        review.setCaseId(caseId);
        review.setReviewerId(reviewerId);
        review.setAgentRiskLevel(c.getRawRiskLevel() != null ? c.getRawRiskLevel() : c.getRiskLevel());
        review.setGuardrailRiskLevel(c.getRiskLevel());
        review.setReviewerRiskLevel(reviewerRiskLevel);
        review.setDecision(normalizedDecision.name());
        review.setReasonCode(normalizedReason.name());
        review.setComment(comment);
        review.setReviewRevision(expectedReviewRevision);
        review.setCaseStatusBefore(c.getStatus().name());
        LocalDateTime reviewedAt = LocalDateTime.now();
        review.setCompletedAt(reviewedAt);

        int updated = switch (normalizedDecision) {
            case CONFIRM_SUSPICIOUS -> {
                review.setCaseStatusAfter(CaseStatus.REPORT_PENDING.name());
                yield caseRepository.completeReview(caseId, CaseStatus.REPORT_PENDING, CaseStatus.HOLD,
                        expectedReviewRevision, normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
            case EXCLUDE_FALSE_POSITIVE -> {
                review.setCaseStatusAfter(CaseStatus.DONE.name());
                yield caseRepository.completeReview(caseId, CaseStatus.DONE, CaseStatus.HOLD,
                        expectedReviewRevision, normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
            case REQUEST_ENHANCED_DUE_DILIGENCE -> {
                review.setCaseStatusAfter(CaseStatus.HOLD.name());
                yield caseRepository.requestEnhancedDueDiligence(caseId, CaseStatus.HOLD,
                        expectedReviewRevision, normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
        };
        if (updated == 0) {
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.HOLD));
        }
        enhancedDueDiligenceService.applyReviewDecision(caseId, normalizedDecision, normalizedReason,
                requiredItems, dueAt, assignedTo, assignedUnit, reviewerId, reviewedAt,
                null, null);
        ManualReview saved = reviewRepository.save(review);
        if (normalizedDecision == ReviewDecision.CONFIRM_SUSPICIOUS) {
            reportService.openPending(caseId, saved);
        }
        auditOutbox.enqueue("CASE_REVIEW:" + caseId + ":" + expectedReviewRevision,
                reviewerId, "REVIEW_DECISION", "CASE", String.valueOf(caseId),
                "decision=" + normalizedDecision + ",reasonCode=" + normalizedReason
                        + ",riskLevel=" + reviewerRiskLevel + ",commentLen=" + comment.length());
        return saved;
    }

    public List<ManualReview> records(Long caseId) {
        return reviewRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
    }

    /** 反馈闭环统计：Agent 与人工评级一致率、复核分布 */
    public Map<String, Object> stats() {
        List<ManualReview> all = reviewRepository.findAllByOrderByCreatedAtDesc();
        long total = all.size();
        long agreed = all.stream()
                .filter(r -> r.getAgentRiskLevel() != null && r.getAgentRiskLevel().equals(r.getReviewerRiskLevel()))
                .count();
        long confirmed = all.stream().filter(r -> Set.of("CONFIRM_SUSPICIOUS", "APPROVE")
                .contains(r.getDecision())).count();
        long excluded = all.stream().filter(r -> "EXCLUDE_FALSE_POSITIVE".equals(r.getDecision())).count();
        long eddRequested = all.stream().filter(r -> Set.of("REQUEST_ENHANCED_DUE_DILIGENCE", "REJECT", "ESCALATE")
                .contains(r.getDecision())).count();
        return Map.ofEntries(
                Map.entry("reviewedCount", total),
                Map.entry("agreementRate", total == 0 ? 0 : Math.round(100.0 * agreed / total)),
                Map.entry("confirmedSuspiciousCount", confirmed),
                Map.entry("falsePositiveCount", excluded),
                Map.entry("eddRequestedCount", eddRequested),
                // 兼容旧页面/调用方；新页面只使用上面的业务口径。
                Map.entry("approvedCount", all.stream().filter(r -> "APPROVE".equals(r.getDecision())).count()),
                Map.entry("rejectedCount", all.stream().filter(r -> "REJECT".equals(r.getDecision())).count()),
                Map.entry("escalatedCount", all.stream().filter(r -> "ESCALATE".equals(r.getDecision())).count())
        );
    }
}
