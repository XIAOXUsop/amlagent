package com.bank.aml.config;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DeepSeek OpenAI 兼容接口的出站请求参数清洗器。 */
final class DeepSeekRequestSanitizer {

    private static final Set<String> UNSUPPORTED_CUSTOM_PARAMETERS = Set.of(
            "prompt_cache_retention",
            "prompt_caching_retention"
    );

    private DeepSeekRequestSanitizer() {
    }

    static ChatRequest sanitize(ChatRequest request) {
        ChatRequestParameters sanitized = sanitize(request.parameters());
        if (sanitized == request.parameters()) {
            return request;
        }
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(sanitized)
                .build();
    }

    static ChatRequestParameters sanitize(ChatRequestParameters parameters) {
        if (!(parameters instanceof OpenAiChatRequestParameters openAiParameters)) {
            return parameters;
        }
        Map<String, Object> custom = openAiParameters.customParameters();
        if (custom == null || custom.isEmpty() || custom.keySet().stream().noneMatch(DeepSeekRequestSanitizer::isUnsupported)) {
            return parameters;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        custom.forEach((key, value) -> {
            if (!isUnsupported(key)) {
                sanitized.put(key, value);
            }
        });
        return OpenAiChatRequestParameters.builder()
                .overrideWith(openAiParameters)
                .customParameters(sanitized)
                .build();
    }

    private static boolean isUnsupported(String key) {
        return key != null && UNSUPPORTED_CUSTOM_PARAMETERS.contains(key.toLowerCase(Locale.ROOT));
    }
}
