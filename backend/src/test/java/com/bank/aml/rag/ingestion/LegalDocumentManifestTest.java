package com.bank.aml.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentManifestTest {

    @Test
    void acceptsOnlyApprovedTrustedDocumentWithSpecificOfficialHttpsUrl() {
        assertThat(manifest("APPROVED", "TRUSTED",
                "https://www.pbc.gov.cn/tiaofasi/144941/144957/5863650/index.html").valid()).isTrue();

        assertThat(manifest("PENDING", "TRUSTED",
                "https://www.pbc.gov.cn/tiaofasi/144941/144957/5863650/index.html")
                .validationFailures()).contains("REVIEW_NOT_APPROVED");
        assertThat(manifest("APPROVED", "UNTRUSTED_METADATA",
                "https://www.pbc.gov.cn/tiaofasi/144941/144957/5863650/index.html")
                .validationFailures()).contains("SECURITY_STATUS_NOT_TRUSTED");
        assertThat(manifest("APPROVED", "TRUSTED", "https://www.pbc.gov.cn/")
                .validationFailures()).contains("INVALID_MANIFEST_FIELD:sourceUrl");
        assertThat(manifest("APPROVED", "TRUSTED", "https://pbc.gov.cn.evil.example/rule")
                .validationFailures()).contains("INVALID_MANIFEST_FIELD:sourceUrl");
    }

    private LegalDocumentManifest manifest(String reviewStatus, String securityStatus, String sourceUrl) {
        return new LegalDocumentManifest(
                "AML-REG-001", "法规", "人民银行令〔2025〕第10号", "中国人民银行", "CN",
                LocalDate.of(2025, 11, 20), LocalDate.of(2026, 1, 1), null,
                sourceUrl, "CURATED_SUMMARY", Set.of("PUBLIC_LEGAL"), reviewStatus,
                "compliance-reviewer", LocalDate.of(2026, 1, 1), "a".repeat(64), null,
                "legal-article-v3", securityStatus);
    }
}
