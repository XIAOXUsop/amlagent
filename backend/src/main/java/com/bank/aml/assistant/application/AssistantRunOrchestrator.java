package com.bank.aml.assistant.application;

import com.bank.aml.assistant.agent.CustomerAssistantAgent;
import com.bank.aml.assistant.agent.AssistantEvidenceCitationAppender;
import com.bank.aml.assistant.agent.AssistantToolBudgetFallback;
import com.bank.aml.assistant.agent.CustomerAssistantAgentFactory;
import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.guard.AssistantInputGuard;
import com.bank.aml.assistant.guard.AssistantOutputGuard;
import com.bank.aml.assistant.memory.AssistantHistoryLoader;
import com.bank.aml.assistant.memory.ConversationLeaseService;
import com.bank.aml.assistant.snapshot.AssistantSnapshotArchiveService;
import com.bank.aml.assistant.snapshot.CustomerAssistantSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 一条消息从输入防护到持久化终态的完整编排；模型调用不持有数据库事务。 */
@Service
public class AssistantRunOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AssistantRunOrchestrator.class);
    private static final String SAFE_FAILURE_MESSAGE = "AI 小助暂时无法完成分析，请稍后重试。";
    private static final String BLOCKED_MESSAGE = "本次回答未通过安全或证据校验，已停止展示。";

    private final AssistantConversationService conversations;
    private final AssistantRunStateService state;
    private final AssistantInputGuard inputGuard;
    private final AssistantOutputGuard outputGuard;
    private final CustomerAssistantSnapshotFactory snapshots;
    private final AssistantSnapshotArchiveService snapshotArchive;
    private final AssistantHistoryLoader history;
    private final CustomerAssistantAgentFactory agents;
    private final ConversationLeaseService leases;
    private final AssistantRunExecutor executor;
    private final ScheduledExecutorService leaseScheduler;
    private final AssistantRunEventPublisher events;
    private final AssistantProperties properties;

    public AssistantRunOrchestrator(AssistantConversationService conversations, AssistantRunStateService state,
                                    AssistantInputGuard inputGuard, AssistantOutputGuard outputGuard,
                                    CustomerAssistantSnapshotFactory snapshots,
                                    AssistantSnapshotArchiveService snapshotArchive,
                                    AssistantHistoryLoader history, CustomerAssistantAgentFactory agents,
                                    ConversationLeaseService leases, AssistantRunExecutor executor,
                                    @Qualifier("assistantLeaseScheduler") ScheduledExecutorService leaseScheduler,
                                    AssistantRunEventPublisher events, AssistantProperties properties) {
        this.conversations = conversations;
        this.state = state;
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
        this.snapshots = snapshots;
        this.snapshotArchive = snapshotArchive;
        this.history = history;
        this.agents = agents;
        this.leases = leases;
        this.executor = executor;
        this.leaseScheduler = leaseScheduler;
        this.events = events;
        this.properties = properties;
    }

    public AssistantConversationService.AcceptedRun submitMessage(String conversationId, String operatorUsername,
                                                                  String clientMessageId, String rawInput) {
        AssistantInputGuard.InputDecision decision = inputGuard.inspect(rawInput);
        String persistedInput = decision.sanitizedInput() == null || decision.sanitizedInput().isBlank()
                ? "[请求需要澄清]" : decision.sanitizedInput();
        var accepted = conversations.acceptMessage(conversationId, operatorUsername, clientMessageId, persistedInput);
        if (accepted.idempotentReplay()) return accepted;

        if (!decision.allowed()) {
            state.refuse(accepted.runId(), decision.response(), decision.resultType(), decision.intent().name());
            emitSafely(() -> events.refused(accepted.runId(), decision.resultType(), decision.response()));
            return accepted;
        }

        boolean submitted = executor.submit(() -> execute(accepted.runId(), decision));
        if (!submitted) {
            state.fail(accepted.runId(), SAFE_FAILURE_MESSAGE, "ASSISTANT_EXECUTOR_SATURATED", 0);
            emitSafely(() -> events.failed(accepted.runId(), "ASSISTANT_EXECUTOR_SATURATED"));
        }
        return accepted;
    }

    private void execute(String runId, AssistantInputGuard.InputDecision decision) {
        long started = System.nanoTime();
        ConversationLeaseService.LeaseHandle lease = null;
        ScheduledFuture<?> renewal = null;
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        CustomerAssistantAgentFactory.AgentWithTools agentWithTools = null;
        try {
            var context = state.load(runId);
            lease = leases.tryAcquire(context.conversation().getId()).orElse(null);
            if (lease == null) {
                safeFail(runId, "CONVERSATION_BUSY", started);
                return;
            }
            ConversationLeaseService.LeaseHandle acquiredLease = lease;
            renewal = leaseScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!leases.renew(acquiredLease)) leaseValid.set(false);
                } catch (RuntimeException exception) {
                    leaseValid.set(false);
                }
            }, properties.getLeaseRenewSeconds(), properties.getLeaseRenewSeconds(), TimeUnit.SECONDS);

            state.processing(runId, decision.intent().name(), CustomerAssistantAgent.PROMPT_VERSION);
            var snapshot = snapshots.create(runId, context.conversation(), decision.sanitizedInput(), decision.intent());
            snapshotArchive.archive(snapshot);
            state.attachSnapshot(runId, snapshot.snapshotId(), snapshot.sourceDigest(),
                    LocalDateTime.ofInstant(snapshot.asOfTime(), ZoneId.systemDefault()));
            var memory = history.load(context.conversation().getId(), context.userMessage().getId());
            agentWithTools = agents.create(snapshot, memory);
            emitSafely(() -> events.started(runId, context.assistantMessage().getId()));

            StringBuffer rawOutput = new StringBuffer();
            AtomicReference<Throwable> modelError = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            agentWithTools.agent().chat(context.conversation().getId(), decision.sanitizedInput())
                    .onPartialResponse(delta -> {
                        rawOutput.append(delta);
                    })
                    .onCompleteResponse(response -> completed.countDown())
                    .onError(error -> {
                        modelError.compareAndSet(null, error);
                        completed.countDown();
                    })
                    .start();

            boolean ended = completed.await(properties.getRunTimeoutSeconds(), TimeUnit.SECONDS);
            if (!ended) {
                safeFail(runId, "MODEL_TIMEOUT", started, agentWithTools);
                return;
            }
            if (!leaseValid.get()) {
                safeFail(runId, "CONVERSATION_LEASE_LOST", started, agentWithTools);
                return;
            }
            if (modelError.get() != null) {
                Throwable error = modelError.get();
                log.warn("AI 小助模型调用失败 runId={} type={}", runId,
                        error.getClass().getSimpleName(), error);
                List<com.bank.aml.assistant.agent.AssistantToolTrace> toolTraces = agentWithTools.tools().traces();
                if (isToolRoundLimit(error) && toolTraces.stream().anyMatch(trace -> "SUCCESS".equals(trace.status()))) {
                    String fallback = AssistantToolBudgetFallback.create(snapshot, decision.intent());
                    fallback = AssistantEvidenceCitationAppender.appendMissing(fallback, toolTraces);
                    var fallbackValidation = outputGuard.validate(snapshot, fallback);
                    if (fallbackValidation.valid()) {
                        emitValidatedAnswer(runId, "\n\n" + fallback);
                        state.complete(runId, fallback, elapsedMs(started), toolTraces);
                        emitSafely(() -> events.completed(runId, AssistantResultType.ANSWERED));
                        return;
                    }
                }
                safeFail(runId, "MODEL_ERROR", started, agentWithTools);
                return;
            }
            List<com.bank.aml.assistant.agent.AssistantToolTrace> toolTraces = agentWithTools.tools().traces();
            // 模型正文在完整输出、证据归一化和最终护栏通过前绝不发送，避免“先泄漏/误导、后阻断”。
            String answer = AssistantEvidenceCitationAppender.normalizeAndAppend(rawOutput.toString(), toolTraces);
            var validation = outputGuard.validate(snapshot, answer);
            if (!validation.valid()) {
                if (toolTraces.stream().anyMatch(trace -> "SUCCESS".equals(trace.status()))) {
                    String fallback = AssistantToolBudgetFallback.create(snapshot, decision.intent());
                    fallback = AssistantEvidenceCitationAppender.appendMissing(fallback, toolTraces);
                    var fallbackValidation = outputGuard.validate(snapshot, fallback);
                    if (fallbackValidation.valid()) {
                        log.warn("AI 小助模型输出未通过校验，已切换确定性只读回答 runId={} violations={}",
                                runId, validation.violations());
                        emitValidatedAnswer(runId, fallback);
                        state.complete(runId, fallback, elapsedMs(started), toolTraces);
                        emitSafely(() -> events.completed(runId, AssistantResultType.ANSWERED));
                        return;
                    }
                }
                state.block(runId, BLOCKED_MESSAGE, String.join(",", validation.violations()),
                        elapsedMs(started), toolTraces);
                emitSafely(() -> events.failed(runId, "OUTPUT_BLOCKED"));
                return;
            }
            emitValidatedAnswer(runId, answer);
            state.complete(runId, answer, elapsedMs(started), toolTraces);
            emitSafely(() -> events.completed(runId, AssistantResultType.ANSWERED));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            safeFail(runId, "RUN_INTERRUPTED", started, agentWithTools);
        } catch (RuntimeException exception) {
            log.warn("AI 小助执行失败 runId={} type={}", runId,
                    exception.getClass().getSimpleName(), exception);
            safeFail(runId, "ASSISTANT_EXECUTION_FAILED", started, agentWithTools);
        } finally {
            if (renewal != null) renewal.cancel(false);
            if (lease != null) {
                try { leases.release(lease); } catch (RuntimeException ignored) { /* TTL 最终释放 */ }
            }
        }
    }

    private void safeFail(String runId, String code, long started) {
        safeFail(runId, code, started, null);
    }

    private void safeFail(String runId, String code, long started,
                          CustomerAssistantAgentFactory.AgentWithTools agentWithTools) {
        try {
            List<com.bank.aml.assistant.agent.AssistantToolTrace> toolTraces = agentWithTools == null
                    ? List.of() : agentWithTools.tools().traces();
            state.fail(runId, SAFE_FAILURE_MESSAGE, code, elapsedMs(started), toolTraces);
            emitSafely(() -> events.failed(runId, code));
        } catch (RuntimeException exception) {
            log.error("AI 小助失败状态落库异常 runId={} code={}", runId, code, exception);
        }
    }

    private void emitSafely(Runnable emission) {
        try { emission.run(); } catch (RuntimeException exception) {
            log.warn("AI 小助实时事件写入失败 type={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 仅对已经通过完整输出护栏的正文做有节奏的 SSE 分块。
     * 这不是模型 token 直通，断线后仍可通过 Redis Stream 重放并以 MySQL 终态对账。
     */
    private void emitValidatedAnswer(String runId, String answer) throws InterruptedException {
        List<String> chunks = ValidatedAnswerChunker.split(answer, properties.getValidatedStreamChunkChars());
        int delayMs = properties.getValidatedStreamChunkDelayMs();
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            emitSafely(() -> events.delta(runId, chunk));
            if (delayMs > 0 && index + 1 < chunks.size()) {
                Thread.sleep(delayMs);
            }
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static boolean isToolRoundLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("maxToolCallingRoundTrips")) return true;
            current = current.getCause();
        }
        return false;
    }
}
