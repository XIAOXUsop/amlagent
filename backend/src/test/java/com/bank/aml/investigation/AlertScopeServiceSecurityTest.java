package com.bank.aml.investigation;

import com.bank.aml.TestClocks;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.TransactionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertScopeServiceSecurityTest {

    private final AmlAlertRepository alerts = mock(AmlAlertRepository.class);

    private final CustomerDataPort customerData = mock(CustomerDataPort.class);

    private final AlertScopeService service = new AlertScopeService(alerts, new ObjectMapper(), customerData,
            TestClocks.FIXED);

    @BeforeEach
    void setUp() {
        AmlAlert alert = new AmlAlert();
        setId(alert, 11L);
        alert.setCustomerId("C001");
        when(alerts.findById(11L)).thenReturn(Optional.of(alert));
        when(customerData.sourceVersion()).thenReturn("MONITOR-2026-09");
        when(customerData.transactionsOf("C001"))
            .thenReturn(List.of(new TransactionRecord(LocalDateTime.parse("2026-09-01T10:00:00"),
                    new BigDecimal("100.00"), "IN", "对手方", null, "TRANSFER", "GOODS", "CNY", "T-1001")));
    }

    @Test
    void freezesOnlyCurrentAuthoritativeSourceVersion() {
        AlertScopeService.ScopeSnapshot snapshot = service.freezeScope(11L, List.of("T-1001"), List.of(),
                "MONITOR-2026-09", "admin");
        assertThat(snapshot.triggerTransactionIds()).containsExactly("T-1001");
        assertThat(snapshot.sourceVersion()).isEqualTo("MONITOR-2026-09");
    }

    @Test
    void rejectsStaleSourceVersionAndForeignTransaction() {
        assertThatThrownBy(() -> service.freezeScope(11L, List.of("T-1001"), List.of(), "MONITOR-OLD", "admin"))
            .hasMessageContaining("来源版本");
        assertThatThrownBy(() -> service.freezeScope(11L, List.of("T-OTHER"), List.of(), "MONITOR-2026-09", "admin"))
            .hasMessageContaining("权威来源集合");
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
