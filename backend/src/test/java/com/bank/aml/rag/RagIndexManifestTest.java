package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexManifestTest {

    @Test
    void modelChunkerAndMetadataChangesCreateDifferentIndexIdentity() {
        RagProperties base = properties();
        String original = RagIndexManifest.from("a".repeat(64), base).indexVersion();

        RagProperties modelChanged = properties();
        modelChanged.getEmbedding().setRevision("new-revision");
        RagProperties chunkerChanged = properties();
        chunkerChanged.setChunkerVersion("legal-article-v3");
        RagProperties metadataChanged = properties();
        metadataChanged.setMetadataSchemaVersion("legal-metadata-v3");

        assertThat(RagIndexManifest.from("a".repeat(64), modelChanged).indexVersion()).isNotEqualTo(original);
        assertThat(RagIndexManifest.from("a".repeat(64), chunkerChanged).indexVersion()).isNotEqualTo(original);
        assertThat(RagIndexManifest.from("a".repeat(64), metadataChanged).indexVersion()).isNotEqualTo(original);
        assertThat(RagIndexManifest.from("a".repeat(64), properties()).indexVersion()).isEqualTo(original);
    }

    @Test
    void dimensionsAndDistanceMetricBelongToTheIndexIdentity() {
        RagProperties left = properties();
        RagProperties right = properties();
        right.getPg().setDimensions(768);
        assertThat(RagIndexManifest.from("b".repeat(64), left).indexVersion())
                .isNotEqualTo(RagIndexManifest.from("b".repeat(64), right).indexVersion());
    }

    private RagProperties properties() {
        return new RagProperties();
    }
}
