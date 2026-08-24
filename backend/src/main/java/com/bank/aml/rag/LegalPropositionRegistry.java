package com.bank.aml.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 人工审核法律命题表（resources/rag/legal-propositions.json）。
 * <p>由领域专家维护，把高影响处置动作与法规证据的结构化语义绑定，替代关键词共现式校验。</p>
 */
@Component
public class LegalPropositionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LegalPropositionRegistry.class);
    private static final String RESOURCE = "/rag/legal-propositions.json";

    private final List<LegalActionProposition> propositions;

    public LegalPropositionRegistry(ObjectMapper objectMapper) {
        this.propositions = load(objectMapper);
        log.info("法律命题表已装载 {} 条", propositions.size());
    }

    private List<LegalActionProposition> load(ObjectMapper objectMapper) {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                log.warn("法律命题表缺失（{}），动作支持校验将退化为无命题（fail-closed）", RESOURCE);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(input).path("propositions");
            List<LegalActionProposition> result = new ArrayList<>();
            for (JsonNode node : root) {
                result.add(objectMapper.treeToValue(node, LegalActionProposition.class));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("法律命题表装载失败：{}", e.getMessage());
            return List.of();
        }
    }

    public List<LegalActionProposition> findByCode(String actionCode) {
        if (actionCode == null) return List.of();
        return propositions.stream().filter(p -> actionCode.equals(p.actionCode())).toList();
    }

    public List<LegalActionProposition> all() {
        return propositions;
    }
}