package com.bank.aml.assistant.agent;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CustomerAssistantAgentFactory {
    private final StreamingChatModel model;
    private final ObjectMapper objectMapper;
    private final AssistantProperties properties;

    public CustomerAssistantAgentFactory(
            @Qualifier("assistantStreamingChatModel") StreamingChatModel model,
            ObjectMapper objectMapper, AssistantProperties properties) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AgentWithTools create(CustomerAssistantSnapshot snapshot, ChatMemory memory) {
        CustomerAssistantToolSuite tools = new CustomerAssistantToolSuite(snapshot, objectMapper);
        CustomerAssistantAgent agent = AiServices.builder(CustomerAssistantAgent.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> {
                    if (!snapshot.conversationId().equals(String.valueOf(memoryId))) {
                        throw new IllegalArgumentException("会话 memoryId 与快照不匹配");
                    }
                    return memory;
                })
                .tools(tools)
                .toolExecutionErrorHandler((error, context) ->
                        ToolErrorHandlerResult.text(tools.recoverableError(error)))
                .maxToolCallingRoundTrips(properties.getMaxToolRoundTrips())
                .build();
        return new AgentWithTools(agent, tools);
    }

    public record AgentWithTools(CustomerAssistantAgent agent, CustomerAssistantToolSuite tools) {}
}
