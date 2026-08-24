package com.bank.aml.sanction;

/** 候选复核使用了过期 revision。 */
public class SanctionReviewConflictException extends RuntimeException {
    public SanctionReviewConflictException(String message) {
        super(message);
    }
}
