package com.bank.aml.observability;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;

/**
 * 可观测流式 ChatModel 包装器：显式携带固定 purpose 标签，
 * 在流式完成/出错回调中记录请求、Token 与错误数，替代依赖 ThreadLocal 的方案
 * （异步回调线程不传播 ThreadLocal，导致 purpose=unknown）。
 */
public final class ObservedStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final MetricsRecorder metrics;
    private final ModelInvocationTags tags;

    public ObservedStreamingChatModel(StreamingChatModel delegate, MetricsRecorder metrics, ModelInvocationTags tags) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.tags = tags;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        metrics.llmRequest(tags.purpose());
        delegate.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                var usage = response.tokenUsage();
                if (usage != null && usage.totalTokenCount() != null) {
                    metrics.llmTokens(tags.purpose(), usage.totalTokenCount());
                }
                handler.onCompleteResponse(response);
            }

            @Override
            public void onError(Throwable error) {
                metrics.llmError(tags.purpose());
                handler.onError(error);
            }
        });
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
