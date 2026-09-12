package com.bank.aml.audit;

import com.bank.aml.datasource.entity.AuditLogEntity;
import com.bank.aml.datasource.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);

    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private AuditService audit() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AuditService(repository, txManager);
    }

    @Test
    void recordsSanitizedAuditEntry() {
        audit().record("alice", "REVIEW_DECISION", "CASE", "42", "success",
                "decision=APPROVE\r\nrisk=HIGH" + (char) 0x07, "10.0.0.9");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).save(captor.capture());
        AuditLogEntity entity = captor.getValue();
        // outcome 归一为大写 SUCCESS；控制字符被清理，换行保留不会被用来伪造审计行
        assertThat(entity.getOutcome()).isEqualTo("SUCCESS");
        assertThat(entity.getDetail()).doesNotContain("\r").doesNotContain("\u0007");
        assertThat(entity.getActor()).isEqualTo("alice");
    }

    @Test
    void overlongDetailIsTruncatedAndUnknownActorIsFallback() {
        audit().record(null, null, null, null, "WEIRD", "x".repeat(400), null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).save(captor.capture());
        AuditLogEntity entity = captor.getValue();
        assertThat(entity.getActor()).isEqualTo("unknown");
        assertThat(entity.getAction()).isEqualTo("UNKNOWN");
        assertThat(entity.getOutcome()).isEqualTo("SUCCESS"); // 非 FAILURE 一律按成功记录
        assertThat(entity.getDetail()).hasSize(256);
    }

    @Test
    void auditWriteFailureNeverBlocksBusinessFlow() {
        doThrow(new IllegalStateException("db down")).when(repository).save(any());

        assertThatCode(() -> audit().record("a", "LOGIN_SUCCESS", null, null, "SUCCESS", null, null))
            .doesNotThrowAnyException();
    }

}
