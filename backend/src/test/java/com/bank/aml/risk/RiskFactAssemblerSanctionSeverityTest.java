package com.bank.aml.risk;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.SanctionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskFactAssemblerSanctionSeverityTest {

    @Test
    void mixedSanctionHitsPreserveLevelOneAsMostSevere() {
        RiskFactAssembler assembler = new RiskFactAssembler(mock(CustomerDataPort.class));
        List<SanctionRecord> hits = List.of(
                new SanctionRecord("A", "ID-A", "DOMESTIC", "二级名单", 2),
                new SanctionRecord("A", "ID-A", "OFAC SDN", "一级制裁", 1));

        RiskContext facts = assembler.assembleFrom(List.of(), List.of(), hits, "低风险");

        assertThat(facts.maxSeverity()).isEqualTo(1);
        assertThat(facts.sanctionHit()).isTrue();
    }

    @Test
    void invalidNonPositiveSeverityDoesNotMasqueradeAsLevelOne() {
        RiskFactAssembler assembler = new RiskFactAssembler(mock(CustomerDataPort.class));
        List<SanctionRecord> hits = List.of(
                new SanctionRecord("A", "ID-A", "INVALID", "错误等级", 0));

        RiskContext facts = assembler.assembleFrom(List.of(), List.of(), hits, "低风险");

        assertThat(facts.maxSeverity()).isZero();
        assertThat(facts.sanctionHit()).isTrue();
    }
}
