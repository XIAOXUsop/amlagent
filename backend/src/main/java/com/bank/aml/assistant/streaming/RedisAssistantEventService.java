package com.bank.aml.assistant.streaming;

import com.bank.aml.assistant.application.AssistantRunEventPublisher;
import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis Stream 是短期可重放展示通道；MySQL 消息仍是最终事实源。 */
@Service
public class RedisAssistantEventService implements AssistantRunEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RedisAssistantEventService.class);
    private static final Duration READ_BLOCK = Duration.ofSeconds(2);
    private static final long HEARTBEAT_SECONDS = 15;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AssistantProperties properties;
    private final ThreadPoolTaskExecutor sseExecutor;

    public RedisAssistantEventService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      AssistantProperties properties,
                                      @Qualifier("assistantSseExecutor") ThreadPoolTaskExecutor sseExecutor) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sseExecutor = sseExecutor;
    }

    @Override
    public void started(String runId, String assistantMessageId) {
        append(runId, "run_started", Map.of("runId", runId, "assistantMessageId", assistantMessageId));
    }

    @Override
    public void delta(String runId, String text) {
        if (text != null && !text.isEmpty()) append(runId, "delta", Map.of("runId", runId, "text", text));
    }

    @Override
    public void completed(String runId, AssistantResultType resultType) {
        append(runId, "completed", Map.of("runId", runId, "resultType", resultType.name()));
    }

    @Override
    public void refused(String runId, AssistantResultType resultType, String message) {
        append(runId, "refused", Map.of("runId", runId, "resultType", resultType.name(), "message", message));
    }

    @Override
    public void failed(String runId, String errorCode) {
        append(runId, "failed", Map.of("runId", runId, "errorCode", errorCode));
    }

    public SseEmitter subscribe(AssistantRunEntity run, String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(error -> open.set(false));
        try {
            sseExecutor.execute(() -> pump(run, lastEventId, emitter, open));
        } catch (RuntimeException exception) {
            open.set(false);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private void pump(AssistantRunEntity run, String lastEventId, SseEmitter emitter, AtomicBoolean open) {
        String key = key(run.getId());
        String offset = validOffset(lastEventId) ? lastEventId : "0-0";
        long lastHeartbeat = System.nanoTime();
        try {
            while (open.get()) {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        StreamReadOptions.empty().count(50).block(READ_BLOCK),
                        StreamOffset.create(key, ReadOffset.from(offset)));
                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        String event = String.valueOf(record.getValue().get("event"));
                        String data = String.valueOf(record.getValue().get("data"));
                        emitter.send(SseEmitter.event().id(record.getId().getValue()).name(event)
                                .data(data, MediaType.APPLICATION_JSON));
                        offset = record.getId().getValue();
                        if (isTerminalEvent(event)) {
                            emitter.complete();
                            open.set(false);
                            return;
                        }
                    }
                } else if (isTerminal(run.getStatus())) {
                    // Redis 展示事件已过期/丢失时只告知客户端回查 MySQL，不伪造回答正文。
                    String event = run.getStatus() == AssistantRunStatus.COMPLETED ? "completed"
                            : run.getStatus() == AssistantRunStatus.REFUSED ? "refused" : "failed";
                    emitter.send(SseEmitter.event().name(event).data(json(Map.of(
                            "runId", run.getId(), "reconcileRequired", true)), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    open.set(false);
                    return;
                }
                if (Duration.ofNanos(System.nanoTime() - lastHeartbeat).toSeconds() >= HEARTBEAT_SECONDS) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.nanoTime();
                }
            }
        } catch (IOException exception) {
            log.debug("AI 小助 SSE 客户端断开 runId={}", run.getId());
            emitter.complete();
        } catch (RuntimeException exception) {
            log.warn("AI 小助 SSE 读取失败 runId={} type={}", run.getId(), exception.getClass().getSimpleName());
            emitter.completeWithError(exception);
        } finally {
            open.set(false);
        }
    }

    private void append(String runId, String event, Map<String, ?> payload) {
        String key = key(runId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("data", json(payload));
        fields.put("createdAt", Instant.now().toString());
        redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(key));
        redis.expire(key, Duration.ofMinutes(properties.getEventStreamTtlMinutes()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 小助事件序列化失败", e);
        }
    }

    private boolean validOffset(String value) { return value != null && value.matches("[0-9]+-[0-9]+"); }
    private boolean isTerminalEvent(String event) {
        return "completed".equals(event) || "refused".equals(event) || "failed".equals(event);
    }
    private boolean isTerminal(AssistantRunStatus status) {
        return status == AssistantRunStatus.COMPLETED || status == AssistantRunStatus.REFUSED
                || status == AssistantRunStatus.FAILED || status == AssistantRunStatus.BLOCKED;
    }
    private String key(String runId) { return "aml:assistant:run:" + runId; }
}
