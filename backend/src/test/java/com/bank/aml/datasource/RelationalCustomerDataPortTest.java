package com.bank.aml.datasource;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.datasource.entity.CustomerTransactionEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.datasource.repository.CustomerShareholdingRepository;
import com.bank.aml.datasource.repository.CustomerTransactionRepository;
import com.bank.aml.datasource.repository.SanctionEntryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationalCustomerDataPortTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerTransactionRepository transactions = mock(CustomerTransactionRepository.class);
    private final CustomerShareholdingRepository shareholdings = mock(CustomerShareholdingRepository.class);
    private final SanctionEntryRepository sanctions = mock(SanctionEntryRepository.class);
    private final RelationalCustomerDataPort port = new RelationalCustomerDataPort(
            customers, transactions, shareholdings, sanctions, "sync-2026-08");

    @Test
    void missingUpstreamFactsStayEmptyInsteadOfBeingSynthesized() {
        when(transactions.findByCustomerNoOrderByTransactedAtAsc("C900")).thenReturn(List.of());
        when(shareholdings.findByCustomerNoOrderByIdAsc("C900")).thenReturn(List.of());

        assertThat(port.transactionsOf("C900")).isEmpty();
        assertThat(port.shareholdingsOf("C900")).isEmpty();
        assertThat(port.sourceSystem()).isEqualTo("BANK_RELATIONAL_SYNC");
        assertThat(port.sourceVersion()).isEqualTo("sync-2026-08");
    }

    @Test
    void persistedTransactionIsMappedWithoutChangingFinancialValues() {
        CustomerTransactionEntity entity = mock(CustomerTransactionEntity.class);
        LocalDateTime at = LocalDateTime.of(2026, 8, 19, 9, 30);
        when(entity.getTransactedAt()).thenReturn(at);
        when(entity.getAmount()).thenReturn(new BigDecimal("123456.78"));
        when(entity.getDirection()).thenReturn("转出");
        when(entity.getCounterparty()).thenReturn("真实交易对手");
        when(entity.getCounterpartyRegion()).thenReturn("HK");
        when(entity.getChannel()).thenReturn("企业网银");
        when(entity.getPurpose()).thenReturn("货款");
        when(entity.getCurrency()).thenReturn("CNY");
        when(transactions.findByCustomerNoOrderByTransactedAtAsc("C901")).thenReturn(List.of(entity));

        var result = port.transactionsOf("C901");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().date()).isEqualTo(at);
        assertThat(result.getFirst().amount()).isEqualByComparingTo("123456.78");
        assertThat(result.getFirst().country()).isEqualTo(CountryRegion.HK);
    }
}
