package com.bank.aml.assistant.agent;

import com.bank.aml.assistant.domain.AssistantCustomerView;
import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.domain.OwnershipRiskView;
import com.bank.aml.assistant.domain.RetrievalStatusView;
import com.bank.aml.assistant.domain.SanctionRiskView;
import com.bank.aml.assistant.domain.TransactionRiskView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerAssistantToolSuiteTest {

    @Test
    void currentCustomerToolsExposeNoCustomerSelectorAndOnlyFrozenFacts() {
        CustomerAssistantToolSuite tools = new CustomerAssistantToolSuite(snapshot(), new ObjectMapper());

        for (String method : List.of("getCurrentCustomerSummary", "getCurrentTransactionRiskProfile",
                "getCurrentOwnershipRiskSummary", "getCurrentSanctionRiskSummary")) {
            assertThat(java.util.Arrays.stream(CustomerAssistantToolSuite.class.getMethods())
                    .filter(item -> item.getName().equals(method)).findFirst().orElseThrow().getParameterCount())
                    .isZero();
        }
        assertThat(tools.getCurrentCustomerSummary())
                .contains("\"ok\":true", "CURRENT_CUSTOMER", "\"evidenceIds\":[\"CUSTOMER_PROFILE:abc\"]")
                .doesNotContain("C-007");
        assertThat(tools.getCurrentTransactionRiskProfile()).contains("transactionCount", "\"evidenceIds\"");
        assertThat(tools.traces()).hasSize(2).allMatch(item -> "SUCCESS".equals(item.status()));
    }

    @Test
    void rejectsEvidenceOutsideCurrentSnapshot() {
        CustomerAssistantToolSuite tools = new CustomerAssistantToolSuite(snapshot(), new ObjectMapper());

        assertThatThrownBy(() -> tools.getCurrentEvidence("CUSTOMER_PROFILE:another-customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前快照");
        assertThat(tools.traces()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("INVALID_ARGUMENT");
            assertThat(trace.evidenceIds()).isEmpty();
        });
    }

    @Test
    void convertsInvalidEvidenceFailureIntoRecoverableStructuredResponse() {
        CustomerAssistantToolSuite tools = new CustomerAssistantToolSuite(snapshot(), new ObjectMapper());

        String response;
        try {
            tools.getCurrentEvidence("CUSTOMER_PROFILE:invented");
            throw new AssertionError("expected invalid evidence");
        } catch (IllegalArgumentException error) {
            response = tools.recoverableError(error);
        }

        assertThat(response)
                .contains("\"ok\":false", "\"errorCode\":\"INVALID_ARGUMENT\"")
                .contains("不得猜测 evidenceId")
                .doesNotContain("invented");
    }

    @Test
    void searchesOnlyFrozenKnowledgeUsingPartialChineseTerms() {
        CustomerAssistantToolSuite tools = new CustomerAssistantToolSuite(snapshot(), new ObjectMapper());
        assertThat(tools.searchBankingKnowledge("请解释存款保险偿付"))
                .contains("KB-DEPOSIT-TEST-001").doesNotContain("CUSTOMER_PROFILE:abc");
    }

    private CustomerAssistantSnapshot snapshot() {
        AssistantEvidence evidence = new AssistantEvidence("CUSTOMER_PROFILE:abc",
                AssistantEvidence.EvidenceType.CUSTOMER_PROFILE, "客户画像", "个人客户", "TEST/v1");
        AssistantEvidence banking = new AssistantEvidence("KB-DEPOSIT-TEST-001",
                AssistantEvidence.EvidenceType.BANKING_PUBLIC, "存款保险", "存款保险实行限额偿付", "MOJ");
        return new CustomerAssistantSnapshot("snapshot-1", "conversation-1", "run-1", Instant.EPOCH,
                new AssistantCustomerView("CURRENT_CUSTOMER", "个人", "贸易", "上海", "-", "ENABLED"),
                new TransactionRiskView(2, new BigDecimal("100.00"), new BigDecimal("50.00"),
                        0, 0, 0, 0, List.of("CNY"), List.of("中国大陆"), true),
                new OwnershipRiskView(0, 0, List.of()), new SanctionRiskView(false, 0, 0, List.of()),
                List.of(evidence, banking), "TEST", "v1", "legal-v1", RetrievalStatusView.NONE, "digest");
    }
}
