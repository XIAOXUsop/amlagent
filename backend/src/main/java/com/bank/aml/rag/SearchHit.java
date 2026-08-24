package com.bank.aml.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索命中完整载体：完整保留各阶段分数、命中的通道与判定原因。
 * <ul>
 *   <li>{@code denseRank/denseScore}：向量召回排名与相似度；</li>
 *   <li>{@code lexicalRank/lexicalScore}：关键词召回排名与加权分值；</li>
 *   <li>{@code fusionScore}：RRF 融合分；</li>
 *   <li>{@code rerankScore}：Cross-Encoder 原始分；</li>
 *   <li>{@code supportProbability}：校准后的支持概率；</li>
 *   <li>{@code channels}：该命中经由哪些检索通道获得；</li>
 *   <li>{@code matchReasons}：命中哪些查询词、匹配文号/条号等可审计原因。</li>
 * </ul>
 */
public record SearchHit(
        LegalDoc document,
        Integer denseRank,
        Double denseScore,
        Integer lexicalRank,
        Double lexicalScore,
        Double fusionScore,
        Double rerankScore,
        Double supportProbability,
        Set<RetrievalChannel> channels,
        List<String> matchReasons
) {
    public SearchHit {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
    }

    public static SearchHit of(LegalDoc document) {
        return new SearchHit(document, null, null, null, null, null, null, null, Set.of(), List.of());
    }

    /** 附上 Reranker 精排分与最终排名（channels 增补 RERANK）。 */
    public SearchHit reranked(int rank, double score) {
        Set<RetrievalChannel> merged = new LinkedHashSet<>(channels);
        merged.add(RetrievalChannel.RERANK);
        return new SearchHit(document, denseRank, denseScore, lexicalRank, lexicalScore,
                fusionScore, score, supportProbability, merged, matchReasons);
    }

    /** 附上支持概率（校准后）。 */
    public SearchHit support(double probability) {
        return new SearchHit(document, denseRank, denseScore, lexicalRank, lexicalScore,
                fusionScore, rerankScore, probability, channels, matchReasons);
    }

    /** 追加可审计匹配原因。 */
    public SearchHit withReasons(List<String> extra) {
        List<String> merged = new ArrayList<>(matchReasons);
        if (extra != null) merged.addAll(extra);
        return new SearchHit(document, denseRank, denseScore, lexicalRank, lexicalScore,
                fusionScore, rerankScore, supportProbability, channels, merged);
    }

    /** 合并同一 evidenceId 的多路召回字段，不能只叠加 RRF 分而丢失另一通道的审计信息。 */
    public SearchHit mergeRecall(SearchHit other) {
        if (other == null) return this;
        if (!java.util.Objects.equals(document.evidenceId(), other.document.evidenceId())) {
            throw new IllegalArgumentException("只能合并同一 evidenceId 的召回结果");
        }
        Set<RetrievalChannel> mergedChannels = new LinkedHashSet<>(channels);
        mergedChannels.addAll(other.channels);
        Set<String> mergedReasons = new LinkedHashSet<>(matchReasons);
        mergedReasons.addAll(other.matchReasons);
        return new SearchHit(document,
                first(denseRank, other.denseRank), first(denseScore, other.denseScore),
                first(lexicalRank, other.lexicalRank), first(lexicalScore, other.lexicalScore),
                first(fusionScore, other.fusionScore), first(rerankScore, other.rerankScore),
                first(supportProbability, other.supportProbability), mergedChannels, List.copyOf(mergedReasons));
    }

    private static final RetrievalChannel RERANK_CHANNEL = RetrievalChannel.RERANK;

    public static SearchHit dense(int rank, double score, LegalDoc doc) {
        return new SearchHit(doc, rank, score, null, null, null, null, null,
                Set.of(RetrievalChannel.DENSE), List.of());
    }

    public static SearchHit lexical(int rank, double score, LegalDoc doc, List<String> reasons) {
        return new SearchHit(doc, null, null, rank, score, null, null, null,
                Set.of(RetrievalChannel.LEXICAL), new ArrayList<>(reasons));
    }

    /** RRF 融合：合并通道并写入融合分，清空单侧排名以保持最终排序语义。 */
    public SearchHit fused(double score, Set<RetrievalChannel> allChannels, List<String> reasons) {
        Set<RetrievalChannel> merged = new LinkedHashSet<>(channels);
        merged.add(RetrievalChannel.FUSION);
        merged.addAll(allChannels);
        Set<String> mergedReasons = new LinkedHashSet<>(matchReasons);
        if (reasons != null) mergedReasons.addAll(reasons);
        return new SearchHit(document, denseRank, denseScore, lexicalRank, lexicalScore,
                score, rerankScore, supportProbability, merged, List.copyOf(mergedReasons));
    }

    private static <T> T first(T left, T right) {
        return left != null ? left : right;
    }
}
