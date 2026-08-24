package com.bank.aml.tools;

import com.bank.aml.datasource.mock.MockDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionToolTest {

    private MockDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new MockDataSource();
        dataSource.init();
    }

    private String profile(String customerId) {
        return TransactionTool.format(dataSource.transactionsOf(customerId), customerId);
    }

    @Test
    void reportsTransactionCount() {
        String result = profile("C001");
        assertThat(result).contains("交易笔数：120 笔");
    }

    @Test
    void reportsNightAndCrossBorderRatios() {
        String result = profile("C001");
        assertThat(result).contains("夜间交易");
        assertThat(result).contains("跨境交易");
    }

    @Test
    void formatsTotalAmountInTenThousands() {
        String result = profile("C001");
        // 总额格式：约 X.XX 万元
        assertThat(result).containsPattern("约 \\d+\\.\\d{2} 万元");
    }

    @Test
    void unknownCustomerReturnsEmptyMessage() {
        String result = profile("C999");
        assertThat(result).isEqualTo("未查询到交易记录。").doesNotContain("C999");
    }

    @Test
    void c003NormalCustomerHasFewTransactions() {
        String result = profile("C003");
        assertThat(result).contains("交易笔数：15 笔");
    }
}
