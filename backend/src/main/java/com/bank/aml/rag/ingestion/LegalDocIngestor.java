package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 法规文档导入器：应用启动时将 data/legal 下的法规/案例文档切分、附加证据元数据并向量化写入 PGVector。
 * <p>每条片段按标题、条款与正文内容生成稳定的内容哈希 {@code evidenceId}，
 * 文档重排不会改变证据标识，供报告引用与回溯。</p>
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

    public LegalDocIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                            RagProperties props, LegalIndexVersionService indexVersions,
                            @org.springframework.beans.factory.annotation.Value("${aml.rag.fail-fast-on-empty-index:false}")
                            boolean failFastOnEmptyIndex) {
        this(embeddingModel, embeddingStore, props, indexVersions, failFastOnEmptyIndex,
                new LegalDocumentChunker(), null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LegalDocIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                            RagProperties props, LegalIndexVersionService indexVersions,
                            @org.springframework.beans.factory.annotation.Value("${aml.rag.fail-fast-on-empty-index:false}")
                            boolean failFastOnEmptyIndex, LegalDocumentChunker chunker,
                            LegalCorpusSecurityGate securityGate, RagBuildLeaseHeartbeat leaseHeartbeat,
                            MetricsRecorder metrics, LegalCandidateIndexStore candidateIndexStore) {
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
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Path> files = loadDocFiles();
            if (files.isEmpty()) {
                if (failFastOnEmptyIndex && indexVersions.activeVersion().isBlank()) {
                    throw new IllegalStateException("法规目录为空且没有可用的 active index");
                }
                log.warn("RAG 导入：目录 {} 下未找到法规文档，继续使用旧 active index", props.getDataDir());
                return;
            }
            if (securityGate != null) securityGate.validate(files);
            // 内容哈希幂等：法规未变化时跳过重建，避免每次启动 removeAll 清空索引（旧快照读到空/新索引）
            String corpusHash = computeContentHash(files);
            RagIndexManifest manifest = RagIndexManifest.from(corpusHash, props);
            String indexVersion = manifest.indexVersion();
            if (indexVersion.equals(indexVersions.activeVersion())) {
                log.info("RAG 索引身份未变化（version={}），跳过重建", indexVersion.substring(0, 8));
                return;
            }
            indexVersions.register(manifest);
            String owner = "rag-builder-" + UUID.randomUUID().toString().substring(0, 12);
            if (!indexVersions.claimBuild(indexVersion, owner)) {
                log.info("RAG 索引 {} 正由其他实例构建，本实例保持使用当前 active version", indexVersion.substring(0, 8));
                return;
            }
            buildAndPublish(files, indexVersion, owner);
        } catch (Exception e) {
            log.error("RAG 文档导入失败，继续保留旧 active index", e);
            if (failFastOnEmptyIndex && indexVersions.activeVersion().isBlank()) {
                throw new IllegalStateException("生产环境没有可用的法规索引", e);
            }
        }
    }

    private void buildAndPublish(List<Path> files, String contentHash, String owner) {
        long started = System.nanoTime();
        RagBuildLeaseHeartbeat.Lease lease = leaseHeartbeat == null ? null : leaseHeartbeat.start(contentHash, owner);
        try {
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                segments.addAll(chunker.chunk(files.get(i), contentHash));
            }
            if (segments.isEmpty()) {
                throw new IllegalStateException("RAG 导入无有效文档片段");
            }
            // 先完成全部 embedding，再写候选版本；任何失败都不会触碰当前 active pointer。
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            if (embeddings.size() != segments.size()) {
                throw new IllegalStateException("Embedding 数量与文档片段不一致");
            }
            assertLease(contentHash, owner, lease);
            // 同版本上次崩溃留下的候选片段必须先清除，否则 Top-K 会被重复 evidenceId 占满。
            if (candidateIndexStore != null) candidateIndexStore.clearCandidate(contentHash);
            embeddingStore.addAll(embeddings, segments);
            var smoke = embeddingStore.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddings.getFirst()).maxResults(1)
                    .filter(metadataKey("corpusVersion").isEqualTo(contentHash)).build());
            if (smoke.matches().isEmpty()) {
                throw new IllegalStateException("候选法规索引 Smoke Test 未命中");
            }
            assertLease(contentHash, owner, lease);
            if (!indexVersions.activate(contentHash, owner, segments.size())) {
                throw new IllegalStateException("法规索引构建租约已失效，拒绝发布");
            }
            if (metrics != null) metrics.ragIndexBuild("SUCCEEDED", elapsedMs(started), segments.size());
            log.info("RAG 导入完成：{} 个文档片段向量化入库（表 {}，hash={}）",
                    segments.size(), props.getPg().getTable(), contentHash.substring(0, 8));
        } catch (Exception e) {
            indexVersions.release(contentHash, owner);
            if (metrics != null) metrics.ragIndexBuild("FAILED", elapsedMs(started), 0);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            if (lease != null) lease.close();
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

    /** 法规索引内容哈希（所有文档内容拼接后的 SHA-256），用于幂等导入与版本审计 */
    private String computeContentHash(List<Path> files) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (Path file : files) {
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<Path> loadDocFiles() throws IOException {
        Path dir = Path.of(props.getDataDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList();
        }
    }

}
