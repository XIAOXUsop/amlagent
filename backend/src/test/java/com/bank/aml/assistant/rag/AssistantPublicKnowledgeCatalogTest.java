package com.bank.aml.assistant.rag;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPublicKnowledgeCatalogTest {
    private final AssistantPublicKnowledgeCatalog catalog = new AssistantPublicKnowledgeCatalog(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void returnsOnlyRelevantEffectiveFrozenEvidence() {
        var hits = catalog.retrieve("存款保险最多偿付多少", Instant.parse("2026-08-23T00:00:00Z"), 3);
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.evidenceId()).isEqualTo("KB-DEPOSIT-INSURANCE-CN-001");
            assertThat(hit.type()).isEqualTo(AssistantEvidence.EvidenceType.BANKING_PUBLIC);
            assertThat(hit.source()).contains("https://xzfg.moj.gov.cn/");
        });
    }

    @Test
    void excludesDocumentsThatWereNotEffectiveAtSnapshotTime() {
        var hits = catalog.retrieve("客户尽职调查 KYC", Instant.parse("2025-08-23T00:00:00Z"), 10);
        assertThat(hits).noneMatch(hit -> hit.evidenceId().equals("KB-KYC-CN-2025-001"));
    }
}
