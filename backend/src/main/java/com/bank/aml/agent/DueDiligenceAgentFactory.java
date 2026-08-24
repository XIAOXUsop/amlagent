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

    /** 创建 Agent 并返回工具套件，供工作流读取工具调用轨迹 */
    public AgentWithTools createWithTraces(InvestigationSnapshot snapshot) {
        SnapshotToolSuite tools = new SnapshotToolSuite(snapshot);
        DueDiligenceAgent agent = build(chatModel, tools);
        return new AgentWithTools(agent, tools);
    }

    /** 生产与离线评测共享的 Agent 构建策略，防止工具并发和轮次上限发生配置漂移。 */
    public static DueDiligenceAgent build(ChatModel chatModel, Object tools) {
        return AiServices.builder(DueDiligenceAgent.class)
                .chatModel(chatModel)
                .tools(tools)
                .executeToolsConcurrently()
                // 两轮工具采集/纠错后仍需允许模型生成最终结构化结果；低于 3 会把合法收尾误判为循环。
                .maxToolCallingRoundTrips(3)
                .build();
    }

    public record AgentWithTools(DueDiligenceAgent agent, SnapshotToolSuite tools) {
    }
}
