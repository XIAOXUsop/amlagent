package com.bank.aml.rag;

import java.time.LocalDate;
import java.util.Set;

/** 法规证据的来源、适用期、访问范围和完整性元数据。 */
public record LegalEvidenceMetadata(
        String documentId,
        String parentSection,
        String jurisdiction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Set<String> accessScopes,
        String contentDigest,
        String corpusVersion,
        String sourceFile,
    String securityStatus
) {
    public LegalEvidenceMetadata {
        // 元数据缺失时必须 fail closed。公开/可信只能由显式的受控摄取流程赋予。
        accessScopes = accessScopes == null || accessScopes.isEmpty() ? Set.of("QUARANTINED") : Set.copyOf(accessScopes);
        jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? "INVALID" : jurisdiction;
        securityStatus = securityStatus == null || securityStatus.isBlank() ? "UNTRUSTED_METADATA" : securityStatus;
    }

    public static LegalEvidenceMetadata publicLegal() {
        return new LegalEvidenceMetadata("", "", "CN", null, null, Set.of("PUBLIC_LEGAL"),
                "", "", "", "TRUSTED");
    }

    public static LegalEvidenceMetadata untrustedMetadata() {
        return new LegalEvidenceMetadata("", "", "INVALID", null, null, Set.of("QUARANTINED"),
                "", "", "", "UNTRUSTED_METADATA");
    }
}
