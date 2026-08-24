package com.bank.aml.rag;

import com.bank.aml.datasource.repository.LegalIndexStateRepository;
import com.bank.aml.datasource.repository.RagIndexManifestRepository;
import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** MySQL 中央发布指针：所有实例检索和缓存使用同一个 active corpus hash。 */
@Service
public class LegalIndexVersionService implements LegalIndexVersionProvider {
    private final LegalIndexStateRepository repository;
    private final RagIndexManifestRepository manifests;

    public LegalIndexVersionService(LegalIndexStateRepository repository,
                                    RagIndexManifestRepository manifests) {
        this.repository = repository;
        this.manifests = manifests;
    }

    @Override
    public String activeVersion() {
        return repository.findById("legal").map(s -> s.getActiveVersion() == null ? "" : s.getActiveVersion())
                .orElse("");
    }

    @Transactional
    public boolean claimBuild(String version, String owner) {
        LocalDateTime now = LocalDateTime.now();
        // Flyway 会预置该行；这里仍做并发安全自愈，兼容 ddl-auto 开发/测试库和被误删的状态行。
        repository.ensureStateRow(now);
        return repository.claimBuild(version, owner, now, now.plusMinutes(15)) == 1;
    }

    /** 幂等登记完整索引身份；同一 indexVersion 的字段由哈希定义，不允许被覆盖。 */
    @Transactional
    public void register(RagIndexManifest manifest) {
        if (manifests.existsById(manifest.indexVersion())) return;
        LocalDateTime now = LocalDateTime.now();
        RagIndexManifestEntity entity = new RagIndexManifestEntity();
        entity.setIndexVersion(manifest.indexVersion());
        entity.setCorpusHash(manifest.corpusHash());
        entity.setChunkerVersion(manifest.chunkerVersion());
        entity.setMetadataSchemaVersion(manifest.metadataSchemaVersion());
        entity.setEmbeddingProvider(manifest.embeddingProvider());
        entity.setEmbeddingModel(manifest.embeddingModel());
        entity.setEmbeddingRevision(manifest.embeddingRevision());
        entity.setEmbeddingModelHash(manifest.embeddingModelHash());
        entity.setEmbeddingDimensions(manifest.embeddingDimensions());
        entity.setDistanceMetric(manifest.distanceMetric());
        entity.setStatus("CANDIDATE");
        entity.setSegmentCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        manifests.save(entity);
    }

    public boolean renewBuildLease(String version, String owner) {
        LocalDateTime now = LocalDateTime.now();
        return repository.renewBuildLease(version, owner, now, now.plusMinutes(15)) == 1;
    }

    @Transactional
    public boolean activate(String version, String owner, int segmentCount) {
        LocalDateTime now = LocalDateTime.now();
        String oldActive = activeVersion();
        if (repository.activate(version, owner, segmentCount, now) != 1) return false;
        if (!oldActive.isBlank() && !oldActive.equals(version)) {
            manifests.findById(oldActive).ifPresent(old -> {
                old.setStatus("RETIRED");
                old.setRetiredAt(now);
                old.setUpdatedAt(now);
                manifests.save(old);
            });
        }
        RagIndexManifestEntity active = manifests.findById(version)
                .orElseThrow(() -> new IllegalStateException("索引 Manifest 不存在: " + version));
        active.setStatus("ACTIVE");
        active.setSegmentCount(segmentCount);
        active.setQualityReportJson("{\"smokeSearch\":true,\"segmentCount\":" + segmentCount + "}");
        active.setActivatedAt(now);
        active.setRetiredAt(null);
        active.setFailureCode(null);
        active.setUpdatedAt(now);
        manifests.save(active);
        return true;
    }

    @Transactional
    public void release(String version, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if (repository.releaseBuild(version, owner, now) == 1) {
            manifests.findById(version).ifPresent(candidate -> {
                candidate.setStatus("FAILED");
                candidate.setFailureCode("BUILD_FAILED");
                candidate.setUpdatedAt(now);
                manifests.save(candidate);
            });
        }
    }

    /** 仅允许回滚到已成功发布过的 ACTIVE/RETIRED 版本。 */
    @Transactional
    public boolean rollback(String targetVersion) {
        // 与 cleanup 共用 legal_index_state 行锁，避免“刚回滚为 ACTIVE 又被并发清理”的竞态。
        var state = repository.findForUpdate("legal")
                .orElseThrow(() -> new IllegalStateException("法规索引状态行不存在"));
        RagIndexManifestEntity target = manifests.findById(targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("目标索引版本不存在"));
        if (!java.util.Set.of("ACTIVE", "RETIRED").contains(target.getStatus())) {
            throw new IllegalStateException("目标索引从未成功发布，禁止回滚");
        }
        LocalDateTime now = LocalDateTime.now();
        String oldActive = state.getActiveVersion() == null ? "" : state.getActiveVersion();
        if (oldActive.equals(targetVersion)) return true;
        state.setPreviousVersion(oldActive);
        state.setActiveVersion(targetVersion);
        state.setSegmentCount(target.getSegmentCount());
        state.setUpdatedAt(now);
        manifests.findById(oldActive).ifPresent(old -> {
            old.setStatus("RETIRED"); old.setRetiredAt(now); old.setUpdatedAt(now); manifests.save(old);
        });
        target.setStatus("ACTIVE"); target.setActivatedAt(now); target.setRetiredAt(null); target.setUpdatedAt(now);
        manifests.save(target);
        return true;
    }

    /** 在中央状态行锁保护下把可清理版本转为 PURGING；一旦提交，rollback 将拒绝该版本。 */
    @Transactional
    public boolean markPurgingIfSafe(String version, java.util.Set<String> additionallyProtected) {
        var state = repository.findForUpdate("legal")
                .orElseThrow(() -> new IllegalStateException("法规索引状态行不存在"));
        if (version.equals(state.getActiveVersion()) || version.equals(state.getPreviousVersion())
                || additionallyProtected.contains(version)) {
            return false;
        }
        return manifests.findById(version).map(manifest -> {
            if (!java.util.Set.of("FAILED", "RETIRED", "PURGING").contains(manifest.getStatus())) return false;
            manifest.setStatus("PURGING");
            manifest.setUpdatedAt(LocalDateTime.now());
            manifests.save(manifest);
            return true;
        }).orElse(false);
    }

    public java.util.List<RagIndexManifestEntity> manifests() {
        return manifests.findAllByOrderByCreatedAtDesc();
    }

    public String previousVersion() {
        return repository.findById("legal").map(s -> s.getPreviousVersion() == null ? "" : s.getPreviousVersion())
                .orElse("");
    }
}
