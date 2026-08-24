package com.bank.aml.assistant.application;

import com.bank.aml.assistant.agent.AssistantToolTrace;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.entity.AssistantToolTraceEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import com.bank.aml.assistant.persistence.repository.AssistantToolTraceRepository;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.observability.MetricsRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 异步回调通过独立事务推进 run 和 AI 消息状态，避免长事务包围模型调用。 */
@Service
public class AssistantRunStateService {
    private final AssistantRunRepository runs;
    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final AssistantToolTraceRepository traces;
    private final ObjectMapper objectMapper;
    private final LlmProperties llm;
    private final MetricsRecorder metrics;

    public AssistantRunStateService(AssistantRunRepository runs,
                                    AssistantConversationRepository conversations,
                                    AssistantMessageRepository messages,
                                    AssistantToolTraceRepository traces,
                                    ObjectMapper objectMapper, LlmProperties llm, MetricsRecorder metrics) {
        this.runs = runs;
        this.conversations = conversations;
        this.messages = messages;
        this.traces = traces;
        this.objectMapper = objectMapper;
        this.llm = llm;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public RunContext load(String runId) {
        AssistantRunEntity run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("run 不存在"));
        AssistantConversationEntity conversation = conversations.findById(run.getConversationId())
                .orElseThrow(ConversationNotFoundException::new);
        AssistantMessageEntity user = messages.findById(run.getUserMessageId())
                .orElseThrow(() -> new IllegalStateException("用户消息不存在"));
        AssistantMessageEntity assistant = messages.findById(run.getAssistantMessageId())
                .orElseThrow(() -> new IllegalStateException("AI 消息不存在"));
        return new RunContext(run, conversation, user, assistant);
    }

    @Transactional
    public void processing(String runId, String intent, String promptVersion) {
        AssistantRunEntity run = requireRun(runId);
        run.processing(intent);
        run.model(llm.getActiveProvider(), llm.active().getModelName(), promptVersion);
        runs.save(run);
    }

    @Transactional
    public void attachSnapshot(String runId, String snapshotId, String digest, java.time.LocalDateTime asOfTime) {
        AssistantRunEntity run = requireRun(runId);
        run.attachSnapshot(snapshotId, digest, asOfTime);
        runs.save(run);
    }

    @Transactional
    public void complete(String runId, String content, long durationMs, List<AssistantToolTrace> toolTraces) {
        AssistantRunEntity run = requireRun(runId);
        AssistantMessageEntity answer = requireMessage(run.getAssistantMessageId());
        answer.complete(content, AssistantResultType.ANSWERED);
        run.complete(durationMs, null, null);
        messages.save(answer);
        runs.save(run);
        saveTraces(runId, toolTraces);
        metrics.assistantRun(AssistantRunStatus.COMPLETED.name(), run.getIntent(), durationMs);
    }

    @Transactional
    public void refuse(String runId, String content, AssistantResultType resultType, String intent) {
        AssistantRunEntity run = requireRun(runId);
        AssistantMessageEntity answer = requireMessage(run.getAssistantMessageId());
        run.processing(intent);
        answer.refuse(content, resultType);
        run.terminal(AssistantRunStatus.REFUSED, resultType.name(), 0);
        messages.save(answer);
        runs.save(run);
        metrics.assistantRun(AssistantRunStatus.REFUSED.name(), intent, 0);
    }

    @Transactional
    public void block(String runId, String content, String failureCode, long durationMs,
                      List<AssistantToolTrace> toolTraces) {
        AssistantRunEntity run = requireRun(runId);
        AssistantMessageEntity answer = requireMessage(run.getAssistantMessageId());
        answer.block(content);
        run.terminal(AssistantRunStatus.BLOCKED, failureCode, durationMs);
        messages.save(answer);
        runs.save(run);
        saveTraces(runId, toolTraces);
        metrics.assistantRun(AssistantRunStatus.BLOCKED.name(), run.getIntent(), durationMs);
        metrics.assistantOutputBlocked("OUTPUT_GUARD");
    }

    @Transactional
    public void fail(String runId, String publicMessage, String failureCode, long durationMs) {
        fail(runId, publicMessage, failureCode, durationMs, List.of());
    }

    @Transactional
    public void fail(String runId, String publicMessage, String failureCode, long durationMs,
                     List<AssistantToolTrace> toolTraces) {
        AssistantRunEntity run = requireRun(runId);
        AssistantMessageEntity answer = requireMessage(run.getAssistantMessageId());
        answer.fail(publicMessage, AssistantResultType.MODEL_UNAVAILABLE);
        run.terminal(AssistantRunStatus.FAILED, failureCode, durationMs);
        messages.save(answer);
        runs.save(run);
        saveTraces(runId, toolTraces);
        metrics.assistantRun(AssistantRunStatus.FAILED.name(), run.getIntent(), durationMs);
    }

    private void saveTraces(String runId, List<AssistantToolTrace> toolTraces) {
        if (toolTraces == null) return;
        for (AssistantToolTrace trace : toolTraces) {
            try {
                traces.save(AssistantToolTraceEntity.create(runId, trace.sequenceNo(), trace.toolName(),
                        trace.status(), trace.durationMs(), trace.resultDigest(),
                        objectMapper.writeValueAsString(trace.evidenceIds()), trace.errorCode()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("工具证据轨迹序列化失败", e);
            }
        }
    }

    private AssistantRunEntity requireRun(String runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("run 不存在"));
    }

    private AssistantMessageEntity requireMessage(String messageId) {
        return messages.findById(messageId).orElseThrow(() -> new IllegalStateException("AI 消息不存在"));
    }

    public record RunContext(AssistantRunEntity run, AssistantConversationEntity conversation,
                             AssistantMessageEntity userMessage, AssistantMessageEntity assistantMessage) {}
}
