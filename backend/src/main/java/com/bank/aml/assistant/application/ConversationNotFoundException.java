package com.bank.aml.assistant.application;

/** 不区分不存在与非 owner，避免枚举其他管理员的会话。 */
public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() { super("会话不存在"); }
}
