package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void defaultsToActiveTargetAndNormalCacheForProductionCallers() {
        RetrievalRequest request = request("CN", Set.of("PUBLIC_LEGAL"));

        assertThat(request.target()).isEqualTo(RetrievalTarget.ACTIVE);
        assertThat(request.cacheMode()).isEqualTo(CacheMode.NORMAL);
        assertThat(request.specificVersion()).isNull();
    }

    @Test
    void specificVersionTargetRequiresAWellFormedIndexVersion() {
        assertThatThrownBy(() -> new RetrievalRequest("资金来源", "尽调", Instant.parse("2026-08-01T00:00:00Z"),
                "CN", Set.of("PUBLIC_LEGAL"), 5, 0.04, RetrievalTarget.SPECIFIC_VERSION, "not-a-hash",
                CacheMode.BYPASS_READ_WRITE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specificVersion");

        RetrievalRequest valid = new RetrievalRequest("资金来源", "尽调", Instant.parse("2026-08-01T00:00:00Z"),
                "CN", Set.of("PUBLIC_LEGAL"), 5, 0.04, RetrievalTarget.SPECIFIC_VERSION,
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                CacheMode.BYPASS_READ_WRITE);
        assertThat(valid.specificVersion()).isEqualTo(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        assertThat(valid.cacheMode()).isEqualTo(CacheMode.BYPASS_READ_WRITE);
    }

    @Test
    void nonSpecificTargetsStripAnyCarriedSpecificVersion() {
        RetrievalRequest plain = new RetrievalRequest("资金来源", "尽调", Instant.parse("2026-08-01T00:00:00Z"),
                "CN", Set.of("PUBLIC_LEGAL"), 5, 0.04, RetrievalTarget.CANDIDATE,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                CacheMode.READ_ONLY);

        assertThat(plain.specificVersion()).isNull();
        assertThat(plain.cacheMode()).isEqualTo(CacheMode.READ_ONLY);
    }

    private RetrievalRequest request(String jurisdiction, Set<String> scopes) {
        return new RetrievalRequest("高风险客户资金来源", "强化尽调", Instant.parse("2026-08-01T00:00:00Z"),
                jurisdiction, scopes, 5, 0.04);
    }
}
