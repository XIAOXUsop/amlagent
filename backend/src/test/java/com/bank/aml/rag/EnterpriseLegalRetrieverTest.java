package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseLegalRetrieverTest {
    private final LegalDocumentSearcher searcher = mock(LegalDocumentSearcher.class);
    private final LegalIndexVersionProvider versions = () -> "index-v1";
    private final EnterpriseLegalRetriever retriever = new EnterpriseLegalRetriever(
            searcher, versions, mock(MetricsRecorder.class), 4);

    private SearchHit supportHit(LegalDoc doc, double rerankScore) {
        return SearchHit.of(doc).reranked(1, rerankScore);
    }

    @Test
    void abstainsWhenNearestEvidenceHasLowSupportProbability() {
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(
                supportHit(doc("LEGAL-1", "高风险客户应核实资金来源", Set.of("PUBLIC_LEGAL")), -5.0)));

        RetrievalResponse response = retriever.retrieve(request("高风险客户资金来源", Set.of("PUBLIC_LEGAL")));

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE);
        assertThat(response.support()).isEqualTo(EvidenceSupport.NO_RELEVANT_EVIDENCE);
        assertThat(response.hits()).isEmpty();
    }

    @Test
    void accessScopeIsEnforcedBeforeEvidenceIsReturned() {
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(
                supportHit(doc("LEGAL-1", "高风险客户应深入了解财产和资金来源", Set.of("AML_INTERNAL")), 3.0)));

        RetrievalResponse response = retriever.retrieve(request("高风险客户资金来源", Set.of("PUBLIC_LEGAL")));

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE);
        assertThat(response.support()).isEqualTo(EvidenceSupport.EVIDENCE_ACCESS_DENIED);
        assertThat(response.hits()).isEmpty();
    }

    @Test
    void returnsSupportedOnlyAfterAuthorizationAndSupportGates() {
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(
                supportHit(doc("LEGAL-1", "高风险客户应深入了解财产和资金来源", Set.of("PUBLIC_LEGAL")), 3.0),
                supportHit(doc("LEGAL-2", "强化尽职调查应当核验客户资金来源", Set.of("PUBLIC_LEGAL")), 2.0)));

        RetrievalResponse response = retriever.retrieve(request("高风险客户资金来源", Set.of("PUBLIC_LEGAL")));

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.SUPPORTED);
        assertThat(response.support()).isEqualTo(EvidenceSupport.SUPPORTED);
        assertThat(response.hits()).hasSize(2).allMatch(hit -> hit.relevanceScore() >= 0.75);
    }

    @Test
    void expiredEvidenceIsNotMisreportedAsAccessDenied() {
        LegalDoc expired = new LegalDoc("LEGAL-1", "客户尽调办法", "文号", "第四条",
                "高风险客户应核验资金来源", new LegalEvidenceMetadata("DOC-1", "强化尽调", "CN",
                java.time.LocalDate.of(2020, 1, 1), java.time.LocalDate.of(2024, 12, 31),
                Set.of("PUBLIC_LEGAL"), "digest", "index-v1", "law.md", "TRUSTED"));
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(SearchHit.of(expired)));

        RetrievalResponse response = retriever.retrieve(request("高风险客户资金来源", Set.of("PUBLIC_LEGAL")));

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE);
        assertThat(response.support()).isEqualTo(EvidenceSupport.EVIDENCE_EXPIRED);
    }

    @Test
    void malformedMetadataFailsClosedInsteadOfBecomingPublicEvidence() {
        LegalDoc malformed = new LegalDoc("LEGAL-1", "客户尽职调查办法", "文号", "第四条",
                "高风险客户应核验资金来源", new LegalEvidenceMetadata("", "", "", null, null,
                Set.of(), "", "", "", ""));
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(SearchHit.of(malformed)));

        RetrievalResponse response = retriever.retrieve(request("高风险客户资金来源", Set.of("PUBLIC_LEGAL")));

        assertThat(malformed.metadata().securityStatus()).isEqualTo("UNTRUSTED_METADATA");
        assertThat(malformed.metadata().accessScopes()).containsExactly("QUARANTINED");
        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE);
        assertThat(response.support()).isEqualTo(EvidenceSupport.NO_RELEVANT_EVIDENCE);
        assertThat(response.hits()).isEmpty();
    }

    @Test
    void returnsInsufficientInsteadOfSupportingWeakAbsoluteEvidence() {
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(
                SearchHit.dense(1, 0.45, doc("LEGAL-1", "客户资料保存的一般说明", Set.of("PUBLIC_LEGAL")))));

        RetrievalResponse response = retriever.retrieve(request("客户资料保存几年", Set.of("PUBLIC_LEGAL")));

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.INSUFFICIENT_EVIDENCE);
        assertThat(response.support()).isEqualTo(EvidenceSupport.WEAK_SUPPORT);
    }

    @Test
    void appliesRequestMinRelevanceAsAbsoluteFloor() {
        when(searcher.searchScored(org.mockito.ArgumentMatchers.any(RetrievalRequest.class),
                org.mockito.ArgumentMatchers.eq(12))).thenReturn(List.of(
                SearchHit.dense(1, 0.45, doc("LEGAL-1", "客户资料保存的一般说明", Set.of("PUBLIC_LEGAL")))));
        RetrievalRequest strict = new RetrievalRequest("客户资料保存几年", "保存期限",
                Instant.parse("2026-08-01T00:00:00Z"), "CN", Set.of("PUBLIC_LEGAL"), 3, 0.6);

        RetrievalResponse response = retriever.retrieve(strict);

        assertThat(response.status()).isEqualTo(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE);
        assertThat(response.hits()).isEmpty();
    }

    private RetrievalRequest request(String query, Set<String> scopes) {
        return new RetrievalRequest(query, query, Instant.parse("2026-08-01T00:00:00Z"),
                "CN", scopes, 3, 0.04);
    }

    private LegalDoc doc(String id, String content, Set<String> scopes) {
        return new LegalDoc(id, "客户尽职调查办法", "文号", "第四条", content,
                new LegalEvidenceMetadata("DOC-1", "强化尽调", "CN", null, null, scopes,
                        "digest", "index-v1", "law.md", "TRUSTED"));
    }
}
