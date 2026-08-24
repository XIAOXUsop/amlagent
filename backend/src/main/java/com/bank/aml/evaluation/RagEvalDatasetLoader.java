package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** 加载并校验固定 RAG DEV 数据集；禁止从当前向量表反向生成正式质量标签。 */
@Component
public class RagEvalDatasetLoader {

    private static final String RESOURCE = "evaluation/rag-cases-v2.json";
    private final RagEvalDataset dataset;
    private final String datasetHash;

    public RagEvalDatasetLoader(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            byte[] bytes = input.readAllBytes();
            this.dataset = objectMapper.readValue(bytes, RagEvalDataset.class);
            this.datasetHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            validate(dataset);
        } catch (Exception e) {
            throw new IllegalStateException("RAG 评测数据集加载失败: " + RESOURCE, e);
        }
    }

    public RagEvalDataset dataset() {
        return dataset;
    }

    public String datasetHash() {
        return datasetHash;
    }

    private void validate(RagEvalDataset value) {
        if (value.datasetVersion() == null || value.datasetVersion().isBlank() || value.cases().isEmpty()) {
            throw new IllegalArgumentException("RAG 评测数据集版本或案例为空");
        }
        Set<String> ids = new HashSet<>();
        for (RagEvalDataset.RagEvalCase c : value.cases()) {
            if (c.id() == null || c.id().isBlank() || !ids.add(c.id())) {
                throw new IllegalArgumentException("RAG 评测案例 ID 为空或重复");
            }
            if (c.question() == null || c.question().isBlank()) {
                throw new IllegalArgumentException("RAG 评测问题为空: " + c.id());
            }
            if (c.answerable() && (blank(c.expectedTitleContains()) || blank(c.expectedContentContains()))) {
                throw new IllegalArgumentException("可回答案例缺少独立标签: " + c.id());
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
