package com.bank.aml.investigation;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.common.time.BusinessZone;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.TransactionRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionWindowServiceTest {

    @Test
    void buildsCustomerWindowsAtSourceAsOfAndExcludesFutureTransactions() {
        CaseRepository cases = mock(CaseRepository.class);
        CustomerDataPort data = mock(CustomerDataPort.class);
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId("C001");
        Instant asOf = Instant.parse("2026-08-20T04:00:00Z");
        LocalDateTime localAsOf = LocalDateTime.ofInstant(asOf, BusinessZone.ZONE);
        when(cases.findById(7L)).thenReturn(Optional.of(caseEntity));
        when(data.asOfTime()).thenReturn(asOf);
        when(data.sourceSystem()).thenReturn("CORE_BANKING");
        when(data.sourceVersion()).thenReturn("2026-08-20");
        when(data.transactionsOf("C001")).thenReturn(List.of(
                tx(localAsOf.minusDays(10).withHour(23), "100.00", "入账", "A", CountryRegion.CHINA),
                tx(localAsOf.minusDays(20), "40.00", "OUT", "B", CountryRegion.US),
                tx(localAsOf.minusDays(70), "60.00", "IN", "A", CountryRegion.CHINA),
                tx(localAsOf.minusDays(120), "80.00", "OUT", "C", CountryRegion.HK),
                tx(localAsOf.plusMinutes(1), "999.00", "IN", "FUTURE", CountryRegion.US)));

        TransactionWindowView result = new TransactionWindowService(cases, data).windows(7L);

        assertThat(result.asOfTime()).isEqualTo(asOf);
        assertThat(result.windows()).extracting(TransactionWindowView.Window::transactionCount)
                .containsExactly(2L, 3L, 4L);
        assertThat(result.windows().getFirst().currencyBreakdown())
                .extracting(TransactionWindowView.CurrencySummary::currency)
                .containsExactly("CNY", "USD");
        assertThat(result.windows().getFirst().currencyBreakdown().getFirst().totalAmount())
                .isEqualByComparingTo("100.00");
        assertThat(result.windows().getFirst().currencyBreakdown().getFirst().incomingAmount())
                .isEqualByComparingTo("100.00");
        assertThat(result.windows().getFirst().currencyBreakdown().getLast().outgoingAmount())
                .isEqualByComparingTo("40.00");
        assertThat(result.windows().getFirst().crossBorderCount()).isEqualTo(1);
        assertThat(result.windows().getFirst().nightCount()).isEqualTo(1);
        assertThat(result.windows().getLast().topCounterparties().getFirst().counterparty()).isEqualTo("A");
    }

    private TransactionRecord tx(LocalDateTime date, String amount, String direction,
                                 String counterparty, CountryRegion country) {
        return new TransactionRecord(date, new BigDecimal(amount), direction, counterparty, country,
                "网银", "转账", country.isCrossBorder() ? "USD" : "CNY");
    }
}
