package com.bank.aml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置：PGVector 连接与文档导入路径。
 */
@ConfigurationProperties(prefix = "aml.rag")
public class RagProperties {

    private Pg pg = new Pg();

    /** 法规文档目录（启动时导入，支持 .md / .txt） */
    private String dataDir = "./data/legal";
    private String chunkerVersion = "legal-article-v2";
    private String metadataSchemaVersion = "legal-metadata-v2";
    private Embedding embedding = new Embedding();

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

    public String getChunkerVersion() { return chunkerVersion; }
    public void setChunkerVersion(String value) { this.chunkerVersion = value; }
    public String getMetadataSchemaVersion() { return metadataSchemaVersion; }
    public void setMetadataSchemaVersion(String value) { this.metadataSchemaVersion = value; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding value) { this.embedding = value; }

    public static class Embedding {
        private String provider = "langchain4j-onnx";
        private String model = "all-MiniLM-L6-v2";
        private String revision = "1.18.1-beta28";
        private String modelHash = "bundled-artifact";
        public String getProvider() { return provider; }
        public void setProvider(String v) { provider = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public String getRevision() { return revision; }
        public void setRevision(String v) { revision = v; }
        public String getModelHash() { return modelHash; }
        public void setModelHash(String v) { modelHash = v; }
    }

    public static class Pg {
        private String url;
        private String username;
        private String password;
        private String table = "legal_docs";
        private int dimensions = 384;
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
        public String getDistanceMetric() { return distanceMetric; }
        public void setDistanceMetric(String value) { this.distanceMetric = value; }
    }
}
