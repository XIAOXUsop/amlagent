package com.bank.aml.controller;

import com.bank.aml.dto.CaseDto;
import com.bank.aml.review.ManualReview;
import com.bank.aml.review.ReviewDecision;
import com.bank.aml.review.ReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 人工复核接口（REVIEWER / ADMIN）。
 */
@RestController
@RequestMapping("/api/reviews")
@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
public class ReviewController {

    private final ReviewService reviewService;
    private final com.bank.aml.security.PromptInjectionGuard injectionGuard;

    public ReviewController(ReviewService reviewService,
                            com.bank.aml.security.PromptInjectionGuard injectionGuard) {
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
    public ManualReview submit(@PathVariable Long caseId, @RequestBody ReviewRequest req) {
        // 复核评论是自由文本并将回显给其他复核者：检出注入模式直接拒绝（400）
        if (injectionGuard.scan(req.comment()).suspicious()) {
            throw new IllegalArgumentException("评论包含不被允许的内容");
        }
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        ManualReview review = reviewService.submit(caseId, reviewer, req.reviewerRiskLevel(), req.decision(),
                req.reasonCode(), req.comment(), req.expectedReviewRevision(), req.requiredItems(), req.dueAt(),
                req.assignedTo(), req.assignedUnit(), req.reviewBasisToken(),
                req.continuationTasks() == null ? null
                        : req.continuationTasks().stream()
                                .map(item -> new com.bank.aml.review.EnhancedDueDiligenceService.ContinuationTaskPlan(
                                        item.originRequestId(), item.assignedTo(), item.assignedUnit(),
                                        item.dueAt(), item.requiredItems(), item.completionStandard(),
                                        item.issueBindingsJson(), item.obligationFactKey(),
                                        item.obligationAmount(), item.obligationTransactionIds()))
                                .toList());
        return review;
    }

    /**
     * 复核预检（只读模拟，v3 闭环方案 §6）：不创建任务、不改状态；
     * 返回 token 一致性、接续计划逐项覆盖差异、未覆盖的 OPEN 决策支持任务与决策表快照。
     */
    @PostMapping("/{caseId}/prechecks")
    public Map<String, Object> precheck(@PathVariable Long caseId,
                                        @RequestBody PrecheckRequest req) {
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        ReviewDecision decision = req.decision() == null ? null : ReviewDecision.parse(req.decision());
        return reviewService.reviewPrecheck(caseId, reviewer, decision, req.reviewBasisToken(),
                req.continuationTasks() == null ? null
                        : req.continuationTasks().stream()
                                .map(item -> new com.bank.aml.review.EnhancedDueDiligenceService.ContinuationTaskPlan(
                                        item.originRequestId(), item.assignedTo(), item.assignedUnit(),
                                        item.dueAt(), item.requiredItems(), item.completionStandard(),
                                        item.issueBindingsJson(), item.obligationFactKey(),
                                        item.obligationAmount(), item.obligationTransactionIds()))
                                .toList());
    }

    /** 工单复核记录 */
    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('ANALYST','REVIEWER','ADMIN')")
    public List<ManualReview> records(@PathVariable Long caseId) {
        return reviewService.records(caseId);
    }

    /** 反馈闭环统计 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return reviewService.stats();
    }

    public record ReviewRequest(String reviewerRiskLevel, String decision, String reasonCode, String comment,
                                int expectedReviewRevision, List<String> requiredItems, LocalDateTime dueAt,
                                String assignedTo, String assignedUnit,
                                /** v2：最终复核依据令牌（GET /review-basis 取得）；v1 案件可不带。 */
                                String reviewBasisToken,
                                /** v2：义务接续计划（可疑 + 已披露未知路径必须提供）。 */
                                List<ContinuationTaskRequest> continuationTasks) {
    }

    public record ContinuationTaskRequest(Long originRequestId, String assignedTo, String assignedUnit,
                                          LocalDateTime dueAt, List<String> requiredItems,
                                          String completionStandard, String issueBindingsJson,
                                          /** FR-03/V33：本任务承接的义务事实键。 */
                                          String obligationFactKey,
                                          java.math.BigDecimal obligationAmount,
                                          String obligationTransactionIds) {
    }

    public record PrecheckRequest(String decision, String reviewBasisToken,
                                  List<ContinuationTaskRequest> continuationTasks) {
    }
}
