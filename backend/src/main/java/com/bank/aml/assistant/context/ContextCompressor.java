package com.bank.aml.assistant.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 确定性上下文压缩。
 *
 * <p>
 * **绝不调用模型**，这是本类的核心设计约束，有三重理由：
 * <ol>
 * <li>Spec §10.1 明写"摘要不得引入新事实"，而模型摘要恰恰无法对此作出保证；</li>
 * <li>压缩若调用模型，成本与延迟会随压缩频率线性上升，与"控制成本"的初衷相悖；</li>
 * <li>非确定性压缩会让"模型这次看到了什么"无法复现，破坏审计前提。</li>
 * </ol>
 *
 * <p>
 * 压缩策略按类型分派：证据类逐字保留（零失真），正文类保留头尾 + 归档索引。 所有字符预算由整数运算得出，不读取任何可变全局状态。
 */
public final class ContextCompressor {

    private final int answerHeadChars;

    private final int answerTailChars;

    public ContextCompressor(int answerHeadChars, int answerTailChars) {
        if (answerHeadChars < 0 || answerTailChars < 0) {
            throw new IllegalArgumentException("压缩头尾长度不能为负");
        }
        this.answerHeadChars = answerHeadChars;
        this.answerTailChars = answerTailChars;
    }

    /**
     * 压缩一条条目。不可压缩的类型（用户输入）与证据类**原样返回**。
     * @param entry 待压缩条目
     * @param archiveRef 归档引用键，用于在压缩文本里留下精确取回入口
     * @return 压缩结果；{@code truncated} 为 false 时表示未发生压缩
     */
    public CompressedEntry compress(ContextEntry entry, String archiveRef) {
        if (!entry.kind().compressible()) {
            return new CompressedEntry(entry.content(), entry.evidenceIds(), false);
        }
        return compressAnswer(entry, archiveRef);
    }

    private CompressedEntry compressAnswer(ContextEntry entry, String archiveRef) {
        String content = entry.content() == null ? "" : entry.content();

        // 收集全部证据标识——压缩**绝不允许**丢掉任何一个，否则引用校验会失败
        List<String> evidenceIds = extractEvidenceIds(content);
        Set<String> distinct = new LinkedHashSet<>(evidenceIds);

        if (content.length() <= answerHeadChars + answerTailChars) {
            // 本就够短，压缩反而更费——直接保留原样，但仍登记证据供依赖图使用
            return new CompressedEntry(content, List.copyOf(distinct), false);
        }

        String head = content.substring(0, answerHeadChars);
        String tail = content.substring(content.length() - answerTailChars);
        int dropped = content.length() - answerHeadChars - answerTailChars;

        StringBuilder compressed = new StringBuilder(head.length() + tail.length() + 160);
        compressed.append(head);
        compressed.append("\n…[中段 ").append(dropped).append(" 字符已压缩");
        if (archiveRef != null && !archiveRef.isBlank()) {
            compressed.append("，可用 recallArchivedHistory(\"").append(archiveRef).append("\") 精确取回原文");
        }
        compressed.append("]…\n");
        // 被压缩掉的中段里若含证据标识，必须在压缩文本中补回，否则引用会凭空消失
        for (String evidenceId : distinct) {
            if (!compressed.toString().contains(evidenceId)) {
                compressed.append("\n- ").append(evidenceId);
            }
        }
        compressed.append(tail);

        return new CompressedEntry(compressed.toString(), List.copyOf(distinct), true);
    }

    /** 按首次出现顺序抽取证据标识（有序去重，保证确定性） */
    static List<String> extractEvidenceIds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        var matcher = com.bank.aml.assistant.domain.EvidenceIdPattern.PATTERN.matcher(text);
        while (matcher.find()) {
            String id = matcher.group();
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * @param content 压缩后进入模型的文本
     * @param retainedEvidenceIds 压缩后仍保留的证据标识（这个集合只增不减）
     * @param truncated 是否真的发生了压缩
     */
    public record CompressedEntry(String content, List<String> retainedEvidenceIds, boolean truncated) {
    }

}
