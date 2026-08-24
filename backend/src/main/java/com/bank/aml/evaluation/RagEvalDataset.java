package com.bank.aml.evaluation;

import java.util.List;

/** 独立于在线索引生成逻辑的版本化 RAG 评测集。 */
public record RagEvalDataset(
        String datasetVersion,
        String reviewStatus,
        List<RagEvalCase> cases
) {
    public RagEvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record RagEvalCase(
            String id,
            String question,
            boolean answerable,
            String expectedTitleContains,
            String expectedContentContains,
            String category
    ) {
        public RagEvalCase {
            category = category == null ? "" : category;
        }
    }
}
