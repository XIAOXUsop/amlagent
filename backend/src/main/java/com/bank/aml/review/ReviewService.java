package com.bank.aml.review;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
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
    private static final Set<String> ALLOWED_DECISIONS = Set.of("APPROVE", "REJECT", "ESCALATE");
    private static final int MAX_COMMENT_LENGTH = 500;

    private final CaseRepository caseRepository;
    private final ManualReviewRepository reviewRepository;

    public ReviewService(CaseRepository caseRepository, ManualReviewRepository reviewRepository) {
        this.caseRepository = caseRepository;
        this.reviewRepository = reviewRepository;
    }

    /** 待复核队列：所有 HOLD 工单 */
    public List<CaseEntity> pending() {
        return caseRepository.findByStatusOrderByCreatedAtAsc(CaseStatus.HOLD);
    }

    /**
     * 提交复核决定：
     * APPROVE → 工单完成；REJECT → 工单失败（可人工重试补充尽调）；ESCALATE → 保持 HOLD。
     * <p>reviewerRiskLevel / decision 使用闭集校验，非法输入直接拒绝（400），不再静默归一。
     */
    @Transactional
    public ManualReview submit(Long caseId, String reviewerId, String reviewerRiskLevel,
                               String decision, String comment) {
        if (reviewerRiskLevel != null && !ALLOWED_RISK_LEVELS.contains(reviewerRiskLevel)) {
            throw new IllegalArgumentException("非法风险等级：" + reviewerRiskLevel);
        }
        String normalizedDecision = decision == null ? "" : decision.toUpperCase();
        if (!ALLOWED_DECISIONS.contains(normalizedDecision)) {
            throw new IllegalArgumentException("非法复核决定：" + decision);
        }
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("复核意见过长（最多 " + MAX_COMMENT_LENGTH + " 字）");
        }

        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        if (c.getStatus() != CaseStatus.HOLD) {
            throw new IllegalStateException("工单当前状态不是 HOLD，无法复核：" + c.getStatus());
        }

        ManualReview review = new ManualReview();
        review.setCaseId(caseId);
        review.setReviewerId(reviewerId);
        review.setAgentRiskLevel(c.getRawRiskLevel() != null ? c.getRawRiskLevel() : c.getRiskLevel());
        review.setGuardrailRiskLevel(c.getRiskLevel());
        review.setReviewerRiskLevel(reviewerRiskLevel);
        review.setDecision(normalizedDecision);
        review.setComment(comment);
        review.setCompletedAt(LocalDateTime.now());

        switch (normalizedDecision) {
            case "APPROVE" -> {
                // 条件更新：并发下仅一个复核成功，其余返回 0
                if (caseRepository.completeReview(caseId, CaseStatus.DONE, CaseStatus.HOLD, null, null) == 0) {
                    throw new IllegalStateException("工单已被其他复核员处理");
                }
            }
            case "REJECT" -> {
                if (caseRepository.completeReview(caseId, CaseStatus.FAILED, CaseStatus.HOLD,
                        "REVIEW_REJECTED", "人工复核驳回，需补充尽调：" + (comment == null ? "" : comment)) == 0) {
                    throw new IllegalStateException("工单已被其他复核员处理");
                }
            }
            default -> {
                // ESCALATE：保持 HOLD，不改变终态
            }
        }
        return reviewRepository.save(review);
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
        long approved = all.stream().filter(r -> "APPROVE".equals(r.getDecision())).count();
        long rejected = all.stream().filter(r -> "REJECT".equals(r.getDecision())).count();
        return Map.of(
                "reviewedCount", total,
                "agreementRate", total == 0 ? 0 : Math.round(100.0 * agreed / total),
                "approvedCount", approved,
                "rejectedCount", rejected,
                "escalatedCount", total - approved - rejected);
    }
}
