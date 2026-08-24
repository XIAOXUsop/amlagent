package com.bank.aml.assistant.rag;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 经人工维护的公开银行知识基线；启动时严格校验来源、时效和标识，运行时只返回冻结摘要。 */
@Component
public class AssistantPublicKnowledgeCatalog {
    private static final Set<String> OFFICIAL_HOST_SUFFIXES = Set.of("pbc.gov.cn", "moj.gov.cn", "gov.cn");
    private final Catalog catalog;

    public AssistantPublicKnowledgeCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("assistant/banking-public-v1.json").getInputStream()) {
            this.catalog = objectMapper.readValue(input, Catalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("AI 小助公开知识库加载失败", exception);
        }
        validate(catalog);
    }

    public String version() { return catalog.version(); }

    public List<AssistantEvidence> retrieve(String query, Instant asOfTime, int limit) {
        String normalized = normalize(query);
        LocalDate asOf = (asOfTime == null ? Instant.now() : asOfTime).atZone(ZoneOffset.UTC).toLocalDate();
        return catalog.documents().stream()
                .filter(document -> !asOf.isBefore(document.effectiveFrom()))
                .map(document -> new Scored(document, score(normalized, document)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(item -> item.document().id()))
                .limit(Math.max(1, Math.min(limit, 10)))
                .map(item -> item.document().toEvidence())
                .toList();
    }

    private int score(String query, Document document) {
        int score = 0;
        for (String keyword : document.keywords()) {
            String term = normalize(keyword);
            if (!term.isBlank() && (query.contains(term) || term.contains(query))) score += Math.max(1, term.length());
        }
        String title = normalize(document.title());
        if (!title.isBlank() && query.contains(title)) score += 10;
        return score;
    }

    private void validate(Catalog value) {
        if (value == null || value.version() == null || !value.version().matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalStateException("AI 小助公开知识库版本非法");
        }
        if (value.documents() == null || value.documents().isEmpty()) {
            throw new IllegalStateException("AI 小助公开知识库不能为空");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (Document document : value.documents()) {
            if (document == null || document.id() == null || !document.id().matches("KB-[A-Z0-9-]{6,64}")
                    || !ids.add(document.id())) throw new IllegalStateException("公开知识 evidenceId 非法或重复");
            if (document.type() != AssistantEvidence.EvidenceType.AML_LEGAL
                    && document.type() != AssistantEvidence.EvidenceType.BANKING_PUBLIC) {
                throw new IllegalStateException("公开知识类型超出只读助手范围");
            }
            if (blank(document.title()) || blank(document.summary()) || document.summary().length() > 800
                    || blank(document.source()) || document.effectiveFrom() == null
                    || document.keywords() == null || document.keywords().isEmpty()) {
                throw new IllegalStateException("公开知识元数据不完整: " + document.id());
            }
            URI uri = URI.create(document.sourceUrl());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || OFFICIAL_HOST_SUFFIXES.stream()
                    .noneMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix))) {
                throw new IllegalStateException("公开知识来源不在官方域名白名单: " + document.id());
            }
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public record Catalog(String version, List<Document> documents) {
        public Catalog { documents = documents == null ? List.of() : List.copyOf(documents); }
    }

    public record Document(String id, AssistantEvidence.EvidenceType type, String title, String summary,
                           String source, String sourceUrl, LocalDate effectiveFrom, List<String> keywords) {
        public Document { keywords = keywords == null ? List.of() : List.copyOf(keywords); }
        AssistantEvidence toEvidence() {
            return new AssistantEvidence(id, type, title, summary, source + " | " + sourceUrl);
        }
    }

    private record Scored(Document document, int score) {}
}
