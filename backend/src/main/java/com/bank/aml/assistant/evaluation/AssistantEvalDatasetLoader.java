package com.bank.aml.assistant.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 合成助手评测集加载与发布门禁；数据不含真实客户资料。 */
@Component
public class AssistantEvalDatasetLoader {
    private static final Map<String, Long> MIN_COUNTS = Map.of(
            "CUSTOMER_ANALYSIS", 20L, "BANKING_KNOWLEDGE", 15L, "ATTACK", 15L,
            "INSUFFICIENT_EVIDENCE", 10L, "ISOLATION", 10L);
    private final AssistantEvalDataset dataset;

    public AssistantEvalDatasetLoader(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("evaluation/assistant-cases-v1.json").getInputStream()) {
            dataset = objectMapper.readValue(input, AssistantEvalDataset.class);
        } catch (IOException exception) {
            throw new IllegalStateException("AI 小助评测集加载失败", exception);
        }
        validate(dataset);
    }

    public AssistantEvalDataset load() { return dataset; }

    private void validate(AssistantEvalDataset value) {
        if (value == null || !"SYNTHETIC_ONLY".equals(value.sourceType()) || value.cases().size() < 70) {
            throw new IllegalStateException("AI 小助评测集必须至少包含 70 条纯合成案例");
        }
        Set<String> ids = value.cases().stream().map(AssistantEvalDataset.EvalCase::id).collect(Collectors.toSet());
        if (ids.size() != value.cases().size()) throw new IllegalStateException("AI 小助评测案例 ID 重复");
        Map<String, Long> counts = value.cases().stream().collect(Collectors.groupingBy(
                AssistantEvalDataset.EvalCase::category, Collectors.counting()));
        MIN_COUNTS.forEach((category, minimum) -> {
            if (counts.getOrDefault(category, 0L) < minimum) {
                throw new IllegalStateException("AI 小助评测场景不足: " + category);
            }
        });
        for (var item : value.cases()) {
            if (item.id() == null || item.input() == null || item.input().isBlank()
                    || item.expectedIntent() == null || item.expectedResult() == null) {
                throw new IllegalStateException("AI 小助评测案例字段不完整");
            }
        }
    }
}
