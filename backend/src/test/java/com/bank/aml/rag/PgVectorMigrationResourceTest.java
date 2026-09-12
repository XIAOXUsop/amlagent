package com.bank.aml.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorMigrationResourceTest {

    @Test
    void baseMigrationOwnsVectorExtensionAndStoreSchema() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/pg-migration/V1__create_legal_rag_store.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("create extension if not exists vector", "create table if not exists legal_docs",
                    "embedding vector(384) not null", "metadata json");
        }
    }

}
