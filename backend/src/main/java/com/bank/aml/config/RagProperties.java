package com.bank.aml.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 配置：PGVector 连接与文档导入路径。
 */
@ConfigurationProperties(prefix = "aml.rag")
@Validated
public class RagProperties {

    @Valid
    @NotNull
    private Pg pg = new Pg();

    /** 法规文档目录（启动时导入，支持 .md / .txt） */
    @NotBlank
    private String dataDir = "./data/legal";

    @NotBlank
    private String chunkerVersion = "legal-article-v2";

    @NotBlank
    private String metadataSchemaVersion = "legal-metadata-v2";

    @Valid
    @NotNull
    private Embedding embedding = new Embedding();

    @Min(1)
    private long cacheTtlMinutes = 60;

    @Min(0)
    private int retainedRetiredVersions = 2;

    @NotBlank
    private String legalIndexVersion = "v1";

    private boolean failFastOnEmptyIndex;

    @Valid
    @NotNull
    private Cache cache = new Cache();

    @Valid
    @NotNull
    private Retrieval retrieval = new Retrieval();

    @Valid
    @NotNull
    private Fusion fusion = new Fusion();

    @Valid
    @NotNull
    private PublicationGate publicationGate = new PublicationGate();

    @Valid
    @NotNull
    private Support support = new Support();

    @Valid
    @NotNull
    private Ingestion ingestion = new Ingestion();

    @Valid
    @NotNull
    private Rerank rerank = new Rerank();

    public Pg getPg() {
        return pg;
    }

    public void setPg(Pg pg) {
        this.pg = pg;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getChunkerVersion() {
        return chunkerVersion;
    }

    public void setChunkerVersion(String value) {
        this.chunkerVersion = value;
    }

    public String getMetadataSchemaVersion() {
        return metadataSchemaVersion;
    }

    public void setMetadataSchemaVersion(String value) {
        this.metadataSchemaVersion = value;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding value) {
        this.embedding = value;
    }

    public long getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    public void setCacheTtlMinutes(long cacheTtlMinutes) {
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    public int getRetainedRetiredVersions() {
        return retainedRetiredVersions;
    }

    public void setRetainedRetiredVersions(int retainedRetiredVersions) {
        this.retainedRetiredVersions = retainedRetiredVersions;
    }

    public String getLegalIndexVersion() {
        return legalIndexVersion;
    }

    public void setLegalIndexVersion(String legalIndexVersion) {
        this.legalIndexVersion = legalIndexVersion;
    }

    public boolean isFailFastOnEmptyIndex() {
        return failFastOnEmptyIndex;
    }

    public void setFailFastOnEmptyIndex(boolean failFastOnEmptyIndex) {
        this.failFastOnEmptyIndex = failFastOnEmptyIndex;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Fusion getFusion() {
        return fusion;
    }

    public void setFusion(Fusion fusion) {
        this.fusion = fusion;
    }

    public PublicationGate getPublicationGate() {
        return publicationGate;
    }

    public void setPublicationGate(PublicationGate publicationGate) {
        this.publicationGate = publicationGate;
    }

    public Support getSupport() {
        return support;
    }

    public void setSupport(Support support) {
        this.support = support;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public void setIngestion(Ingestion ingestion) {
        this.ingestion = ingestion;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    public static class Cache {

        @NotBlank
        private String embeddingModel = "all-minilm-l6-v2";

        @NotBlank
        private String rerankerVersion = "bge-reranker-base";

        @NotBlank
        private String pipelineVersion = "hybrid-rrf-v7";

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getRerankerVersion() {
            return rerankerVersion;
        }

        public void setRerankerVersion(String rerankerVersion) {
            this.rerankerVersion = rerankerVersion;
        }

        public String getPipelineVersion() {
            return pipelineVersion;
        }

        public void setPipelineVersion(String pipelineVersion) {
            this.pipelineVersion = pipelineVersion;
        }

    }

    public static class Retrieval {

        @Min(1)
        private int recallMultiplier = 4;

        @Min(1)
        private int maxPerDocument = 3;

        @Min(1)
        private int maxContextCharacters = 8_000;

        @Min(1)
        private int maxRecalledCandidates = 20;

        @Min(1)
        @Max(100_000)
        private int queryEmbeddingCacheCapacity = 512;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minimumMeaningfulSupport = 0.20;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double contextSimilarityThreshold = 0.88;

        @Min(1)
        private int investigationTopK = 3;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double investigationMinRelevance = 0.04;

        @Min(1)
        private int assistantTopK = 4;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double assistantMinRelevance = 0.08;

        @Min(1)
        @Max(100_000)
        private int assistantEvidenceSummaryMaxCharacters = 800;

        @Min(1)
        @Max(10_000)
        private int assistantEvidenceIdentifierMaxCharacters = 160;

        @AssertTrue(message = "max recalled candidates must cover each configured top-k")
        public boolean isRecallCapacityValid() {
            return maxRecalledCandidates >= investigationTopK && maxRecalledCandidates >= assistantTopK;
        }

        public int getRecallMultiplier() {
            return recallMultiplier;
        }

        public void setRecallMultiplier(int recallMultiplier) {
            this.recallMultiplier = recallMultiplier;
        }

        public int getMaxPerDocument() {
            return maxPerDocument;
        }

        public void setMaxPerDocument(int maxPerDocument) {
            this.maxPerDocument = maxPerDocument;
        }

        public int getMaxContextCharacters() {
            return maxContextCharacters;
        }

        public void setMaxContextCharacters(int maxContextCharacters) {
            this.maxContextCharacters = maxContextCharacters;
        }

        public int getMaxRecalledCandidates() {
            return maxRecalledCandidates;
        }

        public void setMaxRecalledCandidates(int maxRecalledCandidates) {
            this.maxRecalledCandidates = maxRecalledCandidates;
        }

        public int getQueryEmbeddingCacheCapacity() {
            return queryEmbeddingCacheCapacity;
        }

        public void setQueryEmbeddingCacheCapacity(int queryEmbeddingCacheCapacity) {
            this.queryEmbeddingCacheCapacity = queryEmbeddingCacheCapacity;
        }

        public double getMinimumMeaningfulSupport() {
            return minimumMeaningfulSupport;
        }

        public void setMinimumMeaningfulSupport(double minimumMeaningfulSupport) {
            this.minimumMeaningfulSupport = minimumMeaningfulSupport;
        }

        public double getContextSimilarityThreshold() {
            return contextSimilarityThreshold;
        }

        public void setContextSimilarityThreshold(double contextSimilarityThreshold) {
            this.contextSimilarityThreshold = contextSimilarityThreshold;
        }

        public int getInvestigationTopK() {
            return investigationTopK;
        }

        public void setInvestigationTopK(int investigationTopK) {
            this.investigationTopK = investigationTopK;
        }

        public double getInvestigationMinRelevance() {
            return investigationMinRelevance;
        }

        public void setInvestigationMinRelevance(double investigationMinRelevance) {
            this.investigationMinRelevance = investigationMinRelevance;
        }

        public int getAssistantTopK() {
            return assistantTopK;
        }

        public void setAssistantTopK(int assistantTopK) {
            this.assistantTopK = assistantTopK;
        }

        public double getAssistantMinRelevance() {
            return assistantMinRelevance;
        }

        public void setAssistantMinRelevance(double assistantMinRelevance) {
            this.assistantMinRelevance = assistantMinRelevance;
        }

        public int getAssistantEvidenceSummaryMaxCharacters() {
            return assistantEvidenceSummaryMaxCharacters;
        }

        public void setAssistantEvidenceSummaryMaxCharacters(int assistantEvidenceSummaryMaxCharacters) {
            this.assistantEvidenceSummaryMaxCharacters = assistantEvidenceSummaryMaxCharacters;
        }

        public int getAssistantEvidenceIdentifierMaxCharacters() {
            return assistantEvidenceIdentifierMaxCharacters;
        }

        public void setAssistantEvidenceIdentifierMaxCharacters(int assistantEvidenceIdentifierMaxCharacters) {
            this.assistantEvidenceIdentifierMaxCharacters = assistantEvidenceIdentifierMaxCharacters;
        }

    }

    public static class Fusion {

        @Min(1)
        private int rrfK = 60;

        @DecimalMin("0.0")
        private double vectorWeight = 1.0;

        @DecimalMin("0.0")
        private double keywordWeight = 1.2;

        @DecimalMin("0.0")
        @DecimalMax("0.25")
        private double lexicalScoreBonus = 0.08;

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getKeywordWeight() {
            return keywordWeight;
        }

        public void setKeywordWeight(double keywordWeight) {
            this.keywordWeight = keywordWeight;
        }

        public double getLexicalScoreBonus() {
            return lexicalScoreBonus;
        }

        public void setLexicalScoreBonus(double lexicalScoreBonus) {
            this.lexicalScoreBonus = lexicalScoreBonus;
        }

    }

    public static class PublicationGate {

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double minRecallAt5 = 90.0;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double minNdcgAt5 = 80.0;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double maxRecallDropVsActivePp = 2.0;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double minAbstentionAccuracy = 95.0;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double minNoAnswerRefusalRate = 95.0;

        @DecimalMin(value = "0.0", inclusive = false)
        private double maxColdP95Ms = 750.0;

        public double getMinRecallAt5() {
            return minRecallAt5;
        }

        public void setMinRecallAt5(double minRecallAt5) {
            this.minRecallAt5 = minRecallAt5;
        }

        public double getMinNdcgAt5() {
            return minNdcgAt5;
        }

        public void setMinNdcgAt5(double minNdcgAt5) {
            this.minNdcgAt5 = minNdcgAt5;
        }

        public double getMaxRecallDropVsActivePp() {
            return maxRecallDropVsActivePp;
        }

        public void setMaxRecallDropVsActivePp(double maxRecallDropVsActivePp) {
            this.maxRecallDropVsActivePp = maxRecallDropVsActivePp;
        }

        public double getMinAbstentionAccuracy() {
            return minAbstentionAccuracy;
        }

        public void setMinAbstentionAccuracy(double minAbstentionAccuracy) {
            this.minAbstentionAccuracy = minAbstentionAccuracy;
        }

        public double getMinNoAnswerRefusalRate() {
            return minNoAnswerRefusalRate;
        }

        public void setMinNoAnswerRefusalRate(double minNoAnswerRefusalRate) {
            this.minNoAnswerRefusalRate = minNoAnswerRefusalRate;
        }

        public double getMaxColdP95Ms() {
            return maxColdP95Ms;
        }

        public void setMaxColdP95Ms(double maxColdP95Ms) {
            this.maxColdP95Ms = maxColdP95Ms;
        }

    }

    public static class Support {

        @Pattern(regexp = "platt|isotonic")
        private String calibrationMethod = "platt";

        @NotNull
        private String calibrationDataPath = "";

        @Valid
        @NotNull
        private Thresholds thresholds = new Thresholds();

        public String getCalibrationMethod() {
            return calibrationMethod;
        }

        public void setCalibrationMethod(String calibrationMethod) {
            this.calibrationMethod = calibrationMethod;
        }

        public String getCalibrationDataPath() {
            return calibrationDataPath;
        }

        public void setCalibrationDataPath(String calibrationDataPath) {
            this.calibrationDataPath = calibrationDataPath;
        }

        public Thresholds getThresholds() {
            return thresholds;
        }

        public void setThresholds(Thresholds thresholds) {
            this.thresholds = thresholds;
        }

        public static class Thresholds {

            @DecimalMin("0.0")
            @DecimalMax("1.0")
            private double regulationFact = 0.70;

            @DecimalMin("0.0")
            @DecimalMax("1.0")
            private double highRiskDisposal = 0.85;

            @DecimalMin("0.0")
            @DecimalMax("1.0")
            private double generalKnowledge = 0.65;

            public double getRegulationFact() {
                return regulationFact;
            }

            public void setRegulationFact(double regulationFact) {
                this.regulationFact = regulationFact;
            }

            public double getHighRiskDisposal() {
                return highRiskDisposal;
            }

            public void setHighRiskDisposal(double highRiskDisposal) {
                this.highRiskDisposal = highRiskDisposal;
            }

            public double getGeneralKnowledge() {
                return generalKnowledge;
            }

            public void setGeneralKnowledge(double generalKnowledge) {
                this.generalKnowledge = generalKnowledge;
            }

        }

    }

    public static class Ingestion {

        @Min(1_024)
        private long maxDocumentBytes = 5_242_880;

        @Min(5)
        @Max(43_200)
        private long leaseHeartbeatSeconds = 60;

        @Min(1)
        @Max(1_440)
        private long buildLeaseMinutes = 15;

        @AssertTrue(message = "build lease must be at least twice the heartbeat interval")
        public boolean isBuildLeaseWindowValid() {
            return buildLeaseMinutes * 60 >= leaseHeartbeatSeconds * 2;
        }

        public long getMaxDocumentBytes() {
            return maxDocumentBytes;
        }

        public void setMaxDocumentBytes(long maxDocumentBytes) {
            this.maxDocumentBytes = maxDocumentBytes;
        }

        public long getLeaseHeartbeatSeconds() {
            return leaseHeartbeatSeconds;
        }

        public void setLeaseHeartbeatSeconds(long leaseHeartbeatSeconds) {
            this.leaseHeartbeatSeconds = leaseHeartbeatSeconds;
        }

        public long getBuildLeaseMinutes() {
            return buildLeaseMinutes;
        }

        public void setBuildLeaseMinutes(long buildLeaseMinutes) {
            this.buildLeaseMinutes = buildLeaseMinutes;
        }

    }

    public static class Rerank {

        private boolean enabled = true;

        @Min(1)
        private int recallSize = 20;

        @NotBlank
        private String modelDir = "";

        @NotBlank
        @Pattern(regexp = "https://.+", message = "rerank 制品地址必须使用 HTTPS")
        private String artifactBaseUrl = "";

        private boolean downloadEnabled;

        @Min(1)
        private int maxConcurrency = 2;

        @Min(1)
        private int queueCapacity = 8;

        @Min(1)
        private long inferenceTimeoutMs = 10_000;

        @Min(1)
        private long quotaTimeoutMs = 2_000;

        @Min(1)
        private long cooldownMs = 60_000;

        @Min(1)
        private int failureThreshold = 10;

        @Min(1)
        @Max(128)
        private int microBatchSize = 4;

        @Min(1)
        @Max(64)
        private int inferenceExecutorThreads = 1;

        @Pattern(regexp = "|[0-9a-fA-F]{64}")
        private String modelSha256 = "";

        @Pattern(regexp = "|[0-9a-fA-F]{64}")
        private String tokenizerSha256 = "";

        @Min(1)
        private long connectTimeoutMs = 30_000;

        @Min(1)
        private long downloadTimeoutMs = 300_000;

        @AssertTrue(message = "rerank inference executor threads must not exceed max concurrency")
        public boolean isInferenceConcurrencyValid() {
            return inferenceExecutorThreads <= maxConcurrency;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRecallSize() {
            return recallSize;
        }

        public void setRecallSize(int recallSize) {
            this.recallSize = recallSize;
        }

        public String getModelDir() {
            return modelDir;
        }

        public void setModelDir(String modelDir) {
            this.modelDir = modelDir;
        }

        public String getArtifactBaseUrl() {
            return artifactBaseUrl;
        }

        public void setArtifactBaseUrl(String artifactBaseUrl) {
            this.artifactBaseUrl = artifactBaseUrl;
        }

        public boolean isDownloadEnabled() {
            return downloadEnabled;
        }

        public void setDownloadEnabled(boolean downloadEnabled) {
            this.downloadEnabled = downloadEnabled;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getInferenceTimeoutMs() {
            return inferenceTimeoutMs;
        }

        public void setInferenceTimeoutMs(long inferenceTimeoutMs) {
            this.inferenceTimeoutMs = inferenceTimeoutMs;
        }

        public long getQuotaTimeoutMs() {
            return quotaTimeoutMs;
        }

        public void setQuotaTimeoutMs(long quotaTimeoutMs) {
            this.quotaTimeoutMs = quotaTimeoutMs;
        }

        public long getCooldownMs() {
            return cooldownMs;
        }

        public void setCooldownMs(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getMicroBatchSize() {
            return microBatchSize;
        }

        public void setMicroBatchSize(int microBatchSize) {
            this.microBatchSize = microBatchSize;
        }

        public int getInferenceExecutorThreads() {
            return inferenceExecutorThreads;
        }

        public void setInferenceExecutorThreads(int inferenceExecutorThreads) {
            this.inferenceExecutorThreads = inferenceExecutorThreads;
        }

        public String getModelSha256() {
            return modelSha256;
        }

        public void setModelSha256(String modelSha256) {
            this.modelSha256 = modelSha256;
        }

        public String getTokenizerSha256() {
            return tokenizerSha256;
        }

        public void setTokenizerSha256(String tokenizerSha256) {
            this.tokenizerSha256 = tokenizerSha256;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getDownloadTimeoutMs() {
            return downloadTimeoutMs;
        }

        public void setDownloadTimeoutMs(long downloadTimeoutMs) {
            this.downloadTimeoutMs = downloadTimeoutMs;
        }

    }

    public static class Embedding {

        @NotBlank
        private String provider = "langchain4j-onnx";

        @NotBlank
        private String model = "all-MiniLM-L6-v2";

        @NotBlank
        private String revision = "1.18.1-beta28";

        @NotBlank
        private String modelHash = "bundled-artifact";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String v) {
            provider = v;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            model = v;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String v) {
            revision = v;
        }

        public String getModelHash() {
            return modelHash;
        }

        public void setModelHash(String v) {
            modelHash = v;
        }

    }

    public static class Pg {

        @Pattern(regexp = "jdbc:postgresql://.+", message = "PGVector URL 必须是 PostgreSQL JDBC URL")
        private String url;

        private String username;

        private String password;

        @NotBlank
        private String table = "legal_docs";

        @Min(1)
        @Max(16_384)
        private int dimensions = 384;

        @Pattern(regexp = "cosine|euclidean|dot_product")
        private String distanceMetric = "cosine";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public String getDistanceMetric() {
            return distanceMetric;
        }

        public void setDistanceMetric(String value) {
            this.distanceMetric = value;
        }

    }

}
