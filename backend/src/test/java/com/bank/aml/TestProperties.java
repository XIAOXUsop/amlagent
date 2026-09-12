package com.bank.aml;

import com.bank.aml.config.AmlProperties;

public final class TestProperties {

    private static final String FIELD_ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";

    private static final String JWT_SECRET = "aml-agent-jwt-secret-2026-change-me-0123456789abcdef";

    private TestProperties() {
    }

    public static AmlProperties aml() {
        return aml("bank-sync-v1");
    }

    public static AmlProperties aml(String sourceVersion) {
        return new AmlProperties(new AmlProperties.Agent(8, 3), new AmlProperties.CostRouting(false, false),
                new AmlProperties.Data(sourceVersion, 5_242_880, 6_291_456, 1_000, 50), new AmlProperties.Demo(true),
                new AmlProperties.Evaluation(new AmlProperties.Evaluation.HiddenTest(""), 10_000, 6_000),
                new AmlProperties.Investigation(5), new AmlProperties.Review(14, 90),
                new AmlProperties.Security(FIELD_ENCRYPTION_KEY, JWT_SECRET, 24, false));
    }

}
