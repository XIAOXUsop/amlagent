package com.bank.aml.tools;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.risk.RiskContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SnapshotToolSuiteTest {

    private static final CustomerProfile CUSTOMER = new CustomerProfile(
            "C001", "张伟", "110101198506123456", "企业法人", "国际贸易", "上海", "注册资本");
    private static final TransactionRecord TXN = new TransactionRecord(
            LocalDateTime.of(2026, 5, 1, 10, 0), new BigDecimal("100000"), "转出",
            "贸易客户A", CountryRegion.CHINA, "企业网银", "货款结算", "CNY");
    private static final ShareholdingRecord SHAREHOLDING = new ShareholdingRecord(
            "张伟", "自然人股东", new BigDecimal("0.65"), "L1");
    private static final SanctionRecord SANCTION = new SanctionRecord(
            "ZHANG WEI（张伟）", "110101198506123456", "OFAC SDN", "制裁", 1);

    private SnapshotToolSuite suite() {
        InvestigationSnapshot snapshot = new InvestigationSnapshot(
                "case-1-v1", 1L, 1, Instant.parse("2026-06-30T00:00:00Z"),
                CUSTOMER, List.of(TXN), List.of(SHAREHOLDING), List.of(SANCTION), List.of(),
                List.of("大额交易", "客户尽职调查"),
                mock(RiskContext.class), "v1", "digest");
        return new SnapshotToolSuite(snapshot);
    }

    @Test
    void transactionProfileRejectsWrongCustomerAndRecordsInvalidArgument() {
        SnapshotToolSuite tools = suite();

        assertThatThrownBy(() -> tools.transactionProfile("C999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");

        List<ToolExecutionTrace> traces = tools.traces();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).toolName()).isEqualTo("transactionProfile");
        assertThat(traces.get(0).argumentValid()).isFalse();
        assertThat(traces.get(0).success()).isFalse();
    }

    @Test
    void checkSanctionsRequiresBothNameAndIdCardMatch() {
        SnapshotToolSuite tools = suite();

        // 姓名正确但证件号错误：应拒绝（制裁场景要求两个可信字段都匹配）
        assertThatThrownBy(() -> tools.checkSanctions("张伟", "999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");

        // 姓名与证件号都正确：应返回命中结果
        String result = tools.checkSanctions("张伟", "110101198506123456");
        assertThat(result).contains("黑名单命中结果");
    }

    @Test
    void tracesPreservePartialFailures() {
        SnapshotToolSuite tools = suite();

        // 一个成功 + 一个参数失败，轨迹都应保留
        String ok = tools.transactionProfile("C001");
        assertThat(ok).contains("交易笔数：1 笔");
        assertThatThrownBy(() -> tools.corporateProfile("C999"))
                .isInstanceOf(IllegalArgumentException.class);

        List<ToolExecutionTrace> traces = tools.traces();
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).success()).isTrue();
        assertThat(traces.get(1).argumentValid()).isFalse();
        assertThat(traces.get(1).errorCode()).isEqualTo("ARGUMENT_VALIDATION_FAILED");
    }

    @Test
    void searchLegalRejectsQueryNotMatchingFrozenKeywords() {
        SnapshotToolSuite tools = suite();

        assertThatThrownBy(() -> tools.searchLegal("与工单无关的任意查询"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
        assertThat(tools.traces().get(0).argumentValid()).isFalse();
    }

    @Test
    void searchLegalAcceptsQueryContainingFrozenKeyword() {
        SnapshotToolSuite tools = suite();

        String result = tools.searchLegal("请检索大额交易相关的反洗钱监管法规");
        assertThat(result).contains("未检索到相关法规条文"); // 快照无法规证据时返回空态文本，但校验已通过
        assertThat(tools.traces().get(0).success()).isTrue();
    }
}
