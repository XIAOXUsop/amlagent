package com.bank.aml.sanction;

import java.time.Instant;
import java.util.List;

/** 单客户制裁筛查结果；只包含脱敏身份与候选解释。 */
public record SanctionScreeningResult(
        String customerId,
        String customerName,
        String status,
        Instant screenedAt,
        String sourceSystem,
        String sourceVersion,
        List<SanctionCandidateMatch> candidates
) {
}
