package com.bank.aml.assistant.context;

import java.util.List;

/**
 * 一条类型化的上下文条目。
 *
 * <p>四个字段承担四种语义，缺一不可：
 * <ul>
 *   <li>{@code kind} —— 决定保留策略（见 {@link ContextEntryKind}）</li>
 *   <li>{@code tokenEstimate} —— 预算计量，随 {@code content} 变化重算，不缓存</li>
 *   <li>{@code evidenceIds} —— **消费者边**：本条引用了哪些证据，用于依赖图算引用计数</li>
 *   <li>{@code archiveRef} —— 归档索引键，非空表示本条的部分原文已归档、可按需精确取回</li>
 * </ul>
 *
 * @param entryId    稳定标识，由 kind + sequenceNo 派生（如 {@code USER_TURN-3}），用于确定性排序
 * @param kind       条目类型
 * @param messageId  来源消息 ID（归档解引用主键）
 * @param sequenceNo 会话内序号
 * @param content    当前进入模型的文本（可能已被压缩）
 * @param tokenEstimate 当前内容的 token 估算
 * @param evidenceIds 本条引用到的证据标识（有序去重）
 * @param archiveRef  归档引用键；null 表示未归档
 */
public record ContextEntry(
        String entryId,
        ContextEntryKind kind,
        String messageId,
        long sequenceNo,
        String content,
        int tokenEstimate,
        List<String> evidenceIds,
        String archiveRef
) {

    public ContextEntry {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    /** 是否是"证据提供者"——驱逐时必须以再水合 stub 保住来源 */
    public boolean isEvidenceProvider() {
        return kind.zeroLoss();
    }

    /** 生成占位/压缩后的副本（token 由调用方按新内容重算） */
    public ContextEntry withContent(String newContent, int newTokenEstimate, String newArchiveRef) {
        return new ContextEntry(entryId, kind, messageId, sequenceNo, newContent, newTokenEstimate,
                evidenceIds, newArchiveRef);
    }
}
