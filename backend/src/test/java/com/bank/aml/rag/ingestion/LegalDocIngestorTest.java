package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
import com.bank.aml.rag.LegalIndexVersionService;
import com.bank.aml.datasource.repository.RagDocumentQuarantineRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalDocIngestorTest {
    @TempDir Path directory;

    @Test
    void activatesOnlyAfterCandidateEmbeddingsWithManifestAreWrittenAndSearchable() throws Exception {
        String body = "中华人民共和国反洗钱法\n\n第三十二条 金融机构应当按照规定识别客户身份并保存完整记录，"
                + "用于客户尽职调查和可疑交易监测。\n";
        writeDocWithManifest("law.md", body, "AML-REG-TEST-1");
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        LegalIndexVersionService versions = mock(LegalIndexVersionService.class);
        when(versions.activeVersion()).thenReturn("");
        when(versions.claimBuild(anyString(), anyString())).thenReturn(true);
        when(versions.renewBuildLease(anyString(), anyString())).thenReturn(true);
        when(versions.activate(anyString(), anyString(), any(Integer.class), anyString())).thenReturn(true);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(ignored -> Embedding.from(new float[384])).toList());
        });
        @SuppressWarnings("unchecked") EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        new LegalDocIngestor(model, store, properties(), versions, false,
                new LegalDocumentChunker(), null, null, null, null,
                new LegalManifestLoader(), mock(RagDocumentQuarantineRepository.class), null).run(null);

        verify(store).addAll(anyList(), anyList());
        verify(store, never()).removeAll();
        verify(versions).activate(anyString(), anyString(), any(Integer.class), anyString());
    }

    @Test
    void missingManifestQuarantinesDocumentAndSkipsBuild() throws Exception {
        // 无 manifest 的文档在生产路径下被隔离，导入失败且不发布新版本
        Files.writeString(directory.resolve("unmanaged.md"),
                "中华人民共和国反洗钱法\n\n第三十二条 金融机构应当按照规定识别客户身份并保存完整记录，用于反洗钱监测。\n");
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        LegalIndexVersionService versions = mock(LegalIndexVersionService.class);
        RagDocumentQuarantineRepository quarantine = mock(RagDocumentQuarantineRepository.class);
        when(versions.activeVersion()).thenReturn("active-v1");
        when(quarantine.existsBySourceFileAndFileHash(anyString(), anyString())).thenReturn(false);

        new LegalDocIngestor(model, store, properties(), versions, false,
                new LegalDocumentChunker(), null, null, null, null,
                new LegalManifestLoader(), quarantine, null).run(null);

        verify(versions, never()).activate(anyString(), anyString(), any(Integer.class), anyString());
    }

    @Test
    void embeddingFailureKeepsOldActivePointerAndReleasesBuildLease() throws Exception {
        String body = "中华人民共和国反洗钱法\n\n第三十二条 金融机构应当按照规定识别客户身份并保存完整记录，"
                + "用于客户尽职调查和可疑交易监测。\n";
        writeDocWithManifest("law.md", body, "AML-REG-TEST-2");
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        LegalIndexVersionService versions = mock(LegalIndexVersionService.class);
        when(versions.activeVersion()).thenReturn("old-version");
        when(versions.claimBuild(anyString(), anyString())).thenReturn(true);
        when(model.embedAll(anyList())).thenThrow(new IllegalStateException("embedding unavailable"));

        new LegalDocIngestor(model, store, properties(), versions, false,
                new LegalDocumentChunker(), null, null, null, null,
                new LegalManifestLoader(), mock(RagDocumentQuarantineRepository.class), null).run(null);

        verify(versions).release(anyString(), anyString());
        verify(versions, never()).activate(anyString(), anyString(), any(Integer.class), anyString());
        verify(store, never()).removeAll();
    }

    private void writeDocWithManifest(String name, String body, String documentId) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, body);
        Files.writeString(directory.resolve(name.replaceFirst("\\.(md|txt)$", "") + ".manifest.yaml"),
                "documentId: " + documentId + "\n"
                        + "title: 反洗钱法测试\n"
                        + "documentNumber: 测试令〔2022〕第1号\n"
                        + "issuingAuthority: 测试机构\n"
                        + "jurisdiction: CN\n"
                        + "promulgatedAt: 2022-01-01\n"
                        + "effectiveFrom: 2022-01-01\n"
                        + "effectiveTo: null\n"
                        + "sourceUrl: https://www.pbc.gov.cn/test/regulation.html\n"
                        + "sourceType: CURATED_SUMMARY\n"
                        + "accessScopes: [PUBLIC_LEGAL]\n"
                        + "reviewStatus: APPROVED\n"
                        + "reviewedBy: reviewer\n"
                        + "reviewedAt: 2026-01-01\n"
                        + "sourceSha256: " + sha256(body) + "\n"
                        + "supersedes: null\n"
                        + "parserVersion: legal-article-v3\n"
                        + "securityStatus: TRUSTED\n");
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.setDataDir(directory.toString());
        properties.getPg().setTable("legal_docs_test");
        return properties;
    }
}
