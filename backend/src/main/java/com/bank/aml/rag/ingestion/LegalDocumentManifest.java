package com.bank.aml.rag.ingestion;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 法律文档强制可信元数据（manifest.yaml）。
 * <p>字段全部必填（{@code effectiveTo}/{@code supersedes} 可为空）：缺失任一项即视为非法，
 * 该文档不得进入候选索引。可信状态 fail-closed：只有显式 {@code securityStatus=TRUSTED} 且
 * 通过人工审核（{@code reviewStatus=APPROVED}）与官方来源校验的文档才能作为可信法规依据发布。</p>
 */
public record LegalDocumentManifest(
        String documentId,
        String title,
        String documentNumber,
        String issuingAuthority,
        String jurisdiction,
        LocalDate promulgatedAt,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceUrl,
        String sourceType,
        Set<String> accessScopes,
        String reviewStatus,
        String reviewedBy,
        LocalDate reviewedAt,
        String sourceSha256,
        String supersedes,
        String parserVersion,
        String securityStatus
) {

    private static final Set<String> ALLOWED_SOURCE_TYPES = Set.of(
            "OFFICIAL_TEXT", "OFFICIAL_EXCERPT", "CURATED_SUMMARY", "REGULATION", "GAZETTE");
    private static final Set<String> TRUSTED_SOURCE_HOSTS = Set.of(
            "npc.gov.cn", "www.npc.gov.cn", "gov.cn", "www.gov.cn",
            "pbc.gov.cn", "www.pbc.gov.cn", "nfra.gov.cn", "www.nfra.gov.cn",
            "csrc.gov.cn", "www.csrc.gov.cn");

    /** 每个字段的名称，用于缺失字段校验报告 */
    private static final List<String> REQUIRED_FIELDS = List.of(
            "documentId", "title", "documentNumber", "issuingAuthority", "jurisdiction",
            "promulgatedAt", "effectiveFrom", "sourceUrl", "sourceType", "accessScopes",
            "reviewStatus", "reviewedBy", "reviewedAt", "sourceSha256", "parserVersion", "securityStatus");

    /** 返回缺失/非法字段的 reasonCode；为空表示完全合法。 */
    public List<String> validationFailures() {
        List<String> failures = new ArrayList<>();
        if (blank(documentId)) failures.add("MISSING_MANIFEST_FIELD:documentId");
        if (blank(title)) failures.add("MISSING_MANIFEST_FIELD:title");
        if (blank(documentNumber)) failures.add("MISSING_MANIFEST_FIELD:documentNumber");
        if (blank(issuingAuthority)) failures.add("MISSING_MANIFEST_FIELD:issuingAuthority");
        if (blank(jurisdiction) || !jurisdiction.matches("^[A-Z]{2,8}(-[A-Z0-9]{1,8})?$"))
            failures.add("INVALID_MANIFEST_FIELD:jurisdiction");
        if (promulgatedAt == null) failures.add("MISSING_MANIFEST_FIELD:promulgatedAt");
        if (effectiveFrom == null) failures.add("MISSING_MANIFEST_FIELD:effectiveFrom");
        if (!specificTrustedOfficialUrl(sourceUrl)) failures.add("INVALID_MANIFEST_FIELD:sourceUrl");
        if (blank(sourceType) || !ALLOWED_SOURCE_TYPES.contains(sourceType))
            failures.add(blank(sourceType) ? "NON_OFFICIAL_SOURCE:MISSING" : "NON_OFFICIAL_SOURCE:" + sourceType);
        if (accessScopes == null || accessScopes.isEmpty()) failures.add("MISSING_MANIFEST_FIELD:accessScopes");
        if (!"APPROVED".equalsIgnoreCase(reviewStatus)) failures.add("REVIEW_NOT_APPROVED");
        if (blank(reviewedBy)) failures.add("MISSING_MANIFEST_FIELD:reviewedBy");
        if (reviewedAt == null) failures.add("MISSING_MANIFEST_FIELD:reviewedAt");
        if (blank(sourceSha256) || !sourceSha256.matches("[0-9a-fA-F]{64}"))
            failures.add("INVALID_MANIFEST_FIELD:sourceSha256");
        if (blank(parserVersion)) failures.add("MISSING_MANIFEST_FIELD:parserVersion");
        if (!"TRUSTED".equalsIgnoreCase(securityStatus)) failures.add("SECURITY_STATUS_NOT_TRUSTED");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom))
            failures.add("INVALID_EFFECTIVE_RANGE:effectiveTo<effectiveFrom");
        return failures;
    }

    public boolean valid() {
        return validationFailures().isEmpty();
    }

    /** 绑定到可核对的具体官方页面；内容形态可以是原文、节选或经审核摘要。 */
    public boolean officialSource() {
        return specificTrustedOfficialUrl(sourceUrl) && ALLOWED_SOURCE_TYPES.contains(sourceType);
    }

    /** 有无人工审核通过记录；不具备该记录不得进入候选索引。 */
    public boolean reviewed() {
        return "APPROVED".equalsIgnoreCase(reviewStatus) && !blank(reviewedBy) && reviewedAt != null;
    }

    /** 期望的源文件 SHA-256（.md/.txt 原文） */
    public String expectedSourceSha256() {
        return sourceSha256 == null ? "" : sourceSha256.toLowerCase();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean specificTrustedOfficialUrl(String value) {
        if (blank(value)) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && TRUSTED_SOURCE_HOSTS.contains(host)
                    && !path.isBlank() && !"/".equals(path);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public Set<String> scopes() {
        return accessScopes == null ? Set.of("QUARANTINED") : new LinkedHashSet<>(accessScopes);
    }
}
