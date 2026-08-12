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

    public static class Pg {
        private String url;
        private String username;
        private String password;
        private String table = "legal_docs";
        private int dimensions = 384;

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
    }
}
