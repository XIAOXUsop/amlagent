package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalRequestTest {

    @Test
    void rejectsUntrustedFilterSyntaxBeforeItReachesStorage() {
        assertThatThrownBy(() -> request("CN' OR '1'='1", Set.of("PUBLIC_LEGAL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jurisdiction");

        assertThatThrownBy(() -> request("CN", Set.of("PUBLIC_%")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("访问范围");
    }

    private RetrievalRequest request(String jurisdiction, Set<String> scopes) {
        return new RetrievalRequest("高风险客户资金来源", "强化尽调", Instant.parse("2026-08-01T00:00:00Z"),
                jurisdiction, scopes, 5, 0.04);
    }
}
