package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextSelectorTest {
    @Test
    void limitsSingleDocumentAndRemovesNearDuplicates() {
        RagContextSelector selector = new RagContextSelector();
        LegalDoc a = doc("A", "DOC-1", "高风险客户应当核验资金来源并加强交易监测。");
        LegalDoc duplicate = doc("B", "DOC-1", "高风险客户应当核验资金来源并加强交易监测。\n");
        LegalDoc second = doc("C", "DOC-1", "受益所有人信息需要进行穿透核验。");
        LegalDoc other = doc("D", "DOC-2", "命中恐怖活动名单后应当立即冻结资产。");

        assertThat(selector.select(List.of(a, duplicate, second, other), 1, 8000, 0.88))
                .extracting(LegalDoc::evidenceId).containsExactly("A", "D");
    }

    private LegalDoc doc(String id, String documentId, String content) {
        return new LegalDoc(id, documentId, "", "", content,
                new LegalEvidenceMetadata(documentId, "", "CN", null, null,
                        java.util.Set.of("PUBLIC_LEGAL"), "", "v1", "", "TRUSTED"));
    }
}
