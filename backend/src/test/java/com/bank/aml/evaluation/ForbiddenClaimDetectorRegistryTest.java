package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForbiddenClaimDetectorRegistryTest {

    private ForbiddenClaimDetectorRegistry registry;
    private AgentEvalDataset dataset;

    @BeforeEach
    void setUp() {
        registry = new ForbiddenClaimDetectorRegistry();
        dataset = new AgentEvalDatasetLoader(new ObjectMapper()).load();
    }

    @Test
    void catchesFabricatedSanctionHitOnNormalCustomer() {
        var evalCase = caseById("AML-AE-001");
        var report = report(evalCase, "低风险", false,
                List.of("NORMAL_TRANSACTION_PATTERN", "SANCTION_LEVEL_1_MATCH"),
                List.of("MAINTAIN_STANDARD_MONITORING"));

        assertThat(registry.evaluate(evalCase, report))
                .anySatisfy(check -> {
                    assertThat(check.claimCode()).isEqualTo("FABRICATED_SANCTION_HIT");
                    assertThat(check.status()).isEqualTo("VIOLATION");
                });
    }

    @Test
    void unsupportedFreeTextClaimIsExplicitlyUnscorable() {
        var evalCase = caseById("AML-AE-008");
        var report = report(evalCase, "中风险", true,
                evalCase.expected().requiredFindingCodes(), evalCase.expected().requiredActions());

        assertThat(registry.evaluate(evalCase, report).stream()
                .filter(check -> "FABRICATED_TRANSACTION_STATISTICS".equals(check.claimCode()))
                .findFirst().orElseThrow().status()).isEqualTo("UNSCORABLE");
    }

    private AgentEvalDataset.AgentEvalCase caseById(String id) {
        return dataset.cases().stream().filter(c -> id.equals(c.id())).findFirst().orElseThrow();
    }

    private DueDiligenceReport report(AgentEvalDataset.AgentEvalCase evalCase, String risk,
                                      boolean manual, List<String> findings, List<String> actions) {
        return new DueDiligenceReport(
                evalCase.input().customerId(), evalCase.input().customerName(), risk,
                "tx", "corp", new ArrayList<>(), List.of("law"), List.of("risk"),
                "conclusion", List.of("evidence"), manual, findings, actions
        );
    }
}
