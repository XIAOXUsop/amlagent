package com.bank.aml.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 数据库部署策略配置，仅承载生产启动安全校验所需的 Spring 数据源、Flyway 与 Hibernate 参数。 */
@ConfigurationProperties(prefix = "spring")
@Validated
public record DatabaseDeploymentProperties(@Valid @NotNull @DefaultValue Datasource datasource,
        @Valid @NotNull @DefaultValue Flyway flyway, @Valid @NotNull @DefaultValue Jpa jpa) {

    public record Datasource(@NotNull @Size(max = 512) @DefaultValue("") String password) {
    }

    public record Flyway(@DefaultValue("false") boolean enabled) {
    }

    public record Jpa(@Valid @NotNull @DefaultValue Hibernate hibernate) {

        public record Hibernate(@NotNull @Size(max = 32) @DefaultValue("") String ddlAuto) {
        }

    }

}
