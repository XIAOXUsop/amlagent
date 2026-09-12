package com.bank.aml.testinfra;

import java.util.regex.Pattern;

/** 集成测试动态数据库对象名的唯一校验与引用入口。 */
public final class TestSqlIdentifier {

    private static final Pattern MYSQL_SCHEMA = Pattern.compile("aml_[a-z0-9_]+_test");

    private static final Pattern POSTGRES_LEGAL_TABLE = Pattern.compile("legal_docs_kw_it_[a-f0-9]{32}");

    private TestSqlIdentifier() {
    }

    /**
     * JDBC 占位符不能绑定数据库标识符，因此仅允许测试专用命名空间，并使用 MySQL 标识符引用。
     */
    public static String mysqlSchema(String schema) {
        if (schema == null || !MYSQL_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("集成测试 schema 名称不安全：" + schema);
        }
        return "`" + schema + "`";
    }

    /**
     * JDBC 占位符不能绑定数据库标识符，因此仅允许进程内生成的测试表名，并使用 PostgreSQL 标识符引用。
     */
    public static String postgresLegalTable(String table) {
        if (table == null || !POSTGRES_LEGAL_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("集成测试表名称不安全：" + table);
        }
        return "\"" + table + "\"";
    }

}
