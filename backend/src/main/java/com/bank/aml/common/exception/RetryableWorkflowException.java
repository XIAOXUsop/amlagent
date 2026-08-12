package com.bank.aml.common.exception;

/**
 * 可重试的工作流异常（模型超时、网络抖动、外部数据源短暂不可用等）。
 * 捕获后按指数退避策略重试，超限进入死信队列。
 */
public class RetryableWorkflowException extends RuntimeException {

    public RetryableWorkflowException(String message) {
        super(message);
    }

    public RetryableWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
