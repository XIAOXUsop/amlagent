package com.bank.aml.controller;

import com.bank.aml.application.ReviewService;
import com.bank.aml.domain.ReviewDecision;
import com.bank.aml.dto.CaseDto;
import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.ManualReview;
import com.bank.aml.security.PromptInjectionGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工复核接口（REVIEWER / ADMIN）。
 */
@RestController
@RequestMapping("/api/reviews")
@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
public class ReviewController {

    private final ReviewService reviewService;

    private final PromptInjectionGuard injectionGuard;

    public ReviewController(ReviewService reviewService, PromptInjectionGuard injectionGuard) {
        this.reviewService = reviewService;
        this.injectionGuard = injectionGuard;
    }

    /** 待复核队列（HOLD 工单） */
    @GetMapping("/pending")
    public List<CaseDto> pending() {
        return reviewService.pending().stream().map(CaseDto::from).toList();
    }

    /** 提交复核决定（携带 expectedReviewRevision 做乐观锁，旧 revision 返回 409） */
    @PostMapping("/{caseId}")
    public ManualReviewResponse submit(@PathVariable Long caseId, @Valid @RequestBody ReviewRequest req) {
        // 复核评论是自由文本并将回显给其他复核者：检出注入模式直接拒绝（400）
        if (injectionGuard.scan(req.comment()).suspicious()) {
            throw new IllegalArgumentException("评论包含不被允许的内容");
        }
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        ManualReview review = reviewService.submit(caseId, reviewer, req.reviewerRiskLevel(), req.decision(),
                req.reasonCode(), req.comment(), req.expectedReviewRevision(), req.requiredItems(),
                toUtcLocalDateTime(req.dueAt()), req.assignedTo(), req.assignedUnit(), req.reviewBasisToken(),
                req.continuationTasks() == null ? null
                        : req.continuationTasks()
                            .stream()
                            .map(item -> new EnhancedDueDiligenceService.ContinuationTaskPlan(item.originRequestId(),
                                    item.assignedTo(), item.assignedUnit(), toUtcLocalDateTime(item.dueAt()),
                                    item.requiredItems(), item.completionStandard(), item.issueBindingsJson(),
                                    item.obligationFactKey(), item.obligationAmount(), item.obligationTransactionIds()))
                            .toList());
        return ManualReviewResponse.from(review);
    }

    /**
     * 复核预检（只读模拟，v3 闭环方案 §6）：不创建任务、不改状态； 返回 token 一致性、接续计划逐项覆盖差异、未覆盖的 OPEN 决策支持任务与决策表快照。
     */
    @PostMapping("/{caseId}/prechecks")
    public ReviewService.ReviewPrecheckResult precheck(@PathVariable Long caseId,
            @Valid @RequestBody PrecheckRequest req) {
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        ReviewDecision decision = req.decision() == null ? null : ReviewDecision.parse(req.decision());
        return reviewService.reviewPrecheck(caseId, reviewer, decision, req.reviewBasisToken(),
                req.continuationTasks() == null ? null
                        : req.continuationTasks()
                            .stream()
                            .map(item -> new EnhancedDueDiligenceService.ContinuationTaskPlan(item.originRequestId(),
                                    item.assignedTo(), item.assignedUnit(), toUtcLocalDateTime(item.dueAt()),
                                    item.requiredItems(), item.completionStandard(), item.issueBindingsJson(),
                                    item.obligationFactKey(), item.obligationAmount(), item.obligationTransactionIds()))
                            .toList());
    }

    /** 工单复核记录 */
    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('ANALYST','REVIEWER','ADMIN')")
    public List<ManualReviewResponse> records(@PathVariable Long caseId) {
        return reviewService.records(caseId).stream().map(ManualReviewResponse::from).toList();
    }

    /** 反馈闭环统计 */
    @GetMapping("/stats")
    public ReviewService.ReviewStats stats() {
        return reviewService.stats();
    }

    public record ReviewRequest(@NotBlank @Pattern(regexp = "高风险|中风险|低风险") String reviewerRiskLevel, @NotBlank @Pattern(
            regexp = "CONFIRM_SUSPICIOUS|EXCLUDE_FALSE_POSITIVE|REQUEST_ENHANCED_DUE_DILIGENCE") String decision,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String reasonCode,
            @NotBlank @Size(min = 10, max = 500) String comment, @PositiveOrZero int expectedReviewRevision,
            @Size(max = 100) List<@NotBlank @Size(max = 500) String> requiredItems, Instant dueAt,
            @Size(max = 64) String assignedTo, @Size(max = 64) String assignedUnit,
            /** v2：最终复核依据令牌（GET /review-basis 取得）；v1 案件可不带。 */
            @Size(max = 128) String reviewBasisToken, /** v2：义务接续计划（可疑 + 已披露未知路径必须提供）。 */
            @Size(max = 100) List<@Valid ContinuationTaskRequest> continuationTasks) {
    }

    public record ContinuationTaskRequest(@NotNull @PositiveOrZero Long originRequestId,
            @NotBlank @Size(max = 64) String assignedTo, @Size(max = 64) String assignedUnit, @NotNull Instant dueAt,
            @Size(max = 100) List<@NotBlank @Size(max = 500) String> requiredItems,
            @NotBlank @Size(min = 10, max = 1_000) String completionStandard,
            @Size(max = 20_000) String issueBindingsJson, /** FR-03/V33：本任务承接的义务事实键。 */
            @Size(max = 128) String obligationFactKey, BigDecimal obligationAmount,
            @Size(max = 2_000) String obligationTransactionIds) {
    }

    public record PrecheckRequest(@Pattern(
            regexp = "CONFIRM_SUSPICIOUS|EXCLUDE_FALSE_POSITIVE|REQUEST_ENHANCED_DUE_DILIGENCE") String decision,
            @Size(max = 128) String reviewBasisToken,
            @Size(max = 100) List<@Valid ContinuationTaskRequest> continuationTasks) {
    }

    public record ManualReviewResponse(Long id, Long caseId, String reviewerId, String agentRiskLevel,
            String guardrailRiskLevel, String reviewerRiskLevel, String decision, String reasonCode, String comment,
            int reviewRevision, String caseStatusBefore, String caseStatusAfter, Instant createdAt,
            Instant completedAt) {

        static ManualReviewResponse from(ManualReview review) {
            return new ManualReviewResponse(review.getId(), review.getCaseId(), review.getReviewerId(),
                    review.getAgentRiskLevel(), review.getGuardrailRiskLevel(), review.getReviewerRiskLevel(),
                    review.getDecision(), review.getReasonCode(), review.getComment(), review.getReviewRevision(),
                    review.getCaseStatusBefore(), review.getCaseStatusAfter(), toInstant(review.getCreatedAt()),
                    toInstant(review.getCompletedAt()));
        }
    }

    private static LocalDateTime toUtcLocalDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
