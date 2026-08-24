package com.bank.aml.assistant.agent;

import com.bank.aml.assistant.domain.AssistantCustomerView;
import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.domain.OwnershipRiskView;
import com.bank.aml.assistant.domain.SanctionRiskView;
import com.bank.aml.assistant.domain.TransactionRiskView;
import com.bank.aml.assistant.guard.AssistantIntent;
import com.bank.aml.assistant.guard.AssistantOutputGuard;
import com.bank.aml.assistant.guard.SensitiveDataDetector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantToolBudgetFallbackTest {

    @Test
    void createsReadOnlySnapshotBoundAnswerThatPassesOutputGuard() {
        String evidenceId = "TRANSACTION_AGGREGATE:71b39e0804a17ef7cdc8508545810921059266fbc43fdb4446d90f10c235595b";
        CustomerAssistantSnapshot snapshot = new CustomerAssistantSnapshot("s", "c", "r", Instant.EPOCH,
                new AssistantCustomerView("CURRENT_CUSTOMER", "企业", "贸易", "上海", "5000万", "ENABLED"),
                new TransactionRiskView(120, new BigDecimal("497000000"), new BigDecimal("4141666.67"),
                        50.83, 32.5, 105, 2, List.of("CNY", "USD"), List.of("中国大陆"), true),
                new OwnershipRiskView(1, 1, List.of()), new SanctionRiskView(false, 0, 0, List.of()),
                List.of(new AssistantEvidence(evidenceId, AssistantEvidence.EvidenceType.TRANSACTION_AGGREGATE,
                        "交易聚合", "交易共120笔", "TEST/v1")), "TEST", "v1", "legal-v1",
                com.bank.aml.assistant.domain.RetrievalStatusView.NONE, "digest");

        String answer = AssistantEvidenceCitationAppender.appendMissing(
                AssistantToolBudgetFallback.create(snapshot, AssistantIntent.CUSTOMER_ANALYSIS),
                List.of(new AssistantToolTrace(1, "transaction", "SUCCESS", 1, "digest",
                        List.of(evidenceId), null)));

        assertThat(answer).contains("交易笔数：120笔", "只读", evidenceId).doesNotContain("已修改", "已提交");
        assertThat(new AssistantOutputGuard(new SensitiveDataDetector()).validate(snapshot, answer).violations())
                .isEmpty();
    }
}
