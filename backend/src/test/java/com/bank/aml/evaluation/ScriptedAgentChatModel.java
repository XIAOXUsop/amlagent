package com.bank.aml.evaluation;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process protocol driver. It has no provider, API key, client or network connection.
 */
final class ScriptedAgentChatModel implements ChatModel {

    private final List<ChatResponse> responses;

    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    private final AtomicInteger nextResponse = new AtomicInteger();

    ScriptedAgentChatModel(List<ChatResponse> responses) {
        this.responses = List.copyOf(responses);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        int index = nextResponse.getAndIncrement();
        if (index >= responses.size()) {
            throw new IllegalStateException("Synthetic model transport failure");
        }
        return responses.get(index);
    }

    List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    static ChatResponse toolCalls(List<ToolExecutionRequest> requests) {
        return ChatResponse.builder()
            .aiMessage(AiMessage.from(requests))
            .finishReason(FinishReason.TOOL_EXECUTION)
            .build();
    }

    static ChatResponse finalText(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(FinishReason.STOP).build();
    }

}
