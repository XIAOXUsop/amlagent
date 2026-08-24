package com.bank.aml.assistant.application;

public class AssistantDisabledException extends RuntimeException {
    public AssistantDisabledException() { super("AI 小助当前未启用"); }
}
