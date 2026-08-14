package com.bank.aml.controller;

import com.bank.aml.dto.CaseDto;
import com.bank.aml.review.ManualReview;
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

/**
 * 人工复核接口（REVIEWER / ADMIN）。
 */
@RestController
@RequestMapping("/api/reviews")
@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 待复核队列（HOLD 工单） */
    @GetMapping("/pending")
    public List<CaseDto> pending() {
        return reviewService.pending().stream().map(CaseDto::from).toList();
    }

    /** 提交复核决定（携带 expectedReviewRevision 做乐观锁，旧 revision 返回 409） */
    @PostMapping("/{caseId}")
    public ManualReview submit(@PathVariable Long caseId, @RequestBody ReviewRequest req) {
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        return reviewService.submit(caseId, reviewer, req.reviewerRiskLevel(), req.decision(),
                req.comment(), req.expectedReviewRevision());
    }

    /** 工单复核记录 */
    @GetMapping("/{caseId}")
    public List<ManualReview> records(@PathVariable Long caseId) {
        return reviewService.records(caseId);
    }

    /** 反馈闭环统计 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return reviewService.stats();
    }

    public record ReviewRequest(String reviewerRiskLevel, String decision, String comment,
                                int expectedReviewRevision) {
    }
}
