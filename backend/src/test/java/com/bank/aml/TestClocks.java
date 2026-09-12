package com.bank.aml;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class TestClocks {

    public static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);

    private TestClocks() {
    }

}
