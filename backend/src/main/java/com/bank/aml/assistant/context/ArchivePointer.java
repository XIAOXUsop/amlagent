package com.bank.aml.assistant.context;

/**
 * 归档指针：指向一条被压缩/驱逐的上下文条目。
 *
 * <p>设计要点：**归档不复制正文**。{@code assistant_message} 已是完整历史的唯一事实源
 * （ADR-006 决策 4：AES-GCM 密文 + content_digest），再存一份副本会让密文与 PII 暴露面翻倍，
 * 并制造第二个"模型看到了什么"的事实源。因此这里只记**索引**：指向哪条消息、
 * 原文摘要是什么、多少 token——正文按需从原表取回。
 *
 * @param archiveRef      模型可见的引用键，形如 {@code HIST-<16hex>}
 * @param messageId       指向 {@code assistant_message.id}，解引用时按它取原文
 * @param kind            条目类型
 * @param sequenceNo      会话内序号
 * @param tokenEstimate   归档时占用的 token 估算（用于审计"省下了多少"）
 * @param preview         脱敏后的短预览（经 SensitiveDataDetector 处理，不得含敏感明文）
 * @param originalDigest  原文 SHA-256，用于解引用时校验未被篡改
 */
public record ArchivePointer(
        String archiveRef,
        String messageId,
        ContextEntryKind kind,
        long sequenceNo,
        int tokenEstimate,
        String preview,
        String originalDigest
) {
}
