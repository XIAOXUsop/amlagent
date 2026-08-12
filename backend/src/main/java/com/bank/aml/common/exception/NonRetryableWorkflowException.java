package com.bank.aml.common.exception;

/**
 * 不可重试的工作流异常（客户不存在、输入非法等）。
 * 直接失败，不重试，进入失败态。
 */
public class NonRetryableWorkflowException extends RuntimeException {

    public NonRetryableWorkflowException(String message) {
        super(message);
    }

    public NonRetryableWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
