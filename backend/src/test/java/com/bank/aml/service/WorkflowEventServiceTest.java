package com.bank.aml.service;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WorkflowEventServiceTest {

    private final WorkflowEventService service = new WorkflowEventService(new ObjectMapper());

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void terminalCaseSubscriptionDoesNotRegisterPermanentEmitter() {
        service.subscribe(1L, CaseStatus.DONE);
        service.subscribe(2L, CaseStatus.HOLD);
        service.subscribe(3L, CaseStatus.FAILED);

        assertThat(service.activeEmitterCount(1L)).isZero();
        assertThat(service.activeEmitterCount(2L)).isZero();
        assertThat(service.activeEmitterCount(3L)).isZero();
    }

    @Test
    void runningSubscriptionIsRemovedWhenWorkflowCompletes() {
        service.subscribe(10L, CaseStatus.RUNNING);
        assertThat(service.activeEmitterCount(10L)).isEqualTo(1);

        service.complete(10L, CaseStatus.HOLD);

        assertThat(service.activeEmitterCount(10L)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void disconnectedSubscriberIsRemovedWithoutDispatchingErrorIntoBusinessFlow() {
        AtomicBoolean completedWithError = new AtomicBoolean();
        SseEmitter disconnected = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public void completeWithError(Throwable ex) {
                completedWithError.set(true);
            }
        };
        Map<Long, List<SseEmitter>> emitters =
                (Map<Long, List<SseEmitter>>) ReflectionTestUtils.getField(service, "emitters");
        assertThat(emitters).isNotNull();
        emitters.put(20L, new CopyOnWriteArrayList<>(List.of(disconnected)));

        assertThatCode(() -> service.emit(20L, WorkflowStage.COLLECTING, "progress"))
                .doesNotThrowAnyException();

        assertThat(service.activeEmitterCount(20L)).isZero();
        assertThat(completedWithError).isFalse();
    }
}
