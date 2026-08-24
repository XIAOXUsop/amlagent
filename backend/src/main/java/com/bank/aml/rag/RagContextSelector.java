package com.bank.aml.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对精排结果做证据去重、单文档限额和上下文预算控制，避免一个法规霸占全部上下文。 */
@Component
public class RagContextSelector {
    public List<LegalDoc> select(List<LegalDoc> ranked, int maxPerDocument,
                                 int maxCharacters, double duplicateThreshold) {
        List<LegalDoc> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        int characters = 0;
        for (LegalDoc candidate : ranked) {
            String documentKey = candidate.metadata().documentId();
            if (documentKey == null || documentKey.isBlank()) documentKey = candidate.title();
            if (perDocument.getOrDefault(documentKey, 0) >= maxPerDocument) continue;
            if (selected.stream().anyMatch(existing -> duplicate(existing.content(), candidate.content(), duplicateThreshold))) {
                continue;
            }
            int length = candidate.content() == null ? 0 : candidate.content().length();
            if (!selected.isEmpty() && characters + length > maxCharacters) continue;
            selected.add(candidate);
            characters += length;
            perDocument.merge(documentKey, 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    private boolean duplicate(String left, String right, double threshold) {
        if (left == null || right == null || left.equals(right)) return java.util.Objects.equals(left, right);
        Set<String> a = trigrams(left);
        Set<String> b = trigrams(right);
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size() >= threshold;
    }

    private Set<String> trigrams(String value) {
        String normalized = value.replaceAll("\\s+", "");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 2 < normalized.length(); i++) result.add(normalized.substring(i, i + 3));
        return result;
    }
}
