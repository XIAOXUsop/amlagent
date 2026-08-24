package com.bank.aml.assistant.application;

public class ConversationBusyException extends RuntimeException {
    public ConversationBusyException() { super("当前会话已有问题正在分析"); }
}
