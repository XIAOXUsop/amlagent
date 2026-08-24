package com.bank.aml.assistant.application;

public class AssistantRateLimitException extends RuntimeException {
    public AssistantRateLimitException() { super("AI 小助请求过于频繁，请稍后再试"); }
}
