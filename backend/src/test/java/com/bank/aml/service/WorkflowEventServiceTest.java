package com.bank.aml.service;

import com.bank.aml.common.enums.CaseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
