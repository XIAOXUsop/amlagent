package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
import com.bank.aml.datasource.entity.RagDocumentQuarantineEntity;
import com.bank.aml.datasource.repository.RagDocumentQuarantineRepository;
import com.bank.aml.rag.LegalIndexVersionService;
import com.bank.aml.rag.RagIndexManifest;
import com.bank.aml.rag.RagBuildLeaseHeartbeat;
import com.bank.aml.observability.MetricsRecorder;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 法规文档导入器：应用启动时将 data/legal 下的法规文档按「条-款-项」切分、附加证据元数据并向量化写入 PGVector。
 * <p>{@code P0 可信语料}：每个文档必须提供同级 {@code <base>.manifest.yaml} 可信元数据，缺失/非法默认拒绝
 * （记入隔离审计并跳过），没有合法 manifest 文档时构建失败并保留旧 active 索引。</p>
 * <p>发布链路：BUILDING → SCANNING → EVALUATING →（门禁通过）CANDIDATE → ACTIVE；
 * 门禁不通过 → REJECTED 并保留旧 active。</p>
 */
@Component
public class LegalDocIngestor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegalDocIngestor.class);
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagProperties props;
    private final LegalIndexVersionService indexVersions;
    private final boolean failFastOnEmptyIndex;
    private final LegalDocumentChunker chunker;
    private final LegalCorpusSecurityGate securityGate;
    private final RagBuildLeaseHeartbeat leaseHeartbeat;
    private final MetricsRecorder metrics;
    private final LegalCandidateIndexStore candidateIndexStore;
    private final LegalManifestLoader manifestLoader;
    private final RagDocumentQuarantineRepository quarantine;
    private final LegalIndexPublicationGate publicationGate;

    /** 便捷构造（测试/降级使用）：不注入安全门与 manifest 校验，跳过 manifest 强制与发布门禁。 */
    public LegalDocIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                            RagProperties props, LegalIndexVersionService indexVersions,
                            @Value("${aml.rag.fail-fast-on-empty-index:false}") boolean failFastOnEmptyIndex) {
        this(embeddingModel, embeddingStore, props, indexVersions, failFastOnEmptyIndex,
                new LegalDocumentChunker(), null, null, null, null, null, null, null);
    }

    @Autowired
    public LegalDocIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                            RagProperties props, LegalIndexVersionService indexVersions,
                            @Value("${aml.rag.fail-fast-on-empty-index:false}") boolean failFastOnEmptyIndex,
                            LegalDocumentChunker chunker,
                            LegalCorpusSecurityGate securityGate, RagBuildLeaseHeartbeat leaseHeartbeat,
                            MetricsRecorder metrics, LegalCandidateIndexStore candidateIndexStore,
                            LegalManifestLoader manifestLoader, RagDocumentQuarantineRepository quarantine,
                            LegalIndexPublicationGate publicationGate) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.props = props;
        this.indexVersions = indexVersions;
        this.failFastOnEmptyIndex = failFastOnEmptyIndex;
        this.chunker = chunker;
        this.securityGate = securityGate;
        this.leaseHeartbeat = leaseHeartbeat;
        this.metrics = metrics;
        this.candidateIndexStore = candidateIndexStore;
        this.manifestLoader = manifestLoader;
        this.quarantine = quarantine;
        this.publicationGate = publicationGate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<DocFile> docFiles = loadDocFiles();
            List<DocFile> valid = docFiles.stream().filter(DocFile::acceptable).toList();
            if (valid.isEmpty()) {
                if (failFastOnEmptyIndex && indexVersions.activeVersion().isBlank()) {
                    throw new IllegalStateException("法规目录无可信 manifest 文档且没有可用的 active index");
                }
                log.warn("RAG 导入：无可信 manifest 文档（{} 个被隔离），继续使用旧 active index", docFiles.size());
                return;
            }
            List<Path> files = valid.stream().map(DocFile::file).toList();
            if (securityGate != null) securityGate.validate(files);
            // manifest 强约束：原文哈希一致 + 文号唯一，否则构建失败，不触碰当前 active 指针
            validateManifestConsistency(valid);
            // 内容哈希幂等：法规未变化时跳过重建，避免每次启动 removeAll 清空索引（旧快照读到空/新索引）
            String corpusHash = computeContentHash(valid);
            RagIndexManifest manifest = RagIndexManifest.from(corpusHash, props);
            String indexVersion = manifest.indexVersion();
            if (indexVersion.equals(indexVersions.activeVersion())) {
                log.info("RAG 索引身份未变化（version={}），跳过重建", indexVersion.substring(0, 8));
                return;
            }
            indexVersions.register(manifest);
            String owner = "rag-builder-" + java.util.UUID.randomUUID().toString().substring(0, 12);
            if (!indexVersions.claimBuild(indexVersion, owner)) {
                log.info("RAG 索引 {} 正由其他实例构建，本实例保持使用当前 active version", indexVersion.substring(0, 8));
                return;
            }
            buildAndPublish(valid, indexVersion, owner);
        } catch (Exception e) {
            log.error("RAG 文档导入失败，继续保留旧 active index", e);
            if (failFastOnEmptyIndex && indexVersions.activeVersion().isBlank()) {
                throw new IllegalStateException("生产环境没有可用的法规索引", e);
            }
        }
    }

    private void buildAndPublish(List<DocFile> docs, String contentHash, String owner) {
        long started = System.nanoTime();
        RagBuildLeaseHeartbeat.Lease lease = leaseHeartbeat == null ? null : leaseHeartbeat.start(contentHash, owner);
        try {
            // 安全扫描通过后进入 SCANNING；随后构建候选向量。
            if (indexVersions.markStatus(contentHash, owner, "SCANNING", null)) {
                log.info("RAG 候选索引进入 SCANNING：{}", contentHash.substring(0, 8));
            }
            List<TextSegment> segments = new ArrayList<>();
            for (DocFile doc : docs) {
                segments.addAll(chunk(doc, contentHash));
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("RAG 导入无有效文档片段");
            }
            assertUniqueEvidenceIds(segments, contentHash);
            // 先完成全部 embedding，再写候选版本；任何失败都不会触碰当前 active pointer。
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            if (embeddings.size() != segments.size()) {
                throw new IllegalStateException("Embedding 数量与文档片段不一致");
            }
            assertLease(contentHash, owner, lease);
            // 同版本上次崩溃留下的候选片段必须先清除，否则 Top-K 会被重复 evidenceId 占满。
            if (candidateIndexStore != null) candidateIndexStore.clearCandidate(contentHash);
            embeddingStore.addAll(embeddings, segments);
            assertIndexIntegrity(contentHash, segments.size());
            assertLease(contentHash, owner, lease);
            // 候选评估：发布门禁（Recall/nDCG/拒答/延迟/回退），达标才允许激活
            if (indexVersions.markStatus(contentHash, owner, "EVALUATING", null)) {
                log.info("RAG 候选索引进入 EVALUATING：{}（{} 片段）", contentHash.substring(0, 8), segments.size());
            }
            LegalIndexPublicationGate.GateResult gate = publicationGate == null
                    ? LegalIndexPublicationGate.GateResult.PASSED_WITHOUT_REPORT
                    : publicationGate.evaluate(contentHash, segments.size());
            if (!gate.passed()) {
                indexVersions.markStatus(contentHash, owner, "REJECTED", gate.qualityJson());
                indexVersions.releaseLease(contentHash, owner);
                if (metrics != null) metrics.ragIndexBuild("REJECTED", elapsedMs(started), segments.size());
                log.warn("RAG 候选索引未通过发布门禁，保留旧 active（{}）: {}",
                        gate.failures(), contentHash.substring(0, 8));
                return;
            }
            indexVersions.markStatus(contentHash, owner, "CANDIDATE", gate.qualityJson());
            if (!indexVersions.activate(contentHash, owner, segments.size(), gate.qualityJson())) {
                throw new IllegalStateException("法规索引构建租约已失效，拒绝发布");
            }
            if (metrics != null) metrics.ragIndexBuild("SUCCEEDED", elapsedMs(started), segments.size());
            log.info("RAG 导入完成：{} 个文档片段向量化入库并通过发布门禁（表 {}，hash={}），评估指标 recallAt5={} ndcg={} coldP95={}ms",
                    segments.size(), props.getPg().getTable(), contentHash.substring(0, 8),
                    firstNumber(gate.qualityJson(), "recallAt5"), firstNumber(gate.qualityJson(), "ndcgAt5"),
                    firstNumber(gate.qualityJson(), "coldP95Ms"));
        } catch (Exception e) {
            indexVersions.release(contentHash, owner);
            if (metrics != null) metrics.ragIndexBuild("FAILED", elapsedMs(started), 0);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            if (lease != null) lease.close();
        }
    }

    private List<TextSegment> chunk(DocFile doc, String corpusVersion) throws IOException {
        if (doc.manifest() != null) {
            return chunker.chunk(doc.file(), corpusVersion, doc.manifest());
        }
        // 便捷构造（无 manifest 校验）：回退旧策略头解析
        return chunker.chunk(doc.file(), corpusVersion);
    }

    private void assertUniqueEvidenceIds(List<TextSegment> segments, String contentHash) {
        Set<String> seen = new HashSet<>();
        for (TextSegment segment : segments) {
            String evidenceId = segment.metadata().getString("evidenceId");
            if (!seen.add(evidenceId)) {
                throw new IllegalStateException("候选索引存在重复 evidenceId（索引完整性门禁未通过）: " + evidenceId);
            }
        }
    }

    /** 完整性门禁：落库条数必须与构建片段数一致，确保候选索引 100% 可检索。 */
    private void assertIndexIntegrity(String contentHash, int expectedSegments) {
        if (candidateIndexStore == null) return;
        int stored = candidateIndexStore.candidateCount(contentHash);
        if (stored != expectedSegments) {
            throw new IllegalStateException("索引完整性门禁未通过：落库 " + stored + " != 构建 " + expectedSegments);
        }
    }

    private void validateManifestConsistency(List<DocFile> valid) {
        Map<String, String> documentNumbers = new HashMap<>();
        for (DocFile doc : valid) {
            if (doc.manifest() == null) continue;
            if (!doc.manifest().expectedSourceSha256().isBlank()) {
                String actual = sha256File(doc.file());
                if (!doc.manifest().expectedSourceSha256().equalsIgnoreCase(actual)) {
                    throw new IllegalStateException("manifest sourceSha256 与文档原文不一致（拒绝构建）: "
                            + doc.file().getFileName() + " expect=" + doc.manifest().expectedSourceSha256()
                            + " actual=" + actual);
                }
            }
            String docNumber = doc.manifest().documentNumber();
            if (docNumber != null && !docNumber.isBlank()) {
                String previous = documentNumbers.putIfAbsent(docNumber, doc.file().getFileName().toString());
                if (previous != null) {
                    throw new IllegalStateException("同一 documentNumber 出现于多份文档且内容不同（拒绝构建）: "
                            + docNumber + " → " + previous + " / " + doc.file().getFileName());
                }
            }
        }
    }

    private void assertLease(String version, String owner, RagBuildLeaseHeartbeat.Lease lease) {
        if (lease != null) {
            lease.assertAndRenew();
        } else if (!indexVersions.renewBuildLease(version, owner)) {
            throw new IllegalStateException("法规索引构建租约已失效，拒绝继续构建");
        }
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 法规索引内容哈希（文档原文 + manifest 元数据的 SHA-256），manifest 变更亦触发版本重建。 */
    private String computeContentHash(List<DocFile> docs) throws IOException {
        MessageDigest digest = sha256Digest();
        for (DocFile doc : docs) {
            hashFile(digest, doc.file());
            if (doc.manifestFile() != null && Files.isRegularFile(doc.manifestFile())) {
                hashFile(digest, doc.manifestFile());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256File(Path file) {
        try {
            MessageDigest digest = sha256Digest();
            hashFile(digest, file);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("法规文档哈希失败: " + file, e);
        }
    }

    private void hashFile(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private List<DocFile> loadDocFiles() throws IOException {
        Path dir = Path.of(props.getDataDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<DocFile> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList();
            for (Path file : files) {
                if (manifestLoader == null) {
                    result.add(new DocFile(file, null, null, List.of()));
                    continue;
                }
                Optional<LegalDocumentManifest> loaded = manifestLoader.load(file);
                if (loaded.isEmpty()) {
                    quarantine(file, "MISSING_MANIFEST");
                    result.add(new DocFile(file, null, null, List.of("MISSING_MANIFEST")));
                    continue;
                }
                LegalDocumentManifest manifest = loaded.get();
                if (!manifest.valid()) {
                    quarantine(file, String.join(",", manifest.validationFailures()));
                    result.add(new DocFile(file, manifestLoader.manifestFor(file), manifest,
                            manifest.validationFailures()));
                    continue;
                }
                result.add(new DocFile(file, manifestLoader.manifestFor(file), manifest, List.of()));
            }
        }
        return result;
    }

    private void quarantine(Path file, String reasonCodes) {
        if (quarantine == null) return;
        try {
            String hash = sha256File(file);
            if (!quarantine.existsBySourceFileAndFileHash(file.getFileName().toString(), hash)) {
                RagDocumentQuarantineEntity entity = new RagDocumentQuarantineEntity();
                entity.setSourceFile(file.getFileName().toString());
                entity.setFileHash(hash);
                entity.setReasonCodes(reasonCodes);
                entity.setDetectedAt(LocalDateTime.now());
                quarantine.save(entity);
            }
        } catch (Exception e) {
            log.warn("隔离审计记录写入失败（不影响启动）: {}", e.getMessage());
        }
    }

    private double firstNumber(String json, String field) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path(field);
            return node.isNumber() ? node.asDouble() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private record DocFile(Path file, Path manifestFile, LegalDocumentManifest manifest, List<String> rejectReasons) {
        boolean acceptable() {
            return manifest != null && rejectReasons.isEmpty();
        }
    }
}
