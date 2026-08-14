package com.bank.aml.agent;

import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.tools.SnapshotToolSuite;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 合规尽调 Agent 工厂：每个工单动态创建一个绑定冻结快照的 {@link DueDiligenceAgent}。
 * <p>快照工具只读 {@link InvestigationSnapshot}（含预检索法规证据），不再访问 {@code CustomerDataPort}
 * 或可变 RAG 索引，从而保证 Agent 推理与 Guardrails 校验共享同一份业务事实（Snapshot First）。
 */
@Component
public class DueDiligenceAgentFactory {

    private final ChatModel chatModel;

    public DueDiligenceAgentFactory(@Qualifier("mainAgentChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public DueDiligenceAgent create(InvestigationSnapshot snapshot) {
        return createWithTraces(snapshot).agent();
    }

    /** 创建 Agent 并返回工具套件，供工作流读取工具调用轨迹 */
    public AgentWithTools createWithTraces(InvestigationSnapshot snapshot) {
        SnapshotToolSuite tools = new SnapshotToolSuite(snapshot);
        DueDiligenceAgent agent = AiServices.builder(DueDiligenceAgent.class)
                .chatModel(chatModel)
                .tools(tools)
                .executeToolsConcurrently()
                // 循环防护：限制工具调用最大轮次，防止 Agent 无限循环
                .maxToolCallingRoundTrips(5)
                .build();
        return new AgentWithTools(agent, tools);
    }

    public record AgentWithTools(DueDiligenceAgent agent, SnapshotToolSuite tools) {
    }
}
