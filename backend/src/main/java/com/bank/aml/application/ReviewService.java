package com.bank.aml.application;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.EddTaskPurpose;
import com.bank.aml.domain.ReviewDecision;
import com.bank.aml.explanation.ExplanationWorkspaceService;
import com.bank.aml.investigation.InvestigationService;
import com.bank.aml.reporting.SuspiciousTransactionReportService;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.review.ManualReview;
import com.bank.aml.review.ManualReviewRepository;
import com.bank.aml.review.ReviewReasonCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ExplanationWorkspaceService explanationWorkspaceService;

    private final Clock clock;

    @Autowired
    public ReviewService(CaseRepository caseRepository, ManualReviewRepository reviewRepository,
            EnhancedDueDiligenceService enhancedDueDiligenceService, SuspiciousTransactionReportService reportService,
            AuditOutboxService auditOutbox, InvestigationService investigationService,
            ExplanationWorkspaceService explanationWorkspaceService, Clock clock) {
        this.caseRepository = caseRepository;
        this.reviewRepository = reviewRepository;
        this.enhancedDueDiligenceService = enhancedDueDiligenceService;
        this.reportService = reportService;
        this.auditOutbox = auditOutbox;
        this.investigationService = investigationService;
        this.explanationWorkspaceService = explanationWorkspaceService;
        this.clock = clock;
    }

    /** 待复核队列：所有 HOLD 工单 */
    public List<CaseEntity> pending() {
        return caseRepository.findByStatusOrderByCreatedAtAsc(CaseStatus.HOLD);
    }

    /**
     * 复核预检（v3 闭环方案 §6 / RC-08 前置）：只读模拟，不创建任务、不改任何状态。 输出：变更前 token
     * 校验结果、案件与复核版本、拟议接续计划的逐项覆盖差异、 决策表可行性快照。页面在真正提交前用本接口校对计划。
     */
    @Transactional(readOnly = true)
    public ReviewPrecheckResult reviewPrecheck(Long caseId, String reviewer, ReviewDecision decision,
            String reviewBasisToken, List<EnhancedDueDiligenceService.ContinuationTaskPlan> plans) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        boolean v2Case = caseEntity.getInvestigationContractVersion() == 2;

        // token 一致性（只读比较，不因失败终止——预检的职责是暴露差异）
        boolean tokenCurrent = true;
        String tokenProblem = null;
        if (v2Case && decision != ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            String expected = explanationWorkspaceService.reviewBasisToken(caseId);
            tokenCurrent = reviewBasisToken != null && reviewBasisToken.trim().equals(expected);
            if (!tokenCurrent) {
                tokenProblem = "最终复核依据令牌与当前事实不一致（取号后案件事实已变化）";
            }
        }

        // 接续计划覆盖差异（只读模拟 transferObligations 的校验，不写库）
        List<ContinuationPlanCheck> planChecks = new ArrayList<>();
        if (plans != null) {
            for (EnhancedDueDiligenceService.ContinuationTaskPlan plan : plans) {
                String problem = null;
                Integer originRound = null;
                if (plan.originRequestId() == null) {
                    problem = "缺少 originRequestId（接续计划必须绑定原任务）";
                }
                else {
                    var origin = enhancedDueDiligenceService.lookupTask(caseId, plan.originRequestId());
                    if (origin.isEmpty()) {
                        problem = "原任务不存在或不属于本案件";
                    }
                    else if (origin.get().getStatus() != EnhancedDueDiligenceStatus.OPEN
                            || origin.get().getPurpose() != EddTaskPurpose.DECISION_SUPPORT) {
                        problem = "原任务不是待处理的决策支持任务（当前 " + origin.get().getStatus() + "/" + origin.get().getPurpose()
                                + "）";
                    }
                    else {
                        originRound = origin.get().getRoundNo();
                    }
                }
                boolean completionStandardPresent = plan.completionStandard() != null
                        && plan.completionStandard().trim().length() >= 10;
                String standardProblem = completionStandardPresent ? null : "完成标准缺失或过短（至少 10 个字符）";
                Instant dueAt = plan.dueAt() == null ? null : plan.dueAt().toInstant(ZoneOffset.UTC);
                planChecks.add(new ContinuationPlanCheck(plan.originRequestId(), problem, originRound,
                        plan.assignedTo(), dueAt, completionStandardPresent, standardProblem));
            }
        }

        // 未被任何计划引用的 OPEN 决策支持任务（接续后仍会阻断）
        List<EnhancedDueDiligenceRequest> openSupport = enhancedDueDiligenceService.openTasks(caseId);
        Set<Long> referenced = plans == null ? Set.of()
                : plans.stream()
                    .map(EnhancedDueDiligenceService.ContinuationTaskPlan::originRequestId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        List<Long> uncovered = openSupport.stream()
            .filter(task -> task.getPurpose() == EddTaskPurpose.DECISION_SUPPORT)
            .map(EnhancedDueDiligenceRequest::getId)
            .filter(id -> !referenced.contains(id))
            .toList();

        // 决策表可行性快照（v2，reviewer 视角含自审限制）
        Boolean canExclude = null;
        Boolean canConfirm = null;
        List<String> excludeBlockers = List.of();
        List<String> confirmBlockers = List.of();
        ExplanationWorkspaceService.ObligationCoverage simulatedObligationCoverage = null;
        if (v2Case) {
            var readiness = explanationWorkspaceService.readinessResultForReviewer(caseId, reviewer);
            canExclude = readiness.canExclude();
            canConfirm = readiness.canConfirm();
            excludeBlockers = readiness.excludeBlockers();
            confirmBlockers = readiness.confirmBlockers();
            // G3-1/RF-26：拟态义务覆盖计算——假设 plans 全部按声明创建后，
            // 各项义务是否被有效承接（factKey 匹配 + 有效承办人 + 未来期限 + 完成标准）。
            // 不写库；预检结果与最终提交可能因并发变化产生 BASIS_CONFLICT（RF-23 语义）。
            simulatedObligationCoverage = explanationWorkspaceService.simulateObligationCoverage(caseId, reviewer,
                    plans);
        }
        return new ReviewPrecheckResult(caseId, caseEntity.getStatus().name(), caseEntity.getCaseFactsEpoch(),
                caseEntity.getReviewRevision(), decision == null ? null : decision.name(), tokenCurrent, tokenProblem,
                List.copyOf(planChecks), uncovered, canExclude, canConfirm, excludeBlockers, confirmBlockers,
                simulatedObligationCoverage);
    }

    /**
     * 提交复核决定： 确认可疑/排除预警 → 工单完成；请求强化尽调 → 保持 HOLD，revision 自增。
     * <p>
     * reviewerRiskLevel / decision 使用闭集校验（400）；所有决策均通过 reviewRevision 乐观锁 条件更新，旧 revision
     * 返回 409，避免并发复核冲突。
     */
    @Transactional
    public ManualReview submit(Long caseId, String reviewerId, String reviewerRiskLevel, String decision,
            String reasonCode, String comment, int expectedReviewRevision, List<String> requiredItems,
            LocalDateTime dueAt, String assignedTo, String assignedUnit) {
        return submit(caseId, reviewerId, reviewerRiskLevel, decision, reasonCode, comment, expectedReviewRevision,
                requiredItems, dueAt, assignedTo, assignedUnit, null, null);
    }

    /** v2 扩展入口：携带复核依据令牌与义务接续计划（§10.1/§8.2；同一事务，任一失败整体回滚）。 */
    @Transactional
    public ManualReview submit(Long caseId, String reviewerId, String reviewerRiskLevel, String decision,
            String reasonCode, String comment, int expectedReviewRevision, List<String> requiredItems,
            LocalDateTime dueAt, String assignedTo, String assignedUnit, String reviewBasisToken,
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
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.HOLD));
        }
        // A6-04/RC-01：只有最终确认/排除强制最终依据 token；REQUEST_ENHANCED_DUE_DILIGENCE
        // 是补齐尚不足调查的动作，豁免"最终依据 token"（其页面协议本就不取号），
        // 但保留案件锁、expectedReviewRevision 条件更新、角色与任务状态检查。
        // 补件请求不能要求调查已满足最终决定条件，因此也不走决策表校验。
        if (v2Case && normalizedDecision != ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            explanationWorkspaceService.validateReviewBasisToken(caseId, reviewBasisToken);
        }
        // v2 义务接续在同一事务内完成（§8.2.2）：创建 CONTINUING_REVIEW + 接替被引用的
        // OPEN 补件任务；任一写入失败整体回滚，随后按已接续状态校验任务门槛与决策表。
        if (v2Case && continuationTasks != null && !continuationTasks.isEmpty()) {
            if (normalizedDecision != ReviewDecision.CONFIRM_SUSPICIOUS) {
                throw new IllegalArgumentException("义务接续仅支持确认可疑路径；当前关键未知不能借接续放行排除");
            }
            enhancedDueDiligenceService.transferObligations(caseId, reviewerId, LocalDateTime.now(clock),
                    continuationTasks, null);
        }
        enhancedDueDiligenceService.validateReviewDecision(caseId, normalizedDecision, requiredItems, dueAt, assignedTo,
                assignedUnit);
        if (v2Case) {
            // v2：决策表 + Clock 即时重评 + 自审限制。
            // 令牌已在本事务变更前由 validateReviewBasisToken 校验；接续会改变任务集合，
            // 不拿变更前 token 与变更后事实作等值比较（A5-07/TP-22）。
            explanationWorkspaceService.validateReadyForReview(c, normalizedDecision, reviewerId, reviewBasisToken,
                    true);
        }
        else {
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
        LocalDateTime reviewedAt = LocalDateTime.now(clock);
        review.setCompletedAt(reviewedAt);

        int updated = switch (normalizedDecision) {
            case CONFIRM_SUSPICIOUS -> {
                review.setCaseStatusAfter(CaseStatus.REPORT_PENDING.name());
                yield caseRepository.completeReview(caseId, CaseStatus.REPORT_PENDING, CaseStatus.HOLD,
                        expectedReviewRevision, normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
            case EXCLUDE_FALSE_POSITIVE -> {
                review.setCaseStatusAfter(CaseStatus.DONE.name());
                yield caseRepository.completeReview(caseId, CaseStatus.DONE, CaseStatus.HOLD, expectedReviewRevision,
                        normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
            case REQUEST_ENHANCED_DUE_DILIGENCE -> {
                review.setCaseStatusAfter(CaseStatus.HOLD.name());
                yield caseRepository.requestEnhancedDueDiligence(caseId, CaseStatus.HOLD, expectedReviewRevision,
                        normalizedDecision.name(), normalizedReason.name(), reviewedAt);
            }
        };
        if (updated == 0) {
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.HOLD));
        }
        enhancedDueDiligenceService.applyReviewDecision(caseId, normalizedDecision, normalizedReason, requiredItems,
                dueAt, assignedTo, assignedUnit, reviewerId, reviewedAt, null, null);
        ManualReview saved = reviewRepository.save(review);
        if (normalizedDecision == ReviewDecision.CONFIRM_SUSPICIOUS) {
            reportService.openPending(caseId, saved);
        }
        auditOutbox.enqueue("CASE_REVIEW:" + caseId + ":" + expectedReviewRevision, reviewerId, "REVIEW_DECISION",
                "CASE", String.valueOf(caseId), "decision=" + normalizedDecision + ",reasonCode=" + normalizedReason
                        + ",riskLevel=" + reviewerRiskLevel + ",commentLen=" + comment.length());
        return saved;
    }

    public List<ManualReview> records(Long caseId) {
        return reviewRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
    }

    /** 反馈闭环统计：Agent 与人工评级一致率、复核分布 */
    public ReviewStats stats() {
        List<ManualReview> all = reviewRepository.findAllByOrderByCreatedAtDesc();
        long total = all.size();
        long agreed = all.stream()
            .filter(r -> r.getAgentRiskLevel() != null && r.getAgentRiskLevel().equals(r.getReviewerRiskLevel()))
            .count();
        long confirmed = all.stream()
            .filter(r -> Set.of("CONFIRM_SUSPICIOUS", "APPROVE").contains(r.getDecision()))
            .count();
        long excluded = all.stream().filter(r -> "EXCLUDE_FALSE_POSITIVE".equals(r.getDecision())).count();
        long eddRequested = all.stream()
            .filter(r -> Set.of("REQUEST_ENHANCED_DUE_DILIGENCE", "REJECT", "ESCALATE").contains(r.getDecision()))
            .count();
        return new ReviewStats(total, total == 0 ? 0 : Math.round(100.0 * agreed / total), confirmed, excluded,
                eddRequested, all.stream().filter(r -> "APPROVE".equals(r.getDecision())).count(),
                all.stream().filter(r -> "REJECT".equals(r.getDecision())).count(),
                all.stream().filter(r -> "ESCALATE".equals(r.getDecision())).count());
    }

    public record ContinuationPlanCheck(Long originRequestId, String problem, Integer originRound, String assignedTo,
            Instant dueAt, boolean completionStandardPresent, String standardProblem) {
    }

    public record ReviewPrecheckResult(Long caseId, String caseStatus, long caseFactsEpoch, int reviewRevision,
            String decision, boolean tokenCurrent, String tokenProblem,
            List<ContinuationPlanCheck> continuationPlanChecks, List<Long> uncoveredDecisionSupportTasks,
            Boolean canExclude, Boolean canConfirm, List<String> excludeBlockers, List<String> confirmBlockers,
            ExplanationWorkspaceService.ObligationCoverage simulatedObligationCoverage) {
    }

    public record ReviewStats(long reviewedCount, long agreementRate, long confirmedSuspiciousCount,
            long falsePositiveCount, long eddRequestedCount, long approvedCount, long rejectedCount,
            long escalatedCount) {
    }

}
