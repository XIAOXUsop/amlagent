package com.bank.aml.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentChunkerTest {
    @TempDir Path directory;

    @Test
    void preservesArticleParentAndEvidenceProvenance() throws Exception {
        Path file = directory.resolve("办法.md");
        Files.writeString(file, "# 测试管理办法\n\n## 第三条 大额交易标准\n\n金融机构应报告当日累计交易。\n");

        var chunks = new LegalDocumentChunker().chunk(file, "index-v1");

        assertThat(chunks).hasSize(1);
        var segment = chunks.getFirst();
        assertThat(segment.text()).contains("第三条 大额交易标准", "当日累计交易");
        assertThat(segment.metadata().getString("articleNumber")).isEqualTo("第三条");
        assertThat(segment.metadata().getString("documentId")).startsWith("DOC-");
        assertThat(segment.metadata().getString("chunkId")).startsWith("CHUNK-");
        assertThat(segment.metadata().getString("contentDigest")).hasSize(64);
        assertThat(segment.metadata().getString("corpusVersion")).isEqualTo("index-v1");
    }

    @Test
    void parsesEffectivePeriodAndAccessPolicyFromControlledHeader() throws Exception {
        Path file = directory.resolve("internal.md");
        Files.writeString(file, "<!-- rag:jurisdiction=CN;effectiveFrom=2025-01-01;effectiveTo=2027-12-31;accessScopes=AML_INTERNAL -->\n"
                + "# 内部规则\n\n## 第一条 调查要求\n\n调查人员应核验交易背景和资金来源。\n");

        var metadata = new LegalDocumentChunker().chunk(file, "index-v2").getFirst().metadata();

        assertThat(metadata.getString("effectiveFrom")).isEqualTo("2025-01-01");
        assertThat(metadata.getString("effectiveTo")).isEqualTo("2027-12-31");
        assertThat(metadata.getString("accessScopes")).isEqualTo("AML_INTERNAL");
    }
}
