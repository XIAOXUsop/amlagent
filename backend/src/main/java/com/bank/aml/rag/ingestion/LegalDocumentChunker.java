package com.bank.aml.rag.ingestion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按「章 → 条 → 款 → 项」层级分块法规正文。
 * <p>每条通常作为一个整块（保持条款语义完整）；超长条款按段落做带尾段重叠的分块，
 * 并在元数据中记录 articleNumber / paragraphNumber / itemNumber / sourceOffset / rawText / normalizedText /
 * parentArticleId，供精确定位、去重与追溯。</p>
 * <ul>
 *   <li>{@link #chunk(Path, String)}：兼容旧策略头（{@code <!-- rag:... -->}）的无 manifest 路径，供测试/旧语料回退；</li>
 *   <li>{@link #chunk(Path, String, LegalDocumentManifest)}：强制 manifest 路径，可信元数据以 manifest 为准。</li>
 * </ul>
 * <p>可信状态始终 fail-closed：没有显式 {@code TRUSTED} 来源的文档一律进入隔离态。</p>
 */
@Component
public class LegalDocumentChunker {
    private static final Logger log = LoggerFactory.getLogger(LegalDocumentChunker.class);

    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千零〇0-9]+条");
    private static final Pattern CHAPTER = Pattern.compile("第[一二三四五六七八九十百千零〇0-9]+章[^\\n]*");
    /** 项编号：中文数字/阿拉伯数字，括号或顿号分隔，如（一）（1）1. 一、 */
    private static final Pattern ITEM = Pattern.compile("^[（(]?[一二三四五六七八九十0-9]+[）)、.．]\\s*");
    private static final Pattern DOC_NUMBER = Pattern.compile("[（(]?[^（(]*令[〔【]?\\d{4}[〕】]?第\\d+号[）)]?");
    private static final int MAX_CHARS = 1_200;
    private static final int OVERLAP_CHARS = 120;

    private static final Set<String> ALLOWED_SCOPES = Set.of("PUBLIC_LEGAL", "AML_INTERNAL");
    private static final Set<String> ALLOWED_SECURITY = Set.of("TRUSTED", "PENDING_REVIEW", "UNTRUSTED_METADATA");
    private static final Pattern RAG_POLICY = Pattern.compile("<!--\\s*rag:(.*?)-->", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 旧路径：从策略头解析元数据（无 manifest 时回退）。 */
    public List<TextSegment> chunk(Path file, String corpusVersion) throws java.io.IOException {
        return chunkInternal(file, corpusVersion, null);
    }

    /** 强制 manifest 路径：可信元数据取自 {@code manifest}；缺失/非法字段由调用方拒绝，此处仍 fail-closed。 */
    public List<TextSegment> chunk(Path file, String corpusVersion, LegalDocumentManifest manifest)
            throws java.io.IOException {
        return chunkInternal(file, corpusVersion, manifest);
    }

    private List<TextSegment> chunkInternal(Path file, String corpusVersion, LegalDocumentManifest manifest)
            throws java.io.IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String title = manifest != null && !blank(manifest.title()) ? manifest.title()
                : firstHeading(source, file);
        String documentNumber = manifest != null && !blank(manifest.documentNumber())
                ? manifest.documentNumber() : documentNumber(source, title);
        String documentId = manifest != null && !blank(manifest.documentId())
                ? manifest.documentId()
                : "DOC-" + sha256(file.getFileName() + "|" + title).substring(0, 16);
        DocumentPolicy policy = manifest != null ? DocumentPolicy.from(manifest, file.getFileName().toString())
                : policy(source, file);

        // 记录各章节偏移用于 sourceOffset 追溯；分块基于移除策略头的原文，避免策略行混入条款正文
        String cleaned = RAG_POLICY.matcher(source).replaceAll("");
        LineScanner scanner = new LineScanner(cleaned);
        List<Section> sections = sections(scanner, cleaned);

        List<TextSegment> result = new ArrayList<>();
        int ordinal = 0;
        Set<String> seenEvidenceIds = new LinkedHashSet<>();
        for (Section section : sections) {
            if (CHAPTER.matcher(section.heading()).matches()) {
                continue; // 章本身不产出片段，仅作条目的父级
            }
            for (Block block : blocks(section)) {
                String text = section.heading().isBlank() ? block.normalizedText() : section.heading() + "\n" + block.normalizedText();
                if (text.strip().length() < 20) {
                    continue;
                }
                String article = article(section.heading() + " " + section.content());
                String digest = sha256(block.normalizedText().strip());
                String evidenceId = "LEGAL-" + sha256(title + "|" + article + "|" + block.normalizedText().strip()).substring(0, 16);
                if (!seenEvidenceIds.add(evidenceId)) {
                    log.debug("同条款重复证据片段跳过：{}", evidenceId);
                    continue;
                }
                String chunkId = "CHUNK-" + sha256(documentId + "|" + ordinal++ + "|" + digest).substring(0, 16);
                Metadata metadata = new Metadata()
                        .put("title", title)
                        .put("documentNumber", documentNumber)
                        .put("articleNumber", article)
                        .put("evidenceId", evidenceId)
                        .put("documentId", documentId)
                        .put("chunkId", chunkId)
                        .put("parentSection", section.chapter())
                        .put("parentArticleId", section.chapter().isBlank() ? ""
                                : "SEC-" + sha256(title + "|" + section.chapter()).substring(0, 8))
                        .put("jurisdiction", policy.jurisdiction())
                        .put("accessScopes", policy.accessScopes().stream().sorted().collect(java.util.stream.Collectors.joining(",")))
                        .put("contentDigest", digest)
                        .put("sourceFile", file.getFileName().toString())
                        .put("sourceOffset", Integer.toString(block.sourceOffset()))
                        .put("paragraphNumber", block.paragraphRange())
                        .put("itemNumber", block.itemRange())
                        .put("rawText", block.rawText())
                        .put("normalizedText", block.normalizedText())
                        .put("legalActionCode", actionCodes(block.normalizedText()))
                        .put("securityStatus", policy.securityStatus())
                        .put("parserVersion", policy.parserVersion())
                        .put("corpusVersion", corpusVersion);
                applyManifest(metadata, manifest, policy);
                if (policy.effectiveFrom() != null) metadata.put("effectiveFrom", policy.effectiveFrom().toString());
                if (policy.effectiveTo() != null) metadata.put("effectiveTo", policy.effectiveTo().toString());
                result.add(TextSegment.from(text.strip(), metadata));
            }
        }
        return result;
    }

    private void applyManifest(Metadata metadata, LegalDocumentManifest manifest, DocumentPolicy policy) {
        if (manifest == null) return;
        if (!blank(manifest.issuingAuthority())) metadata.put("issuingAuthority", manifest.issuingAuthority());
        if (manifest.promulgatedAt() != null) metadata.put("promulgatedAt", manifest.promulgatedAt().toString());
        if (!blank(manifest.sourceUrl())) metadata.put("sourceUrl", manifest.sourceUrl());
        if (!blank(manifest.sourceType())) metadata.put("sourceType", manifest.sourceType());
        if (!blank(manifest.reviewStatus())) metadata.put("reviewStatus", manifest.reviewStatus());
        if (!blank(manifest.reviewedBy())) metadata.put("reviewedBy", manifest.reviewedBy());
        if (manifest.reviewedAt() != null) metadata.put("reviewedAt", manifest.reviewedAt().toString());
        if (!blank(manifest.expectedSourceSha256())) metadata.put("sourceSha256", manifest.expectedSourceSha256());
        if (!blank(manifest.supersedes())) metadata.put("supersedes", manifest.supersedes());
    }

    /** 将条款正文按款/项分层后聚合成块。 */
    private List<Block> blocks(Section section) {
        List<Paragraph> paragraphs = paragraphs(section.content());
        int total = paragraphs.stream().mapToInt(p -> p.text().length()).sum() + (paragraphs.size() - 1) * 2;
        if (total <= MAX_CHARS) {
            if (paragraphs.isEmpty()) return List.of();
            return List.of(toBlock(section, paragraphs, 0, paragraphs.size()));
        }
        // 超长条款：保持段落完整，按 MAX_CHARS 切块并带 OVERLAP_CHARS 重叠
        List<Block> result = new ArrayList<>();
        int start = 0;
        List<Paragraph> current = new ArrayList<>();
        int length = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            Paragraph p = paragraphs.get(i);
            int add = p.text().length() + (current.isEmpty() ? 0 : 2);
            if (!current.isEmpty() && length + add > MAX_CHARS) {
                result.add(toBlock(section, current, start, i));
                int overlapStart = Math.max(0, current.size() - 2);
                start += overlapStart;
                current = new ArrayList<>(paragraphs.subList(start, i));
                length = current.stream().mapToInt(q -> q.text().length()).sum() + (current.size() - 1) * 2;
            }
            current.add(p);
            length += add;
        }
        if (!current.isEmpty()) result.add(toBlock(section, current, start, paragraphs.size()));
        return result;
    }

    private Block toBlock(Section section, List<Paragraph> paragraphs, int paragraphStart, int paragraphEndExclusive) {
        String rawText = String.join("\n\n", paragraphs.stream().map(Paragraph::text).toList());
        String normalizedText = String.join("\n", paragraphs.stream()
                .map(p -> p.text().strip()).filter(v -> !v.isEmpty()).toList());
        Block block = new Block(paragraphs, normalizedText, rawText, section.offset(), paragraphStart, paragraphEndExclusive);
        // client 定位需要的归一化文本：把每段是否以项编号开头保留在 normalized 中即可
        return block;
    }

    private List<Paragraph> paragraphs(String content) {
        List<Paragraph> result = new ArrayList<>();
        int number = 0;
        for (String rawParagraph : content.split("\n\\s*\n")) {
            String paragraphText = rawParagraph.strip();
            if (paragraphText.isEmpty()) continue;
            number++;
            Matcher item = ITEM.matcher(paragraphText);
            String itemNumber = item.find() ? item.group().strip() : "";
            result.add(new Paragraph(paragraphText, number, itemNumber));
        }
        return result;
    }

    /** 计算每个 section 的字符源偏移与所属章标题。 */
    private List<Section> sections(LineScanner scanner, String sanitized) {
        List<Section> result = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        int headingOffset = 0;
        String chapter = "";
        int bodyStart = 0;
        for (String[] token : scanner.lines()) {
            String line = token[0];
            int offset = Integer.parseInt(token[1]);
            if (line.startsWith("## ")) {
                String candidate = line.substring(3).strip();
                if (CHAPTER.matcher(candidate).matches()) {
                    flush(result, heading, body, headingOffset, chapter);
                    chapter = candidate;
                    heading = "";
                    body.setLength(0);
                    bodyStart = offset;
                    continue;
                }
                flush(result, heading, body, headingOffset, chapter);
                heading = candidate;
                headingOffset = offset;
                bodyStart = offset;
                body.setLength(0);
            } else if (!line.startsWith("# ")) {
                if (heading.isEmpty() && body.length() == 0) bodyStart = offset;
                body.append(line).append('\n');
            }
        }
        flush(result, heading, body, headingOffset, chapter);
        if (result.isEmpty() && !sanitized.isBlank()) {
            // 无 H2 结构：整篇作为一个无标题 section（保留旧兼容行为）
            int offset = sanitized.indexOf(sanitized.strip());
            result.add(new Section("", sanitized.strip(), offset, chapter));
        }
        return result;
    }

    private void flush(List<Section> target, String heading, StringBuilder body, int offset, String chapter) {
        String content = body.toString().strip();
        if (!content.isBlank()) target.add(new Section(heading, content, offset, chapter));
        body.setLength(0);
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
     * 解析旧策略头（{@code <!-- rag:... -->}）。仅无 manifest 时用于回退，新增文档一律走 manifest。
     */
    DocumentPolicy policy(String source, Path file) {
        Matcher matcher = RAG_POLICY.matcher(source);
        if (!matcher.find()) {
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
        String security = values.getOrDefault("securityStatus", "PENDING_REVIEW").toUpperCase();
        if (!ALLOWED_SECURITY.contains(security)) return DocumentPolicy.untrusted(file.getFileName().toString());
        LocalDatePolicy dates = policyDates(values.get("effectiveFrom"), values.get("effectiveTo"), values.get("promulgatedAt"));
        if (dates.invalid()) throw new IllegalArgumentException("法规失效日期早于生效日期");
        return DocumentPolicy.of(values.getOrDefault("jurisdiction", "INVALID"), dates, scopes, security,
                values.getOrDefault("parserVersion", ""), file.getFileName().toString());
    }

    private LocalDatePolicy policyDates(String from, String to, String promulgated) {
        try {
            java.time.LocalDate f = from == null || from.isBlank() ? null : java.time.LocalDate.parse(from);
            java.time.LocalDate t = to == null || to.isBlank() ? null : java.time.LocalDate.parse(to);
            return new LocalDatePolicy(f, t, promulgated == null || promulgated.isBlank() ? null : java.time.LocalDate.parse(promulgated));
        } catch (java.time.format.DateTimeParseException e) {
            return new LocalDatePolicy(null, null, null);
        }
    }

    /** 从条款文本提取规范行为标记（供字段化加权检索与处置判定）。 */
    private String actionCodes(String text) {
        List<String> codes = new ArrayList<>();
        if (text.contains("不得") || text.contains("禁止")) codes.add("PROHIBITED");
        if (text.contains("应当") || text.contains("必须")) codes.add("MANDATORY");
        if (text.contains("可以")) codes.add("PERMISSIVE");
        if (text.contains("立即")) codes.add("IMMEDIATE");
        if (text.contains("报告")) codes.add("REPORT");
        if (text.contains("保存")) codes.add("RETAIN");
        if (text.contains("冻结")) codes.add("FREEZE");
        return String.join(",", codes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("法规分块哈希失败", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Section(String heading, String content, int offset, String chapter) {}

    private record Paragraph(String text, int number, String itemNumber) {}

    private record Block(List<Paragraph> paragraphs, String normalizedText, String rawText, int sourceOffset,
                         int paragraphStart, int paragraphEndExclusive) {
        String paragraphRange() {
            if (paragraphs.isEmpty()) return "";
            if (paragraphs.size() == 1) return String.valueOf(paragraphs.getFirst().number());
            return paragraphs.getFirst().number() + "-" + paragraphs.getLast().number();
        }

        String itemRange() {
            List<String> items = paragraphs.stream().map(Paragraph::itemNumber)
                    .filter(v -> !v.isEmpty()).toList();
            if (items.isEmpty()) return "";
            if (items.size() == 1) return items.getFirst();
            return items.getFirst() + ".." + items.getLast();
        }
    }

    private static class LineScanner {
        private final List<String[]> lines = new ArrayList<>();

        LineScanner(String source) {
            int offset = 0;
            for (String line : source.split("\n", -1)) {
                lines.add(new String[]{line, String.valueOf(offset)});
                offset += line.length() + 1;
            }
        }

        List<String[]> lines() {
            return lines;
        }
    }

    private record LocalDatePolicy(java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo,
                                   java.time.LocalDate promulgatedAt) {
        boolean invalid() {
            return effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom);
        }
    }

    private record DocumentPolicy(String jurisdiction, java.time.LocalDate effectiveFrom,
                                  java.time.LocalDate effectiveTo, Set<String> accessScopes,
                                  String securityStatus, String parserVersion) {
        static DocumentPolicy of(String jurisdiction, LocalDatePolicy dates, Set<String> scopes,
                                 String securityStatus, String parserVersion, String sourceFile) {
            return new DocumentPolicy(jurisdiction, dates.effectiveFrom(), dates.effectiveTo(),
                    scopes, securityStatus, parserVersion);
        }

        static DocumentPolicy from(LegalDocumentManifest manifest, String sourceFile) {
            if (!manifest.valid()) {
                return untrusted(sourceFile);
            }
            String security = blank(manifest.securityStatus()) ? "PENDING_REVIEW" : manifest.securityStatus().toUpperCase();
            if (!ALLOWED_SECURITY.contains(security)) {
                return untrusted(sourceFile);
            }
            Set<String> scopes = manifest.scopes().stream()
                    .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (scopes.isEmpty() || !ALLOWED_SCOPES.containsAll(scopes)) {
                return untrusted(sourceFile);
            }
            if (!manifest.officialSource()) {
                security = "PENDING_REVIEW"; // 非官方来源不得直接可信
            }
            return new DocumentPolicy(manifest.jurisdiction(), manifest.effectiveFrom(), manifest.effectiveTo(),
                    scopes, security, blank(manifest.parserVersion()) ? "legal-article-v3" : manifest.parserVersion());
        }

        /** 未授权/未标记可信策略：隔离态，检索层不得作为可信法规依据返回 */
        static DocumentPolicy untrusted(String sourceFile) {
            return new DocumentPolicy("INVALID", null, null, Set.of("QUARANTINED"), "UNTRUSTED_METADATA", "");
        }
    }
}
