package com.bank.aml.testinfra;

import java.util.regex.Pattern;

/** 集成测试动态数据库对象名的唯一校验与引用入口。 */
public final class TestSqlIdentifier {

    private static final Pattern MYSQL_SCHEMA = Pattern.compile("aml_[a-z0-9_]+_test");

    private static final Pattern POSTGRES_LEGAL_TABLE = Pattern.compile("legal_docs_kw_it_[a-f0-9]{32}");

    private static final Pattern POSTGRES_SCHEMA = Pattern.compile("legal_kw_it_[a-f0-9]{32}");

    /** 法规向量表只能取允许列表里的名字——服务端不再接受调用方自选的表名 */
    private static final Pattern POSTGRES_ALLOWLISTED_LEGAL_TABLE = Pattern.compile("legal_docs(_tes)?");

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
     * 集成测试专用的 PostgreSQL schema。
     *
     * <p>
     * 后来法规向量表名改成了**编译期常量允许列表**（SQL 标识符无法参数绑定， 配置值不得进入查询字符串），于是"用随机表名做隔离"这条路走不通了。 隔离下沉到
     * schema：表名用允许列表里的常量，schema 仍然是进程内生成的测试专用名。
     */
    public static String postgresSchema(String schema) {
        if (schema == null || !POSTGRES_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("集成测试 schema 名称不安全：" + schema);
        }
        return "\"" + schema + "\"";
    }

    /**
     * 允许列表内的法规向量表名，并加 PostgreSQL 标识符引用。
     *
     * <p>
     * 与 {@link #postgresLegalTable} 的分工：那个适用于"测试自己造一张随机表"， 这个适用于"表名必须是编译期常量、隔离改由 schema
     * 提供"的场景。 两者都不允许把任意字符串当标识符拼进 SQL。
     */
    public static String postgresAllowlistedLegalTable(String table) {
        if (table == null || !POSTGRES_ALLOWLISTED_LEGAL_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("表名不在法规向量表允许列表中：" + table);
        }
        return "\"" + table + "\"";
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
