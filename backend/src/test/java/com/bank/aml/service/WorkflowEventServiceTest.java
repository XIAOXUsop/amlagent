package com.bank.aml.service;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.config.WorkflowProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowEventServiceTest {

    private final WorkflowEventService service = new WorkflowEventService(new ObjectMapper(), new WorkflowProperties());

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void terminalCaseSubscriptionDoesNotRegisterPermanentEmitter() {
        service.subscribe(1L, () -> CaseStatus.DONE);
        service.subscribe(2L, () -> CaseStatus.HOLD);
        service.subscribe(3L, () -> CaseStatus.FAILED);

        assertThat(service.activeEmitterCount(1L)).isZero();
        assertThat(service.activeEmitterCount(2L)).isZero();
        assertThat(service.activeEmitterCount(3L)).isZero();
    }

    @Test
    void runningSubscriptionIsRemovedWhenWorkflowCompletes() {
        service.subscribe(10L, () -> CaseStatus.RUNNING);
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
        Map<Long, List<SseEmitter>> emitters = (Map<Long, List<SseEmitter>>) ReflectionTestUtils.getField(service,
                "emitters");
        assertThat(emitters).isNotNull();
        emitters.put(20L, new CopyOnWriteArrayList<>(List.of(disconnected)));

        assertThatCode(() -> service.emit(20L, WorkflowStage.COLLECTING, "progress")).doesNotThrowAnyException();

        assertThat(service.activeEmitterCount(20L)).isZero();
        assertThat(completedWithError).isFalse();
    }

    @Test
    void serializationFailureClosesSubscribersInsteadOfSendingInvalidJson() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("synthetic failure") {
        });
        WorkflowEventService failingService = new WorkflowEventService(mapper, new WorkflowProperties());
        try {
            failingService.subscribe(30L, () -> CaseStatus.RUNNING);

            assertThatCode(() -> failingService.emit(30L, WorkflowStage.COLLECTING, "progress"))
                .doesNotThrowAnyException();
            assertThat(failingService.activeEmitterCount(30L)).isZero();
        }
        finally {
            failingService.shutdown();
        }
    }

    @Test
    void concurrentSubscriptionsShareExactlyOneHeartbeat() {
        CompletableFuture<?>[] subscriptions = IntStream.range(0, 32)
            .mapToObj(ignored -> CompletableFuture.runAsync(() -> service.subscribe(40L, () -> CaseStatus.RUNNING)))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(subscriptions).join();

        assertThat(service.activeEmitterCount(40L)).isEqualTo(32);
        assertThat(service.activeHeartbeatCount(40L)).isEqualTo(1);
    }

    @Test
    void terminalTransitionBetweenRegistrationAndStatusReadCannotLeakSubscription() {
        service.subscribe(50L, () -> {
            service.complete(50L, CaseStatus.DONE);
            return CaseStatus.DONE;
        });

        assertThat(service.activeEmitterCount(50L)).isZero();
        assertThat(service.activeHeartbeatCount(50L)).isZero();
    }

    @Test
    void serializationFailureCannotRemoveSubscriptionRegisteredAfterCompletion() throws Exception {
        BlockingFailingObjectMapper mapper = new BlockingFailingObjectMapper();
        WorkflowEventService failingService = new WorkflowEventService(mapper, new WorkflowProperties());
        try {
            failingService.subscribe(60L, () -> CaseStatus.RUNNING);
            CompletableFuture<Void> failedEmit = CompletableFuture
                .runAsync(() -> failingService.emit(60L, WorkflowStage.COLLECTING, "progress"));
            assertThat(mapper.awaitSerializationStarted()).isTrue();

            failingService.complete(60L, CaseStatus.DONE);
            failingService.subscribe(60L, () -> CaseStatus.RUNNING);
            mapper.releaseFailure();
            failedEmit.join();

            assertThat(failingService.activeEmitterCount(60L)).isEqualTo(1);
            assertThat(failingService.activeHeartbeatCount(60L)).isEqualTo(1);
        }
        finally {
            mapper.releaseFailure();
            failingService.shutdown();
        }
    }

    private static final class BlockingFailingObjectMapper extends ObjectMapper {

        private final AtomicBoolean failNextSerialization = new AtomicBoolean(true);

        private final CountDownLatch serializationStarted = new CountDownLatch(1);

        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            if (!failNextSerialization.compareAndSet(true, false)) {
                return "{}";
            }
            serializationStarted.countDown();
            try {
                if (!releaseFailure.await(2, TimeUnit.SECONDS)) {
                    throw new JsonProcessingException("timed out waiting to release synthetic failure") {
                    };
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JsonProcessingException("interrupted while waiting to release synthetic failure",
                        interrupted) {
                };
            }
            throw new JsonProcessingException("synthetic failure") {
            };
        }

        boolean awaitSerializationStarted() throws InterruptedException {
            return serializationStarted.await(2, TimeUnit.SECONDS);
        }

        void releaseFailure() {
            releaseFailure.countDown();
        }

    }

}
