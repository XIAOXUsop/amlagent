package com.bank.aml.service;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.config.WorkflowProperties;
import com.bank.aml.messaging.WorkflowEventPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 工作流实时事件推送（SSE）。
 * <p>
 * 前端订阅 {@code /api/cases/{id}/events}，工作流每推进一个阶段即推送 {@code stage} 事件（JSON：caseId / stage
 * / content），前端逐步高亮流程节点。
 * <p>
 * 生命周期增强：
 * <ul>
 * <li>定期发送 {@code heartbeat} 保活事件，避免网关/代理因空闲掐断 SSE 长连接；</li>
 * <li>工单到达终态时广播 {@code DONE} 终端事件并 {@link SseEmitter#complete()}，
 * 让前端明确知道"流已结束"，避免连接悬挂。</li>
 * </ul>
 */
@Service
public class WorkflowEventService implements WorkflowEventPort {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventService.class);

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final Map<Long, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /** 共享心跳调度器：避免每个订阅工单各建一个线程（并发订阅多时线程数失控） */
    private final ScheduledExecutorService heartbeatScheduler;

    private final long heartbeatIntervalSeconds;

    public WorkflowEventService(ObjectMapper objectMapper, WorkflowProperties properties) {
        this.objectMapper = objectMapper;
        this.heartbeatIntervalSeconds = properties.getEventHeartbeatSeconds();
        this.heartbeatScheduler = Executors.newScheduledThreadPool(properties.getEventHeartbeatThreads(), r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** 订阅某工单的实时事件流；已到终态的历史工单立即返回终态事件并关闭，避免永久心跳泄漏。 */
    public SseEmitter subscribe(Long caseId, Supplier<CaseStatus> currentStatus) {
        SseEmitter emitter = new SseEmitter(0L); // 不设应用级超时，由心跳维持 + 用户断开/终态结束
        emitter.onCompletion(() -> remove(caseId, emitter));
        emitter.onTimeout(() -> remove(caseId, emitter));
        emitter.onError(e -> remove(caseId, emitter));
        emitters.compute(caseId, (ignored, existing) -> {
            List<SseEmitter> subscribers = existing == null ? new CopyOnWriteArrayList<>() : existing;
            subscribers.add(emitter);
            startHeartbeat(caseId);
            return subscribers;
        });
        try {
            CaseStatus statusAfterRegistration = currentStatus.get();
            if (isTerminal(statusAfterRegistration)) {
                if (remove(caseId, emitter)) {
                    sendTerminal(emitter, caseId, statusAfterRegistration);
                }
            }
        }
        catch (RuntimeException statusLookupFailure) {
            remove(caseId, emitter);
            throw statusLookupFailure;
        }
        return emitter;
    }

    /**
     * 推送"工单已到达终态"并结束所有连接。 由业务方在工作流完成/转人工/失败时调用一次。
     */
    @Override
    public void complete(Long caseId, CaseStatus terminalStatus) {
        List<SseEmitter> list = new CopyOnWriteArrayList<>();
        emitters.compute(caseId, (ignored, subscribers) -> {
            if (subscribers != null) {
                list.addAll(subscribers);
            }
            stopHeartbeat(caseId);
            return null;
        });
        for (SseEmitter emitter : list) {
            sendTerminal(emitter, caseId, terminalStatus);
        }
        if (!list.isEmpty()) {
            log.debug("工单 {} SSE 已结束（{} 个连接）", caseId, list.size());
        }
    }

    private void sendTerminal(SseEmitter emitter, Long caseId, CaseStatus terminalStatus) {
        CaseStatus status = isTerminal(terminalStatus) ? terminalStatus : CaseStatus.DONE;
        try {
            String payload = serialize(caseId, "stage",
                    Map.of("caseId", caseId, "stage", status.name(), "content", terminalContent(status)));
            if (payload == null) {
                return;
            }
            emitter.send(SseEmitter.event().name("stage").data(payload, MediaType.APPLICATION_JSON));
        }
        catch (IOException e) {
            log.debug("工单 {} SSE 终端事件发送失败：{}", caseId, e.getMessage());
        }
        finally {
            emitter.complete();
        }
    }

    private boolean isTerminal(CaseStatus status) {
        return status == CaseStatus.DONE || status == CaseStatus.HOLD || status == CaseStatus.FAILED;
    }

    private String terminalContent(CaseStatus status) {
        return switch (status) {
            case DONE -> "工作流执行完成";
            case HOLD -> "工作流已完成，工单转入人工复核";
            case FAILED -> "工作流执行失败，可查看失败原因或人工重试";
            default -> "工作流已结束";
        };
    }

    /** 推送一个阶段事件 */
    public void emit(Long caseId, WorkflowStage stage, String content) {
        send(caseId, "stage", Map.of("caseId", caseId, "stage", stage.name(), "content", content));
    }

    /** 推送一个 token 片段（流式输出），事件名 token */
    public void emitToken(Long caseId, String token) {
        send(caseId, "token", Map.of("caseId", caseId, "token", token));
    }

    private void send(Long caseId, String eventName, Map<String, ?> payload) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String data = serialize(caseId, eventName, payload);
        if (data == null) {
            AtomicBoolean detached = new AtomicBoolean();
            emitters.computeIfPresent(caseId, (ignored, currentSubscribers) -> {
                if (currentSubscribers != list) {
                    return currentSubscribers;
                }
                detached.set(true);
                stopHeartbeat(caseId);
                return null;
            });
            if (detached.get()) {
                list.forEach(SseEmitter::complete);
            }
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            }
            catch (IOException e) {
                remove(caseId, emitter);
                // 客户端关闭页面/网络中断只代表订阅失效，不能把传输异常重新派发到 MVC
                // 异常链并反向中断正在执行的尽调业务。
                log.debug("工单 {} SSE 客户端已断开：{}", caseId, e.getMessage());
            }
        }
    }

    /** 周期性发送心跳，保持连接活跃、避免被代理掐断 */
    private void startHeartbeat(Long caseId) {
        heartbeats.compute(caseId, (ignored, existing) -> {
            if (existing != null && !existing.isCancelled() && !existing.isDone()) {
                return existing;
            }
            return heartbeatScheduler.scheduleAtFixedRate(() -> {
                List<SseEmitter> list = emitters.get(caseId);
                if (list == null || list.isEmpty()) {
                    // 与订阅注册使用同一个 caseId 原子区间，防止刚注册的新订阅
                    // 复用了即将被旧心跳任务取消的 ScheduledFuture。
                    emitters.compute(caseId, (caseKey, currentSubscribers) -> {
                        if (currentSubscribers == null || currentSubscribers.isEmpty()) {
                            stopHeartbeat(caseId);
                            return null;
                        }
                        return currentSubscribers;
                    });
                    return;
                }
                for (SseEmitter emitter : list) {
                    try {
                        // 注释事件（comment）不触发前端 EventSource 的 message 收集，纯保活
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                    catch (IOException e) {
                        remove(caseId, emitter);
                    }
                }
            }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        });
    }

    private void stopHeartbeat(Long caseId) {
        ScheduledFuture<?> future = heartbeats.remove(caseId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean remove(Long caseId, SseEmitter emitter) {
        AtomicBoolean removed = new AtomicBoolean();
        emitters.computeIfPresent(caseId, (ignored, subscribers) -> {
            removed.set(subscribers.remove(emitter));
            if (subscribers.isEmpty()) {
                stopHeartbeat(caseId);
                return null;
            }
            return subscribers;
        });
        return removed.get();
    }

    private String serialize(Long caseId, String eventName, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException | RuntimeException e) {
            log.error("工单 SSE 事件序列化失败，关闭当前订阅 caseId={} eventName={}", caseId, eventName, e);
            return null;
        }
    }

    int activeEmitterCount(Long caseId) {
        List<SseEmitter> list = emitters.get(caseId);
        return list == null ? 0 : list.size();
    }

    int activeHeartbeatCount(Long caseId) {
        ScheduledFuture<?> heartbeat = heartbeats.get(caseId);
        return heartbeat != null && !heartbeat.isCancelled() && !heartbeat.isDone() ? 1 : 0;
    }

    /** 应用关闭时释放共享心跳线程池，避免泄漏 */
    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
    }

}
