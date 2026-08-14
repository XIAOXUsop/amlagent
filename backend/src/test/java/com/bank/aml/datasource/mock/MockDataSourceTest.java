package com.bank.aml.datasource.mock;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.TransactionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockDataSourceTest {

    private MockDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new MockDataSource();
        dataSource.init();
    }

    @Test
    void allTransactionHoursAreLegal() {
        for (CustomerProfile customer : dataSource.allCustomers()) {
            for (TransactionRecord t : dataSource.transactionsOf(customer.id())) {
                assertThat(t.date().getHour()).isBetween(0, 23);
            }
        }
    }

    @Test
    void amountsAreStableAndScaled() {
        for (CustomerProfile customer : dataSource.allCustomers()) {
            for (TransactionRecord t : dataSource.transactionsOf(customer.id())) {
                assertThat(t.amount()).isNotNull();
                assertThat(t.amount().scale()).isEqualTo(2);
            }
        }
    }

    @Test
    void countriesUseEnum() {
        List<TransactionRecord> txns = dataSource.transactionsOf("C001");
        assertThat(txns).isNotEmpty();
        assertThat(txns).allMatch(t -> t.country() != null);
        assertThat(txns).anyMatch(t -> t.country().isCrossBorder());
    }

    @Test
    void deterministicDataWithFixedSeed() {
        MockDataSource another = new MockDataSource();
        another.init();
        List<TransactionRecord> a = dataSource.transactionsOf("C001");
        List<TransactionRecord> b = another.transactionsOf("C001");
        assertThat(a).hasSameSizeAs(b);
        assertThat(a.get(0).amount()).isEqualByComparingTo(b.get(0).amount());
        assertThat(a.get(0).date()).isEqualTo(b.get(0).date());
    }

    @Test
    void sanctionsMatchChineseAndEnglishNames() {
        assertThat(dataSource.searchSanctions("张伟")).isNotEmpty();
        assertThat(dataSource.searchSanctions("ZHANG WEI")).isNotEmpty();
        assertThat(dataSource.searchSanctions("李娜")).isEmpty();
    }

    @Test
    void countryRegionIsCrossBorder() {
        assertThat(CountryRegion.HK.isCrossBorder()).isTrue();
        assertThat(CountryRegion.CHINA.isCrossBorder()).isFalse();
    }
}
