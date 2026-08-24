package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用本机 Docker PostgreSQL 验证动态 SQL 的真实占位符顺序和字段加权。 */
@Tag("integration")
class KeywordLegalSearcherIntegrationTest {
    private static final String VERSION = "a".repeat(64);
    private static final String TABLE = "legal_docs_kw_it_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate jdbc;
    private static KeywordLegalSearcher searcher;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5433/aml_rag",
                System.getenv().getOrDefault("PG_USER", "aml"),
                System.getenv().getOrDefault("PG_PASSWORD", "aml123456"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE " + TABLE + " (text text NOT NULL, metadata jsonb NOT NULL)");
        insert("非自然人客户当日累计转账人民币200万元以上应当报告", "大额交易报告", "中国人民银行令〔2016〕第3号", "第三条", "LEGAL-KW-001");
        insert("金融机构应当保存客户身份资料和交易记录", "客户尽职调查", "中国人民银行令〔2025〕第11号", "第四十四条", "LEGAL-KW-002");

        RagProperties properties = new RagProperties();
        properties.getPg().setTable(TABLE);
        searcher = new KeywordLegalSearcher(dataSource, properties, () -> VERSION, new LegalQueryAnalyzer());
    }

    @AfterAll
    static void tearDown() {
        if (jdbc != null) jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    void bindsScoreConditionsAndVersionInSqlOrder() {
        var request = new RetrievalRequest("公司账户两百万元大额交易报告", "大额交易", Instant.parse("2026-08-01T00:00:00Z"),
                "CN", Set.of("PUBLIC_LEGAL"), 5, 0.04, RetrievalTarget.SPECIFIC_VERSION, VERSION,
                CacheMode.BYPASS_READ_WRITE);

        var hits = searcher.searchScored(request, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().document().evidenceId()).isEqualTo("LEGAL-KW-001");
        assertThat(hits.getFirst().lexicalScore()).isPositive();
        assertThat(hits.getFirst().channels()).contains(RetrievalChannel.LEXICAL);
    }

    private static void insert(String text, String title, String documentNumber, String article, String evidenceId) {
        String metadata = """
                {"title":"%s","documentNumber":"%s","articleNumber":"%s","evidenceId":"%s",
                 "documentId":"DOC-KW","jurisdiction":"CN","accessScopes":"PUBLIC_LEGAL",
                 "contentDigest":"digest","corpusVersion":"%s","sourceFile":"it.md","securityStatus":"TRUSTED"}
                """.formatted(title, documentNumber, article, evidenceId, VERSION);
        jdbc.update("INSERT INTO " + TABLE + " (text, metadata) VALUES (?, ?::jsonb)", text, metadata);
    }
}
