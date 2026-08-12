package com.bank.aml.common.exception;

/**
 * 需要人工复核的异常（命中一级制裁名单、制裁名单服务不可用等底线场景）。
 * 工单转为 HOLD，进入人工复核队列。
 */
public class ManualReviewRequiredException extends RuntimeException {

    public ManualReviewRequiredException(String message) {
        super(message);
    }

    public ManualReviewRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
