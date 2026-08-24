package com.bank.aml.assistant.snapshot;

import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.guard.AssistantIntent;
import com.bank.aml.assistant.rag.AssistantKnowledgeProvider;
import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.rag.LegalIndexVersionProvider;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskFactAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerAssistantSnapshotFactoryTest {

    @Test
    void createsImmutablePseudonymizedSnapshotWithStableEvidence() {
        CustomerRepository customers = mock(CustomerRepository.class);
        CustomerDataPort data = mock(CustomerDataPort.class);
        RiskFactAssembler riskFacts = mock(RiskFactAssembler.class);
        LegalIndexVersionProvider index = () -> "legal-v9";
        CustomerEntity customer = customer(7L, "C-007", "张三", "110101199001011234");
        AssistantConversationEntity conversation = AssistantConversationEntity.create(
                "admin", customer, LocalDateTime.now().plusDays(1));
        List<TransactionRecord> transactions = new java.util.ArrayList<>(List.of(
                new TransactionRecord(LocalDateTime.of(2026, 8, 1, 23, 0), new BigDecimal("1500000"),
                        "转出", "敏感交易对手", CountryRegion.HK, "网银", "货款", "USD")));
        List<ShareholdingRecord> ownership = new java.util.ArrayList<>(List.of(
                new ShareholdingRecord("SECRET TRUST", "信托", new BigDecimal("0.30"), "L1")));
        List<SanctionRecord> sanctions = List.of(new SanctionRecord("张三", "110101199001011234", "TEST", "detail", 1));

        when(customers.findById(7L)).thenReturn(Optional.of(customer));
        when(data.transactionsOf("C-007")).thenReturn(transactions);
        when(data.shareholdingsOf("C-007")).thenReturn(ownership);
        when(data.sourceSystem()).thenReturn("TEST_SOURCE");
        when(data.sourceVersion()).thenReturn("v1");
        when(data.asOfTime()).thenReturn(Instant.parse("2026-08-23T12:00:00Z"));
        when(riskFacts.searchSanctions(any())).thenReturn(sanctions);
        when(riskFacts.assembleFrom(any(), any(), any(), any())).thenReturn(
                new RiskContext(1, true, 100, 100, 1, true, false, 2, 2, "低风险", 1));

        AssistantKnowledgeProvider knowledge = mock(AssistantKnowledgeProvider.class);
        when(knowledge.retrieve(any(), any(), any())).thenReturn(new AssistantKnowledgeProvider.KnowledgeBundle(
                "banking-v1+legal-v9", com.bank.aml.rag.RetrievalResponse.Status.SUPPORTED, List.of(
                new com.bank.aml.assistant.domain.AssistantEvidence("KB-KYC-TEST-001",
                        com.bank.aml.assistant.domain.AssistantEvidence.EvidenceType.AML_LEGAL,
                        "KYC", "尽职调查", "OFFICIAL"))));
        var factory = new CustomerAssistantSnapshotFactory(customers, data, riskFacts, index, knowledge);
        var snapshot = factory.create("run-1", conversation, "客户尽调", AssistantIntent.BANKING_KNOWLEDGE);
        transactions.clear();
        ownership.clear();

        assertThat(snapshot.customer().reference()).isEqualTo("CURRENT_CUSTOMER");
        assertThat(snapshot.transactionRisk().transactionCount()).isEqualTo(1);
        assertThat(snapshot.ownershipRisk().relations()).singleElement()
                .extracting(item -> item.holderMasked()).isEqualTo("S***");
        assertThat(snapshot.toString()).doesNotContain("110101199001011234", "张三", "敏感交易对手", "SECRET TRUST");
        assertThat(snapshot.evidence()).hasSize(5);
        assertThat(snapshot.evidence().subList(0, 4)).allMatch(item -> item.evidenceId().contains(":"));
        assertThat(snapshot.evidence().get(4).evidenceId()).isEqualTo("KB-KYC-TEST-001");
        assertThat(snapshot.sourceDigest()).hasSize(64);
    }

    private CustomerEntity customer(long id, String no, String name, String idCard) {
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(id);
        when(customer.getCustomerNo()).thenReturn(no);
        when(customer.getName()).thenReturn(name);
        when(customer.getIdCard()).thenReturn(idCard);
        when(customer.getType()).thenReturn("个人");
        when(customer.getIndustry()).thenReturn("贸易");
        when(customer.getRegion()).thenReturn("上海");
        when(customer.getRegCapital()).thenReturn("-");
        when(customer.getStatus()).thenReturn("ENABLED");
        return customer;
    }
}
