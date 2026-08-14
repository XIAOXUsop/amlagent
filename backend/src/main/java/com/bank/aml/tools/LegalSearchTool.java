package com.bank.aml.tools;

import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalDocumentSearcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 合规法规检索工具：RAG 检索反洗钱监管法规条文，供报告引用。
 * <p>Phase 2 由关键词检索实现，Phase 3 切换为 PGVector 向量检索，工具签名保持不变。
 */
@Component
public class LegalSearchTool {

    private final LegalDocumentSearcher searcher;

    public LegalSearchTool(LegalDocumentSearcher searcher) {
        this.searcher = searcher;
    }

    @Tool("检索反洗钱监管法规条文，返回与查询相关的法规标题、证据ID(evidenceId)与条文原文，供尽调报告引用")
    public String searchLegal(@P("法规查询关键词，如'大额交易报告'或'客户尽职调查'") String query) {
        return format(searcher.search(query, 3));
    }

    /** 从法规检索结果生成文本（快照工具与 Spring 工具复用同一格式化逻辑） */
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
