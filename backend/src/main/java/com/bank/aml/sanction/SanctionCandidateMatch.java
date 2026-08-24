package com.bank.aml.sanction;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 可解释的制裁名单候选匹配结果。
 * <p>identityMasked 永远只返回脱敏值，reasonCodes 使用闭集代码，便于前端展示和审计统计。
 */
public record SanctionCandidateMatch(
        String candidateFingerprint,
        String candidateName,
        String identityMasked,
        String listType,
        String detail,
        int severity,
        int score,
        SanctionMatchDecision algorithmDecision,
        SanctionMatchDecision decision,
        List<String> reasonCodes,
        String explanation,
        String reviewDecision,
        int reviewRevision,
        String reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment
) {
    /** 只有算法确定命中或人工确认后的候选才能进入 Guardrail。 */
    public boolean actionable() {
        return decision == SanctionMatchDecision.CONFIRMED;
    }

    public SanctionCandidateMatch withReview(SanctionCandidateReview review) {
        if (review == null) return this;
        SanctionMatchDecision effective = switch (review.getReviewDecision()) {
            case "CONFIRM" -> SanctionMatchDecision.CONFIRMED;
            case "DISMISS" -> SanctionMatchDecision.DISMISSED;
            default -> SanctionMatchDecision.REVIEW_REQUIRED;
        };
        return new SanctionCandidateMatch(candidateFingerprint, candidateName, identityMasked, listType, detail,
                severity, score, algorithmDecision, effective, reasonCodes, explanation,
                review.getReviewDecision(), review.getReviewRevision(), review.getReviewerId(), review.getCreatedAt(),
                review.getComment());
    }
}
