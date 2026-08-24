package com.bank.aml.config;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;

/**
 * DeepSeek 流式模型兼容层，与同步模型共享同一套请求参数清洗规则。
 */
final class DeepSeekCompatibleStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;

    DeepSeekCompatibleStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(DeepSeekRequestSanitizer.sanitize(request), handler);
    }

    @Override
    public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        delegate.chat(DeepSeekRequestSanitizer.sanitize(request), options, handler);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return DeepSeekRequestSanitizer.sanitize(delegate.defaultRequestParameters());
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
