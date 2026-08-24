package com.bank.aml.rag.ingestion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按法规章/条结构切分；子块保留父标题，兼顾精确召回与完整语境。 */
@Component
public class LegalDocumentChunker {
    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千0-9]+条");
    private static final Pattern DOC_NUMBER = Pattern.compile("[（(]?[^（(]*令[〔【]?\\d{4}[〕】]?第\\d+号[）)]?");
    private static final int MAX_CHARS = 1_200;
    private static final Set<String> ALLOWED_SCOPES = Set.of("PUBLIC_LEGAL", "AML_INTERNAL");
    private static final Set<String> ALLOWED_SECURITY = Set.of("TRUSTED", "PENDING_REVIEW", "UNTRUSTED_METADATA");
    private static final Pattern RAG_POLICY = Pattern.compile("<!--\\s*rag:(.*?)-->", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public List<TextSegment> chunk(Path file, String corpusVersion) throws java.io.IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String title = firstHeading(source, file);
        String documentNumber = documentNumber(source, title);
        String documentId = "DOC-" + sha256(file.getFileName() + "|" + title).substring(0, 16);
        DocumentPolicy policy = policy(source, file);

        List<Section> sections = sections(RAG_POLICY.matcher(source).replaceAll(""));
        List<TextSegment> result = new ArrayList<>();
        int ordinal = 0;
        for (Section section : sections) {
            for (String child : bounded(section.content())) {
                String text = section.heading().isBlank() ? child : section.heading() + "\n" + child;
                if (text.strip().length() < 20) continue;
                String article = article(section.heading() + " " + child);
                String digest = sha256(text.strip());
                String evidenceId = "LEGAL-" + sha256(title + "|" + article + "|" + text.strip()).substring(0, 16);
                String chunkId = "CHUNK-" + sha256(documentId + "|" + ordinal++ + "|" + digest).substring(0, 16);
                Metadata metadata = new Metadata()
                        .put("title", title)
                        .put("documentNumber", documentNumber)
                        .put("articleNumber", article)
                        .put("evidenceId", evidenceId)
                        .put("documentId", documentId)
                        .put("chunkId", chunkId)
                        .put("parentSection", section.heading())
                        .put("jurisdiction", policy.jurisdiction())
                        .put("accessScopes", policy.accessScopes().stream().sorted().collect(java.util.stream.Collectors.joining(",")))
                        .put("contentDigest", digest)
                        .put("sourceFile", file.getFileName().toString())
                        // 可信状态只能由显式策略头赋予，绝不默认放行
                        .put("securityStatus", policy.securityStatus())
                        .put("corpusVersion", corpusVersion);
                if (policy.effectiveFrom() != null) metadata.put("effectiveFrom", policy.effectiveFrom().toString());
                if (policy.effectiveTo() != null) metadata.put("effectiveTo", policy.effectiveTo().toString());
                result.add(TextSegment.from(text.strip(), metadata));
            }
        }
        return result;
    }

    private List<Section> sections(String source) {
        List<Section> result = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.startsWith("## ")) {
                flush(result, heading, body);
                heading = line.substring(3).strip();
            } else if (!line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        flush(result, heading, body);
        return result;
    }

    private void flush(List<Section> target, String heading, StringBuilder body) {
        String content = body.toString().strip();
        if (!content.isBlank()) target.add(new Section(heading, content));
        body.setLength(0);
    }

    private List<String> bounded(String content) {
        if (content.length() <= MAX_CHARS) return List.of(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\n\\s*\n")) {
            if (!current.isEmpty() && current.length() + paragraph.length() > MAX_CHARS) {
                chunks.add(current.toString().strip());
                String overlap = current.substring(Math.max(0, current.length() - 120));
                current = new StringBuilder(overlap).append('\n');
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.isEmpty()) chunks.add(current.toString().strip());
        return chunks;
    }

    private String firstHeading(String source, Path file) {
        return source.lines().filter(line -> line.startsWith("# ")).findFirst()
                .map(line -> line.substring(2).strip())
                .orElseGet(() -> file.getFileName().toString().replaceFirst("\\.(md|txt)$", ""));
    }

    private String documentNumber(String source, String fallback) {
        Matcher matcher = DOC_NUMBER.matcher(source);
        return matcher.find() ? matcher.group() : fallback;
    }

    private String article(String value) {
        Matcher matcher = ARTICLE.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * 解析文档策略头，例如：{@code <!-- rag:jurisdiction=CN;effectiveFrom=2025-01-01;accessScopes=PUBLIC_LEGAL;securityStatus=TRUSTED -->}
     * <p>可信状态 fail-closed：没有策略头或未显式声明合法 {@code securityStatus} 的文档
     * 一律落入不可信元数据（INVALID / QUARANTINED / UNTRUSTED_METADATA），不会被正式检索当作可信法规依据。
     */
    private DocumentPolicy policy(String source, Path file) {
        Matcher matcher = RAG_POLICY.matcher(source);
        if (!matcher.find()) {
            // 无策略头：绝不默认放行为可信法规，进入隔离态
            return DocumentPolicy.untrusted(file.getFileName().toString());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : matcher.group(1).split(";")) {
            int separator = pair.indexOf('=');
            if (separator > 0) values.put(pair.substring(0, separator).strip(), pair.substring(separator + 1).strip());
        }
        String jurisdiction = values.getOrDefault("jurisdiction", "INVALID");
        if (!jurisdiction.matches("^[A-Z]{2,8}(-[A-Z0-9]{1,8})?$")) return DocumentPolicy.untrusted(file.getFileName().toString());
        Set<String> scopes = java.util.Arrays.stream(values.getOrDefault("accessScopes", "PUBLIC_LEGAL").split(","))
                .map(String::strip).filter(v -> !v.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) {
            return DocumentPolicy.untrusted(file.getFileName().toString());
        }
        // 可信状态必须显式声明为合法值；缺省 PENDING_REVIEW（不得默认 TRUSTED）
        String security = values.getOrDefault("securityStatus", "PENDING_REVIEW").toUpperCase();
        if (!ALLOWED_SECURITY.contains(security)) return DocumentPolicy.untrusted(file.getFileName().toString());
        LocalDate from = date(values.get("effectiveFrom"));
        LocalDate to = date(values.get("effectiveTo"));
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("法规失效日期早于生效日期");
        return new DocumentPolicy(jurisdiction, from, to, scopes, security);
    }

    private LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("法规分块哈希失败", e);
        }
    }

    private record Section(String heading, String content) {}
    private record DocumentPolicy(String jurisdiction, LocalDate effectiveFrom, LocalDate effectiveTo,
                                  Set<String> accessScopes, String securityStatus) {
        /** 未授权/未标记可信策略：隔离态，检索层不得作为可信法规依据返回 */
        static DocumentPolicy untrusted(String sourceFile) {
            return new DocumentPolicy("INVALID", null, null, Set.of("QUARANTINED"), "UNTRUSTED_METADATA");
        }
    }
}
