package com.bank.aml.observability.genai;

import com.bank.aml.observability.ModelInvocationTags;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OTel GenAI 语义约定埋点测试：断言 span 名称与 {@code gen_ai.*} 属性，
 * 不依赖任何追踪后端（{@link SimpleTracer} 在内存中保留 span）。
 */
class GenAiTracingChatModelTest {

    private static final ModelInvocationTags TAGS =
            new ModelInvocationTags("deepseek", "deepseek-chat", "main_agent");

    private final SimpleTracer tracer = new SimpleTracer();

    @Test
    void recordsRequestAndUsageAttributesOnSuccess() {
        ChatModel delegate = stubModel(request -> ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .modelName("deepseek-chat-0711")
                .tokenUsage(new TokenUsage(120, 34))
                .finishReason(FinishReason.STOP)
                .build());

        new GenAiTracingChatModel(delegate, tracer, TAGS).chat(ChatRequest.builder().messages(UserMessage.from("测试请求")).build());

        SimpleSpan span = tracer.onlySpan();
        assertThat(span.getName()).isEqualTo("chat deepseek-chat");
        assertThat(span.getTags()).containsEntry("gen_ai.operation.name", "chat")
                .containsEntry("gen_ai.provider.name", "deepseek")
                .containsEntry("gen_ai.request.model", "deepseek-chat")
                .containsEntry("gen_ai.response.model", "deepseek-chat-0711")
                .containsEntry("gen_ai.usage.input_tokens", "120")
                .containsEntry("gen_ai.usage.output_tokens", "34")
                .containsEntry("gen_ai.response.finish_reasons", "stop")
                .containsEntry("aml.purpose", "main_agent");
    }

    @Test
    void requestModelOverridesConfiguredModel() {
        ChatModel delegate = stubModel(request -> ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        new GenAiTracingChatModel(delegate, tracer, TAGS)
                .chat(ChatRequest.builder().messages(UserMessage.from("测试请求"))
                        .modelName("deepseek-reasoner").build());

        assertThat(tracer.onlySpan().getName()).isEqualTo("chat deepseek-reasoner");
        assertThat(tracer.onlySpan().getTags()).containsEntry("gen_ai.request.model", "deepseek-reasoner");
    }

    @Test
    void missingUsageAndFinishReasonDoNotBreakTracing() {
        ChatModel delegate = stubModel(request -> ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        new GenAiTracingChatModel(delegate, tracer, TAGS).chat(ChatRequest.builder().messages(UserMessage.from("测试请求")).build());

        SimpleSpan span = tracer.onlySpan();
        assertThat(span.getName()).isEqualTo("chat deepseek-chat");
        assertThat(span.getTags()).doesNotContainKeys(
                "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.response.finish_reasons");
    }

    @Test
    void failureIsTaggedWithErrorTypeAndRethrown() {
        ChatModel delegate = stubModel(request -> {
            throw new IllegalStateException("模型不可用");
        });

        GenAiTracingChatModel model = new GenAiTracingChatModel(delegate, tracer, TAGS);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("测试请求")).build();

        assertThatThrownBy(() -> model.chat(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型不可用");

        SimpleSpan span = tracer.onlySpan();
        assertThat(span.getTags()).containsEntry("error.type", "java.lang.IllegalStateException");
        assertThat(span.getError()).isNotNull().hasMessageContaining("模型不可用");
    }

    @Test
    void doesNotRecordPromptOrCompletionContent() {
        ChatModel delegate = stubModel(request -> ChatResponse.builder()
                .aiMessage(AiMessage.from("客户张三，身份证 110101199003078531"))
                .build());

        new GenAiTracingChatModel(delegate, tracer, TAGS).chat(ChatRequest.builder().messages(UserMessage.from("测试请求")).build());

        // AML 场景下把客户数据写进追踪后端等同泄露，这里断言 span 里不含任何提示词/补全内容
        assertThat(tracer.onlySpan().getTags().values())
                .noneMatch(value -> value.contains("张三") || value.contains("110101199003078531"));
    }

    /** 最小可用 ChatModel 打桩：只覆写 chat(ChatRequest)，其余走接口默认实现 */
    private static ChatModel stubModel(java.util.function.Function<ChatRequest, ChatResponse> behaviour) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return behaviour.apply(request);
            }

            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return ChatRequestParameters.builder().build();
            }

            @Override
            public Set<Capability> supportedCapabilities() {
                return Set.of();
            }

            @Override
            public List<ChatModelListener> listeners() {
                return List.of();
            }

            @Override
            public ModelProvider provider() {
                return ModelProvider.OTHER;
            }
        };
    }
}
