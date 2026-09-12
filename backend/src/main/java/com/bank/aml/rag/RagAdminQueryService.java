package com.bank.aml.rag;

import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import com.bank.aml.datasource.repository.RagDocumentQuarantineRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RAG 管理面的只读查询与稳定响应投影。 */
@Service
public class RagAdminQueryService {

    private final LegalIndexVersionService versions;

    private final RagDocumentQuarantineRepository quarantines;

    public RagAdminQueryService(LegalIndexVersionService versions, RagDocumentQuarantineRepository quarantines) {
        this.versions = versions;
        this.quarantines = quarantines;
    }

    @Transactional(readOnly = true)
    public List<ManifestView> manifests() {
        return versions.manifests().stream().map(ManifestView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<QuarantineView> quarantines() {
        return quarantines.findTop100ByOrderByDetectedAtDesc()
            .stream()
            .map(item -> new QuarantineView(item.getId(), item.getSourceFile(), item.getFileHash(),
                    List.of(item.getReasonCodes().split(",")), toInstant(item.getDetectedAt())))
            .toList();
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record QuarantineView(Long id, String sourceFile, String fileHash, List<String> reasonCodes,
            Instant detectedAt) {
    }

    public record ManifestView(String indexVersion, String corpusHash, String chunkerVersion,
            String metadataSchemaVersion, String embeddingProvider, String embeddingModel, String embeddingRevision,
            int embeddingDimensions, String distanceMetric, String status, int segmentCount, String qualityReportJson,
            String failureCode, Instant createdAt, Instant activatedAt, Instant retiredAt) {

        static ManifestView from(RagIndexManifestEntity manifest) {
            return new ManifestView(manifest.getIndexVersion(), manifest.getCorpusHash(), manifest.getChunkerVersion(),
                    manifest.getMetadataSchemaVersion(), manifest.getEmbeddingProvider(), manifest.getEmbeddingModel(),
                    manifest.getEmbeddingRevision(), manifest.getEmbeddingDimensions(), manifest.getDistanceMetric(),
                    manifest.getStatus(), manifest.getSegmentCount(), manifest.getQualityReportJson(),
                    manifest.getFailureCode(), toInstant(manifest.getCreatedAt()), toInstant(manifest.getActivatedAt()),
                    toInstant(manifest.getRetiredAt()));
        }
    }

}
