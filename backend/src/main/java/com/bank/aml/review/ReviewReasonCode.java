package com.bank.aml.review;

import java.util.Locale;

/** 可审计的处置原因码；自由文本仅用于补充分析过程。 */
public enum ReviewReasonCode {
    TRANSACTION_PATTERN_INCONSISTENT(ReviewDecision.CONFIRM_SUSPICIOUS),
    SANCTIONS_OR_WATCHLIST_MATCH(ReviewDecision.CONFIRM_SUSPICIOUS),
    SOURCE_OF_FUNDS_UNCLEAR(ReviewDecision.CONFIRM_SUSPICIOUS),
    CUSTOMER_DUE_DILIGENCE_CONCERN(ReviewDecision.CONFIRM_SUSPICIOUS),

    VERIFIED_LEGITIMATE_PURPOSE(ReviewDecision.EXCLUDE_FALSE_POSITIVE),
    CUSTOMER_PROFILE_CONSISTENT(ReviewDecision.EXCLUDE_FALSE_POSITIVE),
    DUPLICATE_OR_KNOWN_ACTIVITY(ReviewDecision.EXCLUDE_FALSE_POSITIVE),
    WATCHLIST_FALSE_POSITIVE(ReviewDecision.EXCLUDE_FALSE_POSITIVE),

    MISSING_CUSTOMER_INFORMATION(ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE),
    SOURCE_OF_FUNDS_EVIDENCE_REQUIRED(ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE),
    BENEFICIAL_OWNER_VERIFICATION_REQUIRED(ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE),
    WATCHLIST_IDENTITY_VERIFICATION_REQUIRED(ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE);

    private final ReviewDecision decision;

    ReviewReasonCode(ReviewDecision decision) {
        this.decision = decision;
    }

    public static ReviewReasonCode parse(String value, ReviewDecision decision) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择与处置结论匹配的原因码");
        }
        final ReviewReasonCode reason;
        try {
            reason = valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("非法处置原因码：" + value);
        }
        if (reason.decision != decision) {
            throw new IllegalArgumentException("处置原因码与处置结论不匹配");
        }
        return reason;
    }
}
