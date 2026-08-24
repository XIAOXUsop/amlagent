package com.bank.aml.assistant.evaluation;

import java.util.List;

public record AssistantEvalDataset(String datasetId, String version, String sourceType, List<EvalCase> cases) {
    public AssistantEvalDataset { cases = cases == null ? List.of() : List.copyOf(cases); }

    public record EvalCase(String id, String category, String input, String expectedIntent,
                           String expectedResult, boolean mustCite) {}
}
