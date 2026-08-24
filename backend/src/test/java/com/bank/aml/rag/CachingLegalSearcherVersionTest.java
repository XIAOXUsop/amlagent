package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class CachingLegalSearcherVersionTest {
    @Test
    @SuppressWarnings("unchecked")
    void actualActiveCorpusHashSeparatesCacheEntries() {
        ReRankingLegalSearcher delegate = mock(ReRankingLegalSearcher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        MetricsRecorder metrics = mock(MetricsRecorder.class);
        LegalIndexVersionProvider versions = mock(LegalIndexVersionProvider.class);
        doReturn(values).when(redis).opsForValue();
        when(values.get(anyString())).thenReturn(null);
        when(delegate.search("客户尽职调查", 3)).thenReturn(List.of());
        when(versions.activeVersion()).thenReturn("content-hash-v1", "content-hash-v2");
        CachingLegalSearcher searcher = new CachingLegalSearcher(delegate, redis, metrics,
                new ObjectMapper(), 60, versions, "embedding-v1", "reranker-v1");

        searcher.search("客户尽职调查", 3);
        searcher.search("客户尽职调查", 3);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).contains("content-hash-v1");
        assertThat(keys.getAllValues().get(1)).contains("content-hash-v2");
        verify(delegate, org.mockito.Mockito.times(2)).search("客户尽职调查", 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorizationScopeAndAsOfDateSeparateCacheEntries() {
        ReRankingLegalSearcher delegate = mock(ReRankingLegalSearcher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        doReturn(values).when(redis).opsForValue();
        when(values.get(anyString())).thenReturn(null);
        LegalIndexVersionProvider versions = () -> "index-v1";
        CachingLegalSearcher searcher = new CachingLegalSearcher(delegate, redis, mock(MetricsRecorder.class),
                new ObjectMapper(), 60, versions, "embedding-v1", "reranker-v1");
        RetrievalRequest publicRequest = new RetrievalRequest("客户尽调", "尽调",
                Instant.parse("2026-01-01T00:00:00Z"), "CN", Set.of("PUBLIC_LEGAL"), 3, 0.04);
        RetrievalRequest internalRequest = new RetrievalRequest("客户尽调", "尽调",
                Instant.parse("2026-01-01T00:00:00Z"), "CN", Set.of("AML_INTERNAL"), 3, 0.04);

        searcher.search(publicRequest, 3);
        searcher.search(internalRequest, 3);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeRerankerAvailabilitySeparatesCacheEntries() {
        ReRankingLegalSearcher delegate = mock(ReRankingLegalSearcher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        doReturn(values).when(redis).opsForValue();
        when(values.get(anyString())).thenReturn(null);
        when(delegate.search("客户尽调", 3)).thenReturn(List.of());
        when(delegate.pipelineIdentity()).thenReturn("rerank-false-window20", "rerank-true-window20");
        CachingLegalSearcher searcher = new CachingLegalSearcher(delegate, redis, mock(MetricsRecorder.class),
                new ObjectMapper(), 60, () -> "index-v1", "embedding-v1", "reranker-v1");

        searcher.search("客户尽调", 3);
        searcher.search("客户尽调", 3);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).contains("rerank-false-window20");
        assertThat(keys.getAllValues().get(1)).contains("rerank-true-window20");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotCacheResultWhenPipelineChangesDuringRetrieval() {
        ReRankingLegalSearcher delegate = mock(ReRankingLegalSearcher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        doReturn(values).when(redis).opsForValue();
        when(values.get(anyString())).thenReturn(null);
        when(delegate.search("客户尽调", 3)).thenReturn(List.of());
        when(delegate.pipelineIdentity()).thenReturn("rerank-true-f0", "rerank-true-f1");
        CachingLegalSearcher searcher = new CachingLegalSearcher(delegate, redis, mock(MetricsRecorder.class),
                new ObjectMapper(), 60, () -> "index-v1", "embedding-v1", "reranker-v1");

        searcher.search("客户尽调", 3);

        verify(values, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }
}
