package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowCommandServiceTest {

    @Test
    void reclaimDoesNotEnqueueWhenHeartbeatRefreshed() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        // 条件 UPDATE 返回 0：心跳在扫描后刷新，或已被其他 Claimer 接管
        when(repo.reclaimStuckCase(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.RUNNING),
                eq(3), eq("worker-a"), any(LocalDateTime.class))).thenReturn(0);

        boolean reclaimed = svc.reclaimExpiredCase(1L, 3, "worker-a", LocalDateTime.now());

        assertThat(reclaimed).isFalse();
        // 未命中条件时不得写入 Outbox（不重新投递）
        verify(outbox, never()).record(anyLong(), anyString(), anyInt());
    }

    @Test
    void reclaimEnqueuesWhenConditionalUpdateSucceeds() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.reclaimStuckCase(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.RUNNING),
                eq(3), eq("worker-a"), any(LocalDateTime.class))).thenReturn(1);

        boolean reclaimed = svc.reclaimExpiredCase(1L, 3, "worker-a", LocalDateTime.now());

        assertThat(reclaimed).isTrue();
        verify(outbox).record(1L, WorkflowEventType.CASE_RECLAIMED.name(), 3);
    }

    @Test
    void markDeadLetterFailsAndEnqueuesDeadLetterEvent() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.failCase(eq(1L), eq(CaseStatus.FAILED), eq(3), eq("RETRY_EXHAUSTED"),
                anyString(), eq("worker-a"), eq(2))).thenReturn(1);

        boolean marked = svc.markDeadLetter(1L, "worker-a", 2, 3, "重试超限");

        assertThat(marked).isTrue();
        verify(outbox).record(1L, WorkflowEventType.CASE_DEAD_LETTER.name(), 2);
    }

    @Test
    void markDeadLetterDoesNotEnqueueWhenAlreadyReclaimed() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        // 已失去租约（worker/version 不匹配），failCase 返回 0
        when(repo.failCase(eq(1L), eq(CaseStatus.FAILED), eq(3), eq("RETRY_EXHAUSTED"),
                anyString(), eq("worker-a"), eq(2))).thenReturn(0);

        boolean marked = svc.markDeadLetter(1L, "worker-a", 2, 3, "重试超限");

        assertThat(marked).isFalse();
        verify(outbox, never()).record(anyLong(), anyString(), anyInt());
    }

    @Test
    void retryManualThrowsConflictWhenNotFailed() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.retryFailed(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.FAILED))).thenReturn(0);
        CaseEntity done = new CaseEntity();
        done.setStatus(CaseStatus.DONE);
        when(repo.findById(1L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> svc.retryManual(1L))
                .isInstanceOf(WorkflowStateConflictException.class);
        verify(outbox, never()).record(anyLong(), anyString(), anyInt());
    }

    @Test
    void replayDeadThrowsConflictWhenNotFailed() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.replayDeadLetter(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.FAILED), anyCollection())).thenReturn(0);
        CaseEntity done = new CaseEntity();
        done.setStatus(CaseStatus.DONE);
        when(repo.findById(1L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> svc.replayDead(1L))
                .isInstanceOf(WorkflowStateConflictException.class);
        verify(outbox, never()).record(anyLong(), anyString(), anyInt());
    }

    @Test
    void retryManualResetsAndEnqueuesWhenFailed() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.retryFailed(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.FAILED))).thenReturn(1);
        CaseEntity failed = new CaseEntity();
        failed.setStatus(CaseStatus.FAILED);
        failed.setExecutionVersion(2);
        when(repo.findById(1L)).thenReturn(Optional.of(failed));

        CaseEntity result = svc.retryManual(1L);

        assertThat(result).isNotNull();
        // 人工重试使用 CASE_MANUAL_RETRIED 事件类型
        verify(outbox).record(1L, WorkflowEventType.CASE_MANUAL_RETRIED.name(), 2);
    }

    @Test
    void replayDeadRestrictsToDeadLetterFailureCodes() {
        CaseRepository repo = mock(CaseRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        WorkflowCommandService svc = new WorkflowCommandService(repo, outbox);

        when(repo.replayDeadLetter(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.FAILED), anyCollection()))
                .thenReturn(1);
        CaseEntity dead = new CaseEntity();
        dead.setStatus(CaseStatus.FAILED);
        dead.setFailureCode("RETRY_EXHAUSTED");
        dead.setExecutionVersion(3);
        when(repo.findById(1L)).thenReturn(Optional.of(dead));

        svc.replayDead(1L);

        // 验证死信重放只允许 RETRY_EXHAUSTED / CLAIM_EXHAUSTED 两种 failureCode
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(repo).replayDeadLetter(eq(1L), eq(CaseStatus.PENDING), eq(CaseStatus.FAILED), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("RETRY_EXHAUSTED", "CLAIM_EXHAUSTED");
        verify(outbox).record(1L, WorkflowEventType.CASE_DEAD_REPLAYED.name(), 3);
    }
}
