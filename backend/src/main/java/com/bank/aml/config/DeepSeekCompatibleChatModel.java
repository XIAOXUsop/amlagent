package com.bank.aml.config;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/**
 * DeepSeek OpenAI 兼容层。
 *
 * <p>部分上游 OpenAI 客户端会在单次请求中附加 extended prompt cache 参数，
 * 但 DeepSeek 的上下文缓存由服务端自动启用，不支持这些字段。该包装器在最终出站前
 * 删除不兼容字段，避免请求以 {@code prompt_cache_retention is not supported on this model}
 * 失败；其他参数（包括 DeepSeek 的 thinking 配置）保持不变。</p>
 */
final class DeepSeekCompatibleChatModel implements ChatModel {

    private final ChatModel delegate;

    DeepSeekCompatibleChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(DeepSeekRequestSanitizer.sanitize(request));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return delegate.chat(DeepSeekRequestSanitizer.sanitize(request), options);
    }

    @Override
    public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
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
