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
        Files.writeString(file, "<!-- rag:jurisdiction=CN;effectiveFrom=2025-01-01;effectiveTo=2027-12-31;accessScopes=AML_INTERNAL;securityStatus=TRUSTED -->\n"
                + "# 内部规则\n\n## 第一条 调查要求\n\n调查人员应核验交易背景和资金来源。\n");

        var metadata = new LegalDocumentChunker().chunk(file, "index-v2").getFirst().metadata();

        assertThat(metadata.getString("effectiveFrom")).isEqualTo("2025-01-01");
        assertThat(metadata.getString("effectiveTo")).isEqualTo("2027-12-31");
        assertThat(metadata.getString("accessScopes")).isEqualTo("AML_INTERNAL");
        assertThat(metadata.getString("securityStatus")).isEqualTo("TRUSTED");
    }

    @Test
    void untrustedWithoutPolicyHeaderIsQuarantined() throws Exception {
        Path file = directory.resolve("unmarked.md");
        Files.writeString(file, "# 未受控文档\n\n## 第一条 内容\n\n来源未受控的文本内容，可能来自外部导入的法规资料，尚未经过受控审核与可信确认。\n");

        var metadata = new LegalDocumentChunker().chunk(file, "index-v3").getFirst().metadata();

        // P0 可信性防御：无策略头绝不默认可信，进入隔离态
        assertThat(metadata.getString("securityStatus")).isEqualTo("UNTRUSTED_METADATA");
        assertThat(metadata.getString("jurisdiction")).isEqualTo("INVALID");
        assertThat(metadata.getString("accessScopes")).isEqualTo("QUARANTINED");
    }

    @Test
    void untrustedUnlessSecurityStatusExplicitlyTrusted() throws Exception {
        Path file = directory.resolve("pending.md");
        Files.writeString(file, "<!-- rag:jurisdiction=CN;accessScopes=AML_INTERNAL -->\n"
                + "# 内部审核中规则\n\n## 第一条 内容\n\n尚未完成正式审核的规则文本内容，需人工复核后才可作为可信依据。\n");

        var metadata = new LegalDocumentChunker().chunk(file, "index-v4").getFirst().metadata();

        // 有策略头但未显式声明 TRUSTED → 不得按可信依据放行
        assertThat(metadata.getString("securityStatus")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void explicitTrustedHeaderAllowsTrusted() throws Exception {
        Path file = directory.resolve("trusted.md");
        Files.writeString(file, "<!-- rag:jurisdiction=CN;accessScopes=PUBLIC_LEGAL;securityStatus=TRUSTED -->\n"
                + "# 授权法规\n\n## 第一条 内容\n\n已受控审核的法规条文内容，是正式发布的有效规范文本。\n");

        var metadata = new LegalDocumentChunker().chunk(file, "index-v5").getFirst().metadata();

        assertThat(metadata.getString("securityStatus")).isEqualTo("TRUSTED");
        assertThat(metadata.getString("jurisdiction")).isEqualTo("CN");
        assertThat(metadata.getString("accessScopes")).isEqualTo("PUBLIC_LEGAL");
    }
}
