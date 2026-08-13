package com.bank.aml.evaluation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Case-scoped observer around the configured model.
 *
 * <p>The wrapper delegates model capabilities unchanged so LangChain4j can still select its
 * structured-output strategy. It records only run diagnostics; it never substitutes a response.</p>
 */
final class AgentEvalModelObserver implements ChatModel {

    private final ChatModel delegate;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private final AtomicInteger totalTokens = new AtomicInteger();
    private final AtomicReference<String> lastModelName = new AtomicReference<>();
    private final AtomicReference<String> lastAssistantText = new AtomicReference<>();
    private final AtomicReference<Throwable> lastError = new AtomicReference<>();
    private final List<RequestedToolCall> requestedTools = new CopyOnWriteArrayList<>();

    AgentEvalModelObserver(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        requestCount.incrementAndGet();
        try {
            ChatResponse response = delegate.chat(request, options);
            record(response);
            return response;
        } catch (RuntimeException exception) {
            lastError.set(exception);
            throw exception;
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    Snapshot snapshot() {
        Throwable error = lastError.get();
        return new Snapshot(
                requestCount.get(),
                inputTokens.get(),
                outputTokens.get(),
                totalTokens.get(),
                lastModelName.get(),
                lastAssistantText.get(),
                error == null ? null : error.getClass().getSimpleName() + ": " + safeMessage(error),
                List.copyOf(requestedTools)
        );
    }

    private void record(ChatResponse response) {
        if (response == null) {
            return;
        }
        if (response.modelName() != null && !response.modelName().isBlank()) {
            lastModelName.set(response.modelName());
        }
        AiMessage message = response.aiMessage();
        if (message != null && message.text() != null) {
            lastAssistantText.set(message.text());
        }
        if (message != null && message.hasToolExecutionRequests()) {
            message.toolExecutionRequests().forEach(request -> requestedTools.add(
                    new RequestedToolCall(request.name())
            ));
        }
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            add(inputTokens, usage.inputTokenCount());
            add(outputTokens, usage.outputTokenCount());
            add(totalTokens, usage.totalTokenCount());
        }
    }

    private static void add(AtomicInteger counter, Integer value) {
        if (value != null) {
            counter.addAndGet(value);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "no message" : message;
    }

    record Snapshot(
            int requestCount,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            String modelName,
            String lastAssistantText,
            String error,
            List<RequestedToolCall> requestedTools
    ) {
    }

    record RequestedToolCall(String toolName) {
    }
}
