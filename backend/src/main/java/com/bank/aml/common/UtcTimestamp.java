package com.bank.aml.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Converts legacy UTC database timestamps into unambiguous transport timestamps. */
public final class UtcTimestamp {

    private UtcTimestamp() {
    }

    public static Instant from(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
