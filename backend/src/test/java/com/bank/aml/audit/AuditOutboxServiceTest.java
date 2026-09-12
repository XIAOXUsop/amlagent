package com.bank.aml.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditOutboxServiceTest {

    private final AuditOutboxRepository repository = mock(AuditOutboxRepository.class);

    private final AuditOutboxService service = new AuditOutboxService(repository);

    @Test
    void persistsSanitizedEventInsideBusinessTransaction() {
        service.enqueue("CASE_REVIEW:7:0", "reviewer\r\n", "REVIEW_DECISION", "CASE", "7",
                "decision=CONFIRM_SUSPICIOUS" + (char) 7);

        ArgumentCaptor<AuditOutboxEvent> captor = ArgumentCaptor.forClass(AuditOutboxEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventKey()).isEqualTo("CASE_REVIEW:7:0");
        assertThat(captor.getValue().getActor()).doesNotContain("\r", "\n");
        assertThat(captor.getValue().getDetail()).doesNotContain(String.valueOf((char) 7));
    }

    @Test
    void outboxFailurePropagatesSoHighImpactBusinessWriteCanRollback() {
        doThrow(new IllegalStateException("db unavailable")).when(repository).save(any());

        assertThatThrownBy(() -> service.enqueue("CASE_REVIEW:7:0", "reviewer", "REVIEW_DECISION", "CASE", "7",
                "decision=CONFIRM_SUSPICIOUS"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("db unavailable");
    }

}
