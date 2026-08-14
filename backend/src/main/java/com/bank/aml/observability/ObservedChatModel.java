package com.bank.aml.observability;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/**
 * 可观测同步 ChatModel 包装器：显式携带固定 purpose 标签，替代 ThreadLocal，
 * 保证主 Agent / 评测等用途的成本指标不被异步回调线程污染。
 */
public final class ObservedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final MetricsRecorder metrics;
    private final ModelInvocationTags tags;

    public ObservedChatModel(ChatModel delegate, MetricsRecorder metrics, ModelInvocationTags tags) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.tags = tags;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long start = System.nanoTime();
        metrics.llmRequest(tags);
        try {
            ChatResponse response = delegate.chat(request);
            var usage = response.tokenUsage();
            if (usage != null) {
                long input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
                long output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
                metrics.llmTokens(tags, input, output);
            }
            metrics.llmDuration(tags, elapsedMs(start));
            return response;
        } catch (Exception e) {
            metrics.llmError(tags);
            throw e;
        }
    }

    private long elapsedMs(long startNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
        return elapsed == 0L ? 0L : Math.max(1L, elapsed / 1_000_000L);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }
}
