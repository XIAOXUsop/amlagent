package com.bank.aml.rag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 在真实 pgvector/PostgreSQL 上验证法规库的空库安装与无历史表棕地升级。 */
@Tag("integration")
class PgVectorFlywayMigrationIntegrationTest {

    private static final String HOST = env("PG_TEST_HOST", "localhost:5433");

    private static final String USER = env("PG_TEST_USER", "aml");

    private static final String PASSWORD = env("PG_TEST_PASSWORD", "aml123456");

    private static final String URL = "jdbc:postgresql://" + HOST + "/aml_rag";

    private static final Set<String> TEST_SCHEMAS = Set.of("rag_migration_empty", "rag_migration_legacy");

    @AfterEach
    void removeIsolatedSchemas() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String schema : TEST_SCHEMAS) {
                dropSchema(statement, schema);
            }
        }
    }

    @Test
    void installsV1AndV2IntoAnEmptyPostgresSchema() throws Exception {
        String schema = "rag_migration_empty";
        recreateSchema(schema);

        migrate(schema);

        assertMigratedStore(schema);
    }

    @Test
    void baselinesLegacyTableAtV0ThenRunsV1AndV2() throws Exception {
        String schema = "rag_migration_legacy";
        recreateSchema(schema);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE legal_docs ("
                    + "embedding_id UUID PRIMARY KEY, embedding vector(384) NOT NULL, text TEXT, metadata JSON)");
            statement.execute("INSERT INTO legal_docs (embedding_id, embedding, text, metadata) VALUES "
                    + "('00000000-0000-0000-0000-000000000001', array_fill(0::real, ARRAY[384])::vector, "
                    + "'legacy legal text', '{\"corpusVersion\":\"legacy-v1\"}')");
        }

        migrate(schema);

        assertMigratedStore(schema);
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = queryLegacyRow(connection, statement)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private void assertMigratedStore(String schema) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            connection.setSchema(schema);
            try (ResultSet result = statement.executeQuery("SELECT version, success FROM flyway_schema_history "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
                List<String> versions = new ArrayList<>();
                while (result.next()) {
                    assertThat(result.getBoolean("success")).isTrue();
                    versions.add(result.getString("version"));
                }
                assertThat(versions).contains("1", "2");
            }
            try (ResultSet result = statement.executeQuery("SELECT indexname FROM pg_indexes "
                    + "WHERE schemaname = current_schema() AND tablename = 'legal_docs'")) {
                Set<String> indexes = new HashSet<>();
                while (result.next()) {
                    indexes.add(result.getString(1));
                }
                assertThat(indexes).contains("legal_docs_text_trgm_idx", "legal_docs_fts_idx",
                        "legal_docs_corpus_version_idx", "legal_docs_jurisdiction_idx",
                        "legal_docs_security_status_idx", "legal_docs_effective_from_idx",
                        "legal_docs_effective_to_idx");
            }
        }
    }

    private void migrate(String schema) {
        Flyway.configure()
            .dataSource(URL, USER, PASSWORD)
            .locations("classpath:db/pg-migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
    }

    private void recreateSchema(String schema) throws Exception {
        assertThat(TEST_SCHEMAS).contains(schema);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            dropSchema(statement, schema);
            createSchema(statement, schema);
        }
    }

    private void dropSchema(Statement statement, String schema) throws Exception {
        switch (schema) {
            case "rag_migration_empty" -> statement.execute("DROP SCHEMA IF EXISTS rag_migration_empty CASCADE");
            case "rag_migration_legacy" -> statement.execute("DROP SCHEMA IF EXISTS rag_migration_legacy CASCADE");
            default -> throw new IllegalArgumentException("不允许操作测试 schema: " + schema);
        }
    }

    private void createSchema(Statement statement, String schema) throws Exception {
        switch (schema) {
            case "rag_migration_empty" -> statement.execute("CREATE SCHEMA rag_migration_empty");
            case "rag_migration_legacy" -> statement.execute("CREATE SCHEMA rag_migration_legacy");
            default -> throw new IllegalArgumentException("不允许操作测试 schema: " + schema);
        }
    }

    private ResultSet queryLegacyRow(Connection connection, Statement statement) throws Exception {
        connection.setSchema("rag_migration_legacy");
        return statement.executeQuery("SELECT COUNT(*) FROM legal_docs WHERE text = 'legacy legal text'");
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

}
