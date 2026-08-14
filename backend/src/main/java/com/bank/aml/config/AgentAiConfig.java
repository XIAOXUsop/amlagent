package com.bank.aml.config;

import com.bank.aml.agent.AgentAssistant;
import com.bank.aml.agent.StreamingAnalysisAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AiServices 装配。
 * <p>合规尽调 Agent 由 {@code DueDiligenceAgentFactory} 按工单动态创建并绑定冻结快照（Snapshot First），
 * 因此不再在此处注册全局单例 Agent。
 */
@Configuration
public class AgentAiConfig {

    @Bean
    public AgentAssistant agentAssistant(ChatModel chatModel) {
        return AiServices.builder(AgentAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /** 流式风险分析 Agent：token 级流式输出分析过程 */
    @Bean
    public StreamingAnalysisAgent streamingAnalysisAgent(StreamingChatModel streamingChatModel) {
        return AiServices.builder(StreamingAnalysisAgent.class)
                .streamingChatModel(streamingChatModel)
                .build();
    }
}
