package com.bank.aml.rag;

import com.bank.aml.rag.rerank.BgeRerankerScoringModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReRankingLegalSearcherTest {

    @Test
    void reranksEvenWhenUpstreamRequestsTheWholeRecallWindow() {
        HybridLegalSearcher hybrid = mock(HybridLegalSearcher.class);
        BgeRerankerScoringModel model = mock(BgeRerankerScoringModel.class);
        LegalDoc first = new LegalDoc("A", "A", "", "", "第一条");
        LegalDoc second = new LegalDoc("B", "B", "", "", "第二条");
        when(hybrid.searchScored(any(RetrievalRequest.class), eq(20))).thenReturn(List.of(
                SearchHit.of(first), SearchHit.of(second)));
        when(model.tryScoreAll(anyList(), eq("query"))).thenReturn(java.util.Optional.of(List.of(0.1, 0.9)));
        ReRankingLegalSearcher searcher = new ReRankingLegalSearcher(hybrid, model, 20);

        List<LegalDoc> result = searcher.search("query", 20);

        assertThat(result).extracting(LegalDoc::evidenceId).containsExactly("B", "A");
        verify(model).tryScoreAll(anyList(), eq("query"));
    }
}