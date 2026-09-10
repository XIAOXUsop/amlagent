package com.bank.aml.observability.genai;

import com.bank.aml.observability.ModelInvocationTags;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按 <b>OpenTelemetry GenAI 语义约定</b>为同步模型调用开 span 的装饰器。
 *
 * <p>与 {@code ObservedChatModel}（Micrometer 指标）互补而非替代：
 * 指标回答"总体调用了多少、P95 多少"，span 回答"<b>这一次</b>调用发生在哪条链路、
 * 用了哪个模型、token 与结束原因是什么"。两者配合才能既看大盘又能下钻单次调用。
 *
 * <p>装饰器形态与已有 {@code ObservedChatModel} 保持一致：purpose 由构造时显式传入，
 * 不依赖 ThreadLocal（异步回调线程不会传播 ThreadLocal，会导致 purpose 丢失）。
 *
 * <p>注意：span 只记录模型名、token 数、结束原因等<b>元数据</b>，
 * <b>绝不记录 prompt 或补全内容</b>——AML 场景下那等同于把客户数据写进追踪后端。
 */
public final class GenAiTracingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Tracer tracer;
    private final ModelInvocationTags tags;

    public GenAiTracingChatModel(ChatModel delegate, Tracer tracer, ModelInvocationTags tags) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.tags = tags;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String model = resolveRequestModel(request);
        Span span = tracer.nextSpan().name(GenAiSemanticConventions.spanName(model)).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            tagRequest(span, model);
            ChatResponse response = delegate.chat(request);
            tagResponse(span, response);
            return response;
        } catch (RuntimeException e) {
            span.tag(GenAiSemanticConventions.ERROR_TYPE, e.getClass().getName());
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void tagRequest(Span span, String model) {
        span.tag(GenAiSemanticConventions.OPERATION_NAME, GenAiSemanticConventions.OPERATION_CHAT);
        span.tag(GenAiSemanticConventions.PROVIDER_NAME, tags.provider());
        span.tag(GenAiSemanticConventions.REQUEST_MODEL, model);
        span.tag(GenAiSemanticConventions.AML_PURPOSE, tags.purpose());
    }

    private void tagResponse(Span span, ChatResponse response) {
        String responseModel = response.modelName();
        if (responseModel != null && !responseModel.isBlank()) {
            span.tag(GenAiSemanticConventions.RESPONSE_MODEL, responseModel);
        }

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null) {
                span.tag(GenAiSemanticConventions.USAGE_INPUT_TOKENS, String.valueOf(usage.inputTokenCount()));
            }
            if (usage.outputTokenCount() != null) {
                span.tag(GenAiSemanticConventions.USAGE_OUTPUT_TOKENS, String.valueOf(usage.outputTokenCount()));
            }
        }

        FinishReason finishReason = response.finishReason();
        if (finishReason != null) {
            span.tag(GenAiSemanticConventions.RESPONSE_FINISH_REASONS,
                    finishReason.name().toLowerCase(Locale.ROOT));
        }
    }

    /** 请求未指定模型时回落到配置的模型名 */
    private String resolveRequestModel(ChatRequest request) {
        String requested = request == null ? null : request.modelName();
        return requested == null || requested.isBlank() ? tags.model() : requested;
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
