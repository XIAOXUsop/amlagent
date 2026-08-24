package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
import com.bank.aml.rag.LegalIndexVersionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void activatesOnlyAfterCandidateEmbeddingsAreWrittenAndSearchable() throws Exception {
        Files.writeString(directory.resolve("law.md"),
                "中华人民共和国反洗钱法\n\n第三十二条 金融机构应当按照规定识别客户身份并保存完整记录。");
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        LegalIndexVersionService versions = mock(LegalIndexVersionService.class);
        when(versions.activeVersion()).thenReturn("");
        when(versions.claimBuild(anyString(), anyString())).thenReturn(true);
        when(versions.renewBuildLease(anyString(), anyString())).thenReturn(true);
        when(versions.activate(anyString(), anyString(), any(Integer.class))).thenReturn(true);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(ignored -> Embedding.from(new float[384])).toList());
        });
        @SuppressWarnings("unchecked") EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        new LegalDocIngestor(model, store, properties(), versions, false).run(null);

        verify(store).addAll(anyList(), anyList());
        verify(store, never()).removeAll();
        verify(versions).activate(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void embeddingFailureKeepsOldActivePointerAndReleasesBuildLease() throws Exception {
        Files.writeString(directory.resolve("law.md"),
                "中华人民共和国反洗钱法\n\n第三十二条 金融机构应当按照规定识别客户身份并保存完整记录。");
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        LegalIndexVersionService versions = mock(LegalIndexVersionService.class);
        when(versions.activeVersion()).thenReturn("old-version");
        when(versions.claimBuild(anyString(), anyString())).thenReturn(true);
        when(model.embedAll(anyList())).thenThrow(new IllegalStateException("embedding unavailable"));

        new LegalDocIngestor(model, store, properties(), versions, false).run(null);

        verify(versions).release(anyString(), anyString());
        verify(versions, never()).activate(anyString(), anyString(), any(Integer.class));
        verify(store, never()).removeAll();
    }

    private RagProperties properties() {
        RagProperties properties = new RagProperties();
        properties.setDataDir(directory.toString());
        properties.getPg().setTable("legal_docs_test");
        return properties;
    }
}
