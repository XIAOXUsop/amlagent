package com.bank.aml.agent;

import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDocumentSearcher;
import com.bank.aml.tools.SnapshotToolSuite;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

/**
 * 合规尽调 Agent 工厂：每个工单动态创建一个绑定冻结快照的 {@link DueDiligenceAgent}。
 * <p>快照工具只读 {@link InvestigationSnapshot}，不再访问 {@code CustomerDataPort}，
 * 从而保证 Agent 推理与 Guardrails 校验共享同一份业务事实（Snapshot First）。
 */
@Component
public class DueDiligenceAgentFactory {

    private final ChatModel chatModel;
    private final LegalDocumentSearcher searcher;

    public DueDiligenceAgentFactory(ChatModel chatModel, LegalDocumentSearcher searcher) {
        this.chatModel = chatModel;
        this.searcher = searcher;
    }

    public DueDiligenceAgent create(InvestigationSnapshot snapshot) {
        SnapshotToolSuite tools = new SnapshotToolSuite(snapshot, searcher);
        return AiServices.builder(DueDiligenceAgent.class)
                .chatModel(chatModel)
                .tools(tools)
                .executeToolsConcurrently()
                // 循环防护：限制工具调用最大轮次，防止 Agent 无限循环
                .maxToolCallingRoundTrips(5)
                .build();
    }
}
