package com.bank.aml.service;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.risk.RiskContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotArchiveServiceTest {
    @Test
    void encryptedArchiveRoundTripsWithoutPersistingPlainIdentity() {
        InvestigationSnapshotRepository repository = mock(InvestigationSnapshotRepository.class);
        CustomerDataPort dataSource = mock(CustomerDataPort.class);
        AtomicReference<InvestigationSnapshotEntity> stored = new AtomicReference<>();
        when(repository.existsById("case-1-v1")).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(repository.findById("case-1-v1")).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(dataSource.sourceSystem()).thenReturn("BANK_CORE");
        when(dataSource.sourceVersion()).thenReturn("2026-08-19");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SnapshotArchiveService service = new SnapshotArchiveService(repository, dataSource, mapper);
        InvestigationSnapshot snapshot = snapshot();

        service.archive(snapshot);

        assertThat(stored.get().getPayloadCiphertext())
                .startsWith("enc:v1:")
                .doesNotContain("张伟", "110101198506123456");
        InvestigationSnapshot restored = service.loadAndVerify("case-1-v1");
        assertThat(restored).isEqualTo(snapshot);
        assertThat(stored.get().getSourceSystem()).isEqualTo("BANK_CORE");
    }

    /** 新 schema 归档携带预警摘要；重复归档校验一致后幂等返回。 */
    @Test
    void archiveStoresAlertsDigestAndIdempotentReplayVerifiesSameContent() {
        InvestigationSnapshotRepository repository = mock(InvestigationSnapshotRepository.class);
        CustomerDataPort dataSource = mock(CustomerDataPort.class);
        AtomicReference<InvestigationSnapshotEntity> stored = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(repository.findById("case-1-v1")).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(dataSource.sourceSystem()).thenReturn("BANK_CORE");
        when(dataSource.sourceVersion()).thenReturn("2026-08-19");
        SnapshotArchiveService service = new SnapshotArchiveService(repository, dataSource,
                new ObjectMapper().findAndRegisterModules());
        InvestigationSnapshot snapshot = snapshotWithAlerts("alerts-digest-1");

        service.archive(snapshot);
        service.archive(snapshotWithAlerts("alerts-digest-1"));

        assertThat(stored.get().getAlertsDigest()).isEqualTo("alerts-digest-1");
    }

    /** T08（单元层）：相同执行版本归档内容不一致必须显式失败，不能静默保留另一份内容。 */
    @Test
    void archiveConflictOnDifferentAlertsInputIsRejected() {
        InvestigationSnapshotRepository repository = mock(InvestigationSnapshotRepository.class);
        CustomerDataPort dataSource = mock(CustomerDataPort.class);
        AtomicReference<InvestigationSnapshotEntity> stored = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(repository.findById("case-1-v1")).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(dataSource.sourceSystem()).thenReturn("BANK_CORE");
        when(dataSource.sourceVersion()).thenReturn("2026-08-19");
        SnapshotArchiveService service = new SnapshotArchiveService(repository, dataSource,
                new ObjectMapper().findAndRegisterModules());

        service.archive(snapshotWithAlerts("alerts-digest-1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.archive(snapshotWithAlerts("alerts-digest-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("归档冲突");
    }

    private InvestigationSnapshot snapshot() {
        CustomerProfile customer = new CustomerProfile("C001", "张伟", "110101198506123456",
                "企业", "贸易", "上海", "5000万");
        RiskContext risk = new RiskContext(0, false, 0, 0, 0,
                true, true, 0, 0, "低风险", 1);
        return new InvestigationSnapshot("case-1-v1", 1L, 1,
                Instant.parse("2026-08-19T00:00:00Z"), customer,
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of(), List.of("客户尽职调查"),
                risk, "legal-hash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private InvestigationSnapshot snapshotWithAlerts(String alertsDigest) {
        CustomerProfile customer = new CustomerProfile("C001", "张伟", "110101198506123456",
                "企业", "贸易", "上海", "5000万");
        RiskContext risk = new RiskContext(0, false, 0, 0, 0,
                true, true, 0, 0, "低风险", 1);
        var alert = new com.bank.aml.domain.InvestigationAlertSnapshot(11L, "ALERT-A",
                "RULE-001", "STRUCTURING", "客户通过拆分现金交易规避监测",
                java.time.LocalDateTime.of(2026, 8, 1, 10, 0), 0);
        return new InvestigationSnapshot("case-1-v1", 1L, 1,
                Instant.parse("2026-08-19T00:00:00Z"), customer,
                List.of(alert), List.of(), List.of(), List.of(), List.of(), java.util.Map.of(),
                List.of("拆分"), risk, "legal-hash",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                alertsDigest, InvestigationSnapshot.SCHEMA_VERSION_WITH_ALERTS);
    }
}
