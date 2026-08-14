package com.bank.aml.config;

import com.bank.aml.agent.AgentAssistant;
import com.bank.aml.agent.StreamingAnalysisAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 流式风险分析 Agent：使用 purpose=summary 的显式包装模型，异步回调中指标不落 unknown */
    @Bean
    public StreamingAnalysisAgent streamingAnalysisAgent(
            @Qualifier("summaryStreamingChatModel") StreamingChatModel streamingChatModel) {
        return AiServices.builder(StreamingAnalysisAgent.class)
                .streamingChatModel(streamingChatModel)
                .build();
    }
}
