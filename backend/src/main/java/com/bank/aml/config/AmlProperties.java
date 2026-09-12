package com.bank.aml.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * AML 平台级配置。与检索、模型和队列等独立子系统不同，这里集中低数量的运行策略、数据源标识和安全参数。
 */
@ConfigurationProperties(prefix = "aml")
@Validated
public record AmlProperties(@Valid @NotNull @DefaultValue Agent agent,
        @Valid @NotNull @DefaultValue CostRouting costRouting, @Valid @NotNull @DefaultValue Data data,
        @Valid @NotNull @DefaultValue Demo demo, @Valid @NotNull @DefaultValue Evaluation eval,
        @Valid @NotNull @DefaultValue Investigation investigation, @Valid @NotNull @DefaultValue Review review,
        @Valid @NotNull @DefaultValue Security security) {

    private static final String DEFAULT_FIELD_ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";

    public record Agent(@Min(1) @Max(1_000) @DefaultValue("8") int maxLinkedAlerts,
            @Min(1) @Max(10) @DefaultValue("3") int maxToolRoundTrips) {
    }

    public record CostRouting(@DefaultValue("false") boolean ruleFallbackEnabled,
            @DefaultValue("false") boolean summaryEnabled) {
    }

    public record Data(@NotBlank @Size(max = 128) @DefaultValue("bank-sync-v1") String sourceVersion,
            @Min(1) @Max(104_857_600) @DefaultValue("5242880") long customerImportMaxBytes,
            @Min(1) @Max(105_906_176) @DefaultValue("6291456") long customerImportMaxRequestBytes,
            @Min(1) @Max(100_000) @DefaultValue("1000") int customerImportMaxRows,
            @Min(1) @Max(10_000) @DefaultValue("50") int sanctionRecallLimit) {

        @AssertTrue(message = "customer import request limit must cover the file limit")
        public boolean isCustomerImportRequestCapacityValid() {
            return customerImportMaxRequestBytes >= customerImportMaxBytes;
        }
    }

    public record Demo(@DefaultValue("true") boolean seedCustomers) {
    }

    public record Evaluation(@Valid @NotNull @DefaultValue HiddenTest hiddenTest,
            @Min(1) @Max(600_000) @DefaultValue("10000") long p95LatencyBudgetMs,
            @Min(1) @Max(1_000_000) @DefaultValue("6000") long averageTokensPerCaseBudget) {

        public record HiddenTest(@NotNull @Size(max = 4_096) @DefaultValue("") String path) {
        }

    }

    public record Investigation(@Min(0) @Max(1_440) @DefaultValue("5") long futureTimestampToleranceMinutes) {
    }

    public record Review(@Min(1) @Max(365) @DefaultValue("14") int eddDefaultDueDays,
            @Min(1) @Max(365) @DefaultValue("90") int eddMaximumDueDays) {
    }

    public record Security(
            @NotBlank @Size(max = 512) @DefaultValue(DEFAULT_FIELD_ENCRYPTION_KEY) String fieldEncryptionKey,
            @NotBlank @Size(
                    max = 512) @DefaultValue("aml-agent-jwt-secret-2026-change-me-0123456789abcdef") String jwtSecret,
            @Min(1) @Max(8_760) @DefaultValue("24") long jwtValidityHours,
            @DefaultValue("false") boolean cookieSecure) {
    }

}
