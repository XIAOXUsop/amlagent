package com.bank.aml.assistant.domain;

import java.math.BigDecimal;
import java.util.List;

public record OwnershipRiskView(int relationCount, int uboRiskSeverity, List<OwnershipRelationView> relations) {
    public OwnershipRiskView { relations = relations == null ? List.of() : List.copyOf(relations); }

    public record OwnershipRelationView(String holderMasked, String holderType, BigDecimal ratio, String level) {}
}
