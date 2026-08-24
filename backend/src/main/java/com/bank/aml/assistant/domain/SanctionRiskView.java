package com.bank.aml.assistant.domain;

import java.util.List;

public record SanctionRiskView(boolean hit, int hitCount, int maxSeverity, List<String> listTypes) {
    public SanctionRiskView { listTypes = listTypes == null ? List.of() : List.copyOf(listTypes); }
}
