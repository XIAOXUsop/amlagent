package com.bank.aml.rag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 加载每个法规文档同级的 {@code <base>.manifest.yaml} 可信元数据。
 * <p>缺失或不以 UTF-8 可解析即返回 {@code Optional.empty()}，调用方（导入器/安全门）负责隔离。</p>
 */
@Component
public class LegalManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(LegalManifestLoader.class);

    /** 与文档文件同名的 manifest，如 law.md → law.manifest.yaml */
    public static Path manifestFor(Path documentFile) {
        String name = documentFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return documentFile.resolveSibling(base + ".manifest.yaml");
    }

    public Optional<LegalDocumentManifest> load(Path documentFile) {
        Path manifest = manifestFor(documentFile);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(manifest, StandardCharsets.UTF_8);
            Object parsed = new Yaml().load(content);
            if (!(parsed instanceof Map<?, ?> map)) {
                log.warn("Manifest 解析失败（非映射结构）：{}", manifest.getFileName());
                return Optional.empty();
            }
            return Optional.of(toManifest(map, manifest.getFileName().toString()));
        } catch (Exception e) {
            log.warn("Manifest 读取/解析失败：{} — {}", manifest.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private LegalDocumentManifest toManifest(Map<?, ?> raw, String sourceFile) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key == null) continue;
            if (value instanceof java.util.List<?> list) {
                Set<String> scoped = new LinkedHashSet<>();
                for (Object item : list) {
                    if (item != null) scoped.add(String.valueOf(item).strip());
                }
                scoped.remove("");
                values.put(String.valueOf(key), String.join(",", scoped));
            } else if (value instanceof java.util.Date date) {
                // snakeyaml 会把 ISO 日期隐式解析为 Date；归一化为 yyyy-MM-dd 以便本地解析
                values.put(String.valueOf(key),
                        date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString());
            } else if (value != null) {
                values.put(String.valueOf(key), String.valueOf(value).strip());
            }
        }
        try {
            return new LegalDocumentManifest(
                    text(values, "documentId"),
                    text(values, "title"),
                    text(values, "documentNumber"),
                    text(values, "issuingAuthority"),
                    text(values, "jurisdiction"),
                    date(values, "promulgatedAt"),
                    date(values, "effectiveFrom"),
                    date(values, "effectiveTo"),
                    text(values, "sourceUrl"),
                    text(values, "sourceType"),
                    scopes(values.get("accessScopes")),
                    text(values, "reviewStatus"),
                    text(values, "reviewedBy"),
                    date(values, "reviewedAt"),
                    text(values, "sourceSha256"),
                    text(values, "supersedes"),
                    text(values, "parserVersion"),
                    text(values, "securityStatus"));
        } catch (RuntimeException malformed) {
            log.warn("Manifest 字段非法（{}）：{}", sourceFile, malformed.getMessage());
            throw malformed;
        }
    }

    private String text(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? "" : value;
    }

    private LocalDate date(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        return LocalDate.parse(value);
    }

    private Set<String> scopes(String joined) {
        if (joined == null || joined.isBlank() || "null".equalsIgnoreCase(joined)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String scope : joined.split(",")) {
            String v = scope.strip();
            if (!v.isBlank()) result.add(v);
        }
        return result;
    }
}