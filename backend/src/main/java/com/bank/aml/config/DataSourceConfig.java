package com.bank.aml.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 主数据源（MySQL 业务库）。
 * <p>由于 RAG 另建了 PostgreSQL 的 pgDataSource，Spring Boot 的自动配置不再创建默认数据源，
 * 这里通过 {@link DataSourceProperties} 显式构建并标记 {@link Primary}，使 JPA 指向 MySQL 业务库；
 * pgDataSource 仅服务 RAG。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
