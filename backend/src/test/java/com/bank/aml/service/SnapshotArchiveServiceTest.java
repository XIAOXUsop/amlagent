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
}
