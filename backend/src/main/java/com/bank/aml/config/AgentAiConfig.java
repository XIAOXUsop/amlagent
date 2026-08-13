package com.bank.aml.config;

import com.bank.aml.agent.AgentAssistant;
import com.bank.aml.agent.DueDiligenceAgent;
import com.bank.aml.agent.StreamingAnalysisAgent;
import com.bank.aml.tools.CorporateTool;
import com.bank.aml.tools.LegalSearchTool;
import com.bank.aml.tools.SanctionTool;
import com.bank.aml.tools.TransactionTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AiServices 装配：将 {@link ChatModel} 与工具注入生成 agent 实例。
 */
@Configuration
public class AgentAiConfig {

    @Bean
    public AgentAssistant agentAssistant(ChatModel chatModel) {
        return AiServices.builder(AgentAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /** 合规尽调 Agent：绑定四个数据工具，支持并行工具调用 */
    @Bean
    public DueDiligenceAgent dueDiligenceAgent(ChatModel chatModel,
                                               TransactionTool transactionTool,
                                               CorporateTool corporateTool,
                                               SanctionTool sanctionTool,
                                               LegalSearchTool legalSearchTool) {
        return AiServices.builder(DueDiligenceAgent.class)
                .chatModel(chatModel)
                .tools(transactionTool, corporateTool, sanctionTool, legalSearchTool)
                .executeToolsConcurrently()
                // 循环防护：限制工具调用最大轮次，防止 Agent 无限循环（配合 prompt 中"工具轮次上限 5"的软约束）
                .maxToolCallingRoundTrips(5)
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
