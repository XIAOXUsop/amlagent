package com.bank.aml.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionLeaseTest {

    @Test
    void leaseIsValidUntilMarkedLost() {
        ExecutionLease lease = new ExecutionLease(1L, 2, "worker-a");
        assertThat(lease.isValid()).isTrue();
        assertThat(lease.caseId()).isEqualTo(1L);
        assertThat(lease.executionVersion()).isEqualTo(2);
        assertThat(lease.workerId()).isEqualTo("worker-a");

        lease.markLost();
        assertThat(lease.isValid()).isFalse();
    }
}
