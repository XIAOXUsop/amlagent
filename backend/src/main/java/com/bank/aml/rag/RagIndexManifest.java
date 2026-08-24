package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 决定一个向量索引语义空间的完整、不可变身份。 */
public record RagIndexManifest(
        String indexVersion,
        String corpusHash,
        String chunkerVersion,
        String metadataSchemaVersion,
        String embeddingProvider,
        String embeddingModel,
        String embeddingRevision,
        String embeddingModelHash,
        int embeddingDimensions,
        String distanceMetric
) {
    public static RagIndexManifest from(String corpusHash, RagProperties properties) {
        String canonical = String.join("|",
                corpusHash,
                properties.getChunkerVersion(),
                properties.getMetadataSchemaVersion(),
                properties.getEmbedding().getProvider(),
                properties.getEmbedding().getModel(),
                properties.getEmbedding().getRevision(),
                properties.getEmbedding().getModelHash(),
                Integer.toString(properties.getPg().getDimensions()),
                properties.getPg().getDistanceMetric());
        return new RagIndexManifest(sha256(canonical), corpusHash,
                properties.getChunkerVersion(), properties.getMetadataSchemaVersion(),
                properties.getEmbedding().getProvider(), properties.getEmbedding().getModel(),
                properties.getEmbedding().getRevision(), properties.getEmbedding().getModelHash(),
                properties.getPg().getDimensions(), properties.getPg().getDistanceMetric());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("RAG 索引身份哈希失败", e);
        }
    }
}
