package com.bank.aml.tools;

import com.bank.aml.rag.LegalDoc;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 法规检索格式化工具：把法规检索结果格式化为可读文本（含证据 ID）。
 * <p>当前作为纯静态格式化器被 {@link SnapshotToolSuite} 复用。真正的法规检索（RAG）
 * 由 {@link com.bank.aml.rag.LegalDocumentSearcher} 在快照冻结时完成；Agent 不再直接访问实时检索。
 */
public final class LegalSearchTool {

    private LegalSearchTool() {
    }

    /** 从法规检索结果生成文本（快照套件复用同一格式化逻辑） */
    public static String format(List<LegalDoc> docs) {
        if (docs.isEmpty()) {
            return "未检索到相关法规条文。";
        }
        return docs.stream()
                .map(d -> "【" + d.title() + "】[证据ID: " + d.evidenceId() + "]"
                        + (d.articleNumber() == null || d.articleNumber().isEmpty() ? "" : "（" + d.articleNumber() + "）")
                        + "\n" + d.content())
                .collect(Collectors.joining("\n\n"));
    }
}
