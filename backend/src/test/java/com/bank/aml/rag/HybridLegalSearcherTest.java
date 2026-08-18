package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(vector.search("q", 15)).thenReturn(List.of(a, b));
        when(keyword.search("q", 15)).thenReturn(List.of(a));

        List<LegalDoc> result = hybrid.search("q", 5);

        assertThat(result).isNotEmpty();
        // A 两路命中，应排第一
        assertThat(result.get(0).evidenceId()).isEqualTo("A");
        // 去重：A 只出现一次
        assertThat(result).extracting(LegalDoc::evidenceId)
                .containsOnlyOnce("A")
                .contains("B");
    }

    /** 结果按 RRF 分数降序：向量路 rank 较高的排在 rank 较低之前 */
    @Test
    void resultsOrderedByDescendingRrfScore() {
        when(vector.search("q", 15)).thenReturn(List.of(doc("A"), doc("B"), doc("C")));
        when(keyword.search("q", 15)).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 5);
        assertThat(result)
                .extracting(LegalDoc::evidenceId)
                .containsExactly("A", "B", "C");
    }

    /** topK 截断：只返回前 K 条 */
    @Test
    void returnsOnlyTopK() {
        when(vector.search("q", 10)).thenReturn(List.of(doc("A"), doc("B"), doc("C"), doc("D"), doc("E")));
        when(keyword.search("q", 10)).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 2);
        assertThat(result).hasSize(2);
    }

    /** 无 evidenceId 的条目被跳过（不能作为可追溯证据） */
    @Test
    void skipsDocsWithoutEvidenceId() {
        when(vector.search("q", 15)).thenReturn(List.of(doc("X"), new LegalDoc("", "无ID标题", null, null, "正文")));
        when(keyword.search("q", 15)).thenReturn(List.of());
        List<LegalDoc> result = hybrid.search("q", 5);
        assertThat(result).extracting(LegalDoc::evidenceId).containsExactly("X");
    }

    /** 两路均无结果返回空列表 */
    @Test
    void noHitsReturnsEmpty() {
        when(vector.search("q", 10)).thenReturn(List.of());
        when(keyword.search("q", 10)).thenReturn(List.of());
        assertThat(hybrid.search("q", 2)).isEmpty();
    }
}
