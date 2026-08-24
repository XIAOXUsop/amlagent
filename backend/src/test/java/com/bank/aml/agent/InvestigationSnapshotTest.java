package com.bank.aml.agent;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.RetrievalResponse;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.service.LegalKeywordResolver;
import com.bank.aml.tools.SnapshotToolSuite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * 验证 Snapshot First：快照在 Agent 推理前冻结，工具只读快照，数据源变化不影响当前执行。
 */
class InvestigationSnapshotTest {

    private static final CustomerProfile CUSTOMER = new CustomerProfile(
            "C001", "张伟", "110101198506123456", "企业法人", "国际贸易", "上海", "注册资本5000万");
    private static final TransactionRecord TXN_A = new TransactionRecord(
            LocalDateTime.of(2026, 5, 1, 10, 0), new BigDecimal("100000"), "转出",
            "贸易客户A", CountryRegion.CHINA, "企业网银", "货款结算", "CNY");
    private static final TransactionRecord TXN_B = new TransactionRecord(
            LocalDateTime.of(2026, 5, 2, 23, 0), new BigDecimal("99999999"), "转出",
            "境外买方D(伊朗)", CountryRegion.IRAN, "跨境支付", "货款结算", "USD");
    private static final ShareholdingRecord SHAREHOLDING = new ShareholdingRecord(
            "张伟", "自然人股东", new BigDecimal("0.65"), "L1");
    private static final SanctionRecord SANCTION = new SanctionRecord(
            "ZHANG WEI（张伟）", "110101198506123456", "OFAC SDN", "制裁名单同名", 1);

    @Test
    void snapshotFreezesFactsWhenDataSourceChanges() {
        CustomerDataPort dataSource = stubDataSource();
        InvestigationSnapshotFactory factory = factory(dataSource);

        InvestigationSnapshot snapshot = factory.create(1L, 1, CUSTOMER, "低风险", "常规监测");

        // 快照创建后，数据源发生变化
        when(dataSource.transactionsOf("C001")).thenReturn(List.of(TXN_B));

        SnapshotToolSuite tools = new SnapshotToolSuite(snapshot);
        String result = tools.transactionProfile("C001");

        // 工具仍返回快照旧值（1 笔，而非变化后的 1 笔大额）
        assertThat(result).contains("交易笔数：1 笔");
        assertThat(result).doesNotContain("99999999");
        // 交易工具不再二次访问数据源：transactionsOf 仅在快照创建时被调用一次
        verify(dataSource, times(1)).transactionsOf("C001");
    }

    @Test
    void snapshotToolsDoNotAccessDataSourceAfterFreeze() {
        CustomerDataPort dataSource = stubDataSource();
        InvestigationSnapshotFactory factory = factory(dataSource);
        InvestigationSnapshot snapshot = factory.create(1L, 1, CUSTOMER, "低风险", "常规监测");

        SnapshotToolSuite tools = new SnapshotToolSuite(snapshot);
        tools.corporateProfile("C001");
        tools.checkSanctions("C001");

        // 股权/制裁工具均从快照读，不再访问数据源
        verify(dataSource, times(1)).shareholdingsOf("C001");
        verify(dataSource, times(1)).searchSanctions("张伟");
    }

    @Test
    void sameFactsProduceStableDigest() {
        CustomerDataPort dataSource = stubDataSource();
        InvestigationSnapshotFactory factory = factory(dataSource);

        InvestigationSnapshot a = factory.create(1L, 1, CUSTOMER, "低风险", "常规监测");
        InvestigationSnapshot b = factory.create(1L, 1, CUSTOMER, "低风险", "常规监测");

        assertThat(a.sourceDigest()).isEqualTo(b.sourceDigest());
        assertThat(a.riskFacts()).isEqualTo(b.riskFacts());
    }

    @Test
    void differentExecutionVersionProducesDifferentSnapshotId() {
        CustomerDataPort dataSource = stubDataSource();
        InvestigationSnapshotFactory factory = factory(dataSource);

        InvestigationSnapshot v1 = factory.create(1L, 1, CUSTOMER, "低风险", "常规监测");
        InvestigationSnapshot v2 = factory.create(1L, 2, CUSTOMER, "低风险", "常规监测");

        assertThat(v1.snapshotId()).isNotEqualTo(v2.snapshotId());
        assertThat(v1.executionVersion()).isEqualTo(1);
        assertThat(v2.executionVersion()).isEqualTo(2);
    }

    private InvestigationSnapshotFactory factory(CustomerDataPort dataSource) {
        EnterpriseLegalRetriever retriever = mock(EnterpriseLegalRetriever.class);
        when(retriever.retrieve(any())).thenReturn(new RetrievalResponse(
                RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, "v1", List.of()));
        return new InvestigationSnapshotFactory(dataSource, new RiskFactAssembler(dataSource),
                retriever, new LegalKeywordResolver(), () -> "v1");
    }

    private CustomerDataPort stubDataSource() {
        CustomerDataPort dataSource = mock(CustomerDataPort.class);
        when(dataSource.findCustomer("C001")).thenReturn(Optional.of(CUSTOMER));
        when(dataSource.transactionsOf("C001")).thenReturn(List.of(TXN_A));
        when(dataSource.shareholdingsOf("C001")).thenReturn(List.of(SHAREHOLDING));
        when(dataSource.searchSanctions("张伟")).thenReturn(List.of(SANCTION));
        when(dataSource.searchSanctions("110101198506123456")).thenReturn(List.of());
        when(dataSource.asOfTime()).thenReturn(Instant.parse("2026-06-30T00:00:00Z"));
        return dataSource;
    }
}
