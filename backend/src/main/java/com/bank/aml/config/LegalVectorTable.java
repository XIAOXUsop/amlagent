package com.bank.aml.config;

/**
 * 可访问的法规向量表允许列表。SQL 标识符无法作为 JDBC 参数绑定，因此每个完整 SQL 片段都由编译期常量提供，配置值不得直接进入查询字符串。
 */
public enum LegalVectorTable {

    PRODUCTION("legal_docs", "DELETE FROM legal_docs WHERE metadata::jsonb ->> 'corpusVersion' = ?",
            "SELECT COUNT(*) FROM legal_docs WHERE metadata::jsonb ->> 'corpusVersion' = ?",
            "SELECT text, metadata::text, (", ") AS score FROM legal_docs WHERE ("),

    TEST("legal_docs_tes", "DELETE FROM legal_docs_tes WHERE metadata::jsonb ->> 'corpusVersion' = ?",
            "SELECT COUNT(*) FROM legal_docs_tes WHERE metadata::jsonb ->> 'corpusVersion' = ?",
            "SELECT text, metadata::text, (", ") AS score FROM legal_docs_tes WHERE (");

    private final String configuredName;

    private final String deleteByVersionSql;

    private final String countByVersionSql;

    private final String lexicalSelectPrefix;

    private final String lexicalFromClause;

    LegalVectorTable(String configuredName, String deleteByVersionSql, String countByVersionSql,
            String lexicalSelectPrefix, String lexicalFromClause) {
        this.configuredName = configuredName;
        this.deleteByVersionSql = deleteByVersionSql;
        this.countByVersionSql = countByVersionSql;
        this.lexicalSelectPrefix = lexicalSelectPrefix;
        this.lexicalFromClause = lexicalFromClause;
    }

    public static LegalVectorTable fromConfiguration(String value) {
        for (LegalVectorTable table : values()) {
            if (table.configuredName.equals(value)) {
                return table;
            }
        }
        throw new IllegalArgumentException("PGVector 表名不在允许列表中");
    }

    public String configuredName() {
        return configuredName;
    }

    public String deleteByVersionSql() {
        return deleteByVersionSql;
    }

    public String countByVersionSql() {
        return countByVersionSql;
    }

    public String lexicalSelectPrefix() {
        return lexicalSelectPrefix;
    }

    public String lexicalFromClause() {
        return lexicalFromClause;
    }

}
