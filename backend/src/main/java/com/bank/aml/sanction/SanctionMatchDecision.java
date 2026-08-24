package com.bank.aml.sanction;

/** 制裁名单候选的核验结论。 */
public enum SanctionMatchDecision {
    CONFIRMED,
    REVIEW_REQUIRED,
    DISMISSED
}
