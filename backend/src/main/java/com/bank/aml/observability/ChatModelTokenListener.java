package com.bank.aml.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.springframework.stereotype.Component;

/**
 * LLM Token 计量监听器：每次模型响应记录请求数与 Token 数，
 * 量化 AI 应用成本，经 /actuator/prometheus 暴露 aml_llm_token_total / aml_llm_request_total。
 */
@Component
public class ChatModelTokenListener implements ChatModelListener {

    private final MetricsRecorder metrics;

    public ChatModelTokenListener(MetricsRecorder metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        metrics.llmRequest();
        var tokenUsage = context.chatResponse().tokenUsage();
        if (tokenUsage != null && tokenUsage.totalTokenCount() != null) {
            metrics.llmTokens(tokenUsage.totalTokenCount());
        }
    }
}
