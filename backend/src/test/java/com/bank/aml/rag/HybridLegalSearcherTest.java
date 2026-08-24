package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 混合检索 RRF 融合逻辑单元测试：验证两路召回（向量 + 关键词）的倒数排序融合、
 * 按 evidenceId 去重、跨路命中排前、以及 topK 截断。不依赖真实 PGVector。
 */
class HybridLegalSearcherTest {

    private static LegalDoc doc(String id) {
        return new LegalDoc(id, "标题" + id, "文号", "第X条", "内容" + id);
    }

    private final VectorLegalSearcher vector = mock(VectorLegalSearcher.class);
    private final KeywordLegalSearcher keyword = mock(KeywordLegalSearcher.class);
    private final HybridLegalSearcher hybrid = new HybridLegalSearcher(vector, keyword);

    /** 同一 evidenceId 在两路都命中（向量第1、关键词第3）时，RRF 得分应高于单路命中项，排在最前 */
    @Test
    void rerfFusionBoostsDocHitInBothRanks() {
        LegalDoc a = doc("A");
        LegalDoc b = doc("B"); // 仅在向量路命中
        when(vector.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of(
                SearchHit.dense(1, 0.9, a), SearchHit.dense(2, 0.8, b)));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(15)))
                .thenReturn(List.of(SearchHit.lexical(3, 1.2, a, List.of("命中条号"))));

        List<LegalDoc> result = hybrid.search("q", 5);

        assertThat(result).isNotEmpty();
        // A 两路命中，应排第一
        assertThat(result.get(0).evidenceId()).isEqualTo("A");
        // 去重：A 只出现一次
        assertThat(result).extracting(LegalDoc::evidenceId)
                .containsOnlyOnce("A")
                .contains("B");
    }

    @Test
    void preservesDenseAndLexicalScoresWhenSameEvidenceHitsBothChannels() {
        LegalDoc a = doc("A");
        when(vector.searchScored(any(RetrievalRequest.class), eq(15)))
                .thenReturn(List.of(SearchHit.dense(1, 0.91, a)));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(15)))
                .thenReturn(List.of(SearchHit.lexical(2, 4.5, a, List.of("命中标题"))));

        SearchHit hit = hybrid.searchScored(new RetrievalRequest("q", "q", java.time.Instant.now(),
                "CN", java.util.Set.of("PUBLIC_LEGAL"), 5, 0), 5).getFirst();

        assertThat(hit.denseRank()).isEqualTo(1);
        assertThat(hit.denseScore()).isEqualTo(0.91);
        assertThat(hit.lexicalRank()).isEqualTo(2);
        assertThat(hit.lexicalScore()).isEqualTo(4.5);
        assertThat(hit.channels()).contains(RetrievalChannel.DENSE, RetrievalChannel.LEXICAL, RetrievalChannel.FUSION);
        assertThat(hit.matchReasons()).contains("命中标题");
    }

    @Test
    void strongFieldedLexicalMatchCanBeatDenseOnlyNoise() {
        LegalDoc denseNoise = doc("DENSE-NOISE");
        LegalDoc exact = doc("EXACT");
        when(vector.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of(
                SearchHit.dense(1, 0.90, denseNoise), SearchHit.dense(8, 0.70, exact)));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of(
                SearchHit.lexical(1, 12.0, exact, List.of("命中文号", "命中条号"))));

        assertThat(hybrid.search("q", 5).getFirst().evidenceId()).isEqualTo("EXACT");
    }

    /** 结果按 RRF 分数降序：向量路 rank 较高的排在 rank 较低之前 */
    @Test
    void resultsOrderedByDescendingRrfScore() {
        when(vector.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of(
                SearchHit.dense(1, 0.9, doc("A")), SearchHit.dense(2, 0.8, doc("B")),
                SearchHit.dense(3, 0.7, doc("C"))));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 5);
        assertThat(result)
                .extracting(LegalDoc::evidenceId)
                .containsExactly("A", "B", "C");
    }

    /** topK 截断：只返回前 K 条 */
    @Test
    void returnsOnlyTopK() {
        when(vector.searchScored(any(RetrievalRequest.class), eq(10))).thenReturn(List.of(
                SearchHit.dense(1, 0.9, doc("A")), SearchHit.dense(2, 0.8, doc("B")),
                SearchHit.dense(3, 0.7, doc("C")), SearchHit.dense(4, 0.6, doc("D")),
                SearchHit.dense(5, 0.5, doc("E"))));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(10))).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 2);
        assertThat(result).hasSize(2);
    }

    /** 无 evidenceId 的条目被跳过（不能作为可追溯证据） */
    @Test
    void skipsDocsWithoutEvidenceId() {
        when(vector.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of(
                SearchHit.dense(1, 0.9, doc("X")),
                SearchHit.dense(2, 0.8, new LegalDoc("", "无ID标题", null, null, "正文"))));
        when(keyword.searchScored(any(RetrievalRequest.class), eq(15))).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 5);
        assertThat(result).extracting(LegalDoc::evidenceId).containsExactly("X");
    }

    /** 两路均无结果返回空列表 */
    @Test
    void noHitsReturnsEmpty() {
        when(vector.searchScored(any(RetrievalRequest.class), eq(10))).thenReturn(List.of());
        when(keyword.searchScored(any(RetrievalRequest.class), eq(10))).thenReturn(List.of());
        assertThat(hybrid.search("q", 2)).isEmpty();
    }
}
