package com.bank.aml.service;

import com.bank.aml.common.enums.WorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工作流实时事件推送（SSE）。
 * <p>前端订阅 {@code /api/cases/{id}/events}，工作流每推进一个阶段即推送
 * {@code stage} 事件（JSON：caseId / stage / content），前端逐步高亮流程节点。
 * <p>生命周期增强：
 * <ul>
 *   <li>定期发送 {@code heartbeat} 保活事件，避免网关/代理因空闲掐断 SSE 长连接；</li>
 *   <li>工单到达终态时广播 {@code DONE} 终端事件并 {@link SseEmitter#complete()}，
 *       让前端明确知道"流已结束"，避免连接悬挂。</li>
 * </ul>
 */
@Service
public class WorkflowEventService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventService.class);

    /** 心跳间隔（秒）：比大多数网关 read-timeout 短，维持长连接活跃 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledExecutorService> heartbeats = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public WorkflowEventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 订阅某工单的实时事件流 */
    public SseEmitter subscribe(Long caseId) {
        SseEmitter emitter = new SseEmitter(0L); // 不设应用级超时，由心跳维持 + 用户断开/终态结束
        emitters.computeIfAbsent(caseId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(caseId, emitter));
        emitter.onTimeout(() -> remove(caseId, emitter));
        emitter.onError(e -> remove(caseId, emitter));
        startHeartbeat(caseId);
        return emitter;
    }

    /**
     * 推送"工单已到达终态"并结束所有连接。
     * 由业务方在工作流完成/转人工/失败时调用一次。
     */
    public void complete(Long caseId) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list == null || list.isEmpty()) {
            stopHeartbeat(caseId);
            return;
        }
        String payload = toJson(Map.of("caseId", caseId, "stage", "DONE", "content", "工作流执行完成"));
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("工单 {} SSE 终端事件发送失败：{}", caseId, e.getMessage());
            }
            emitter.complete();
        }
        stopHeartbeat(caseId);
        emitters.remove(caseId);
        log.debug("工单 {} SSE 已结束（{} 个连接）", caseId, list.size());
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
        String data = toJson(payload);
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                remove(caseId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** 周期性发送心跳，保持连接活跃、避免被代理掐断 */
    private void startHeartbeat(Long caseId) {
        stopHeartbeat(caseId); // 幂等：防止重复调度
        ScheduledExecutorService scheduler = newHeartbeatScheduler(caseId);
        if (scheduler == null) {
            return;
        }
        heartbeats.put(caseId, scheduler);
        scheduler.scheduleAtFixedRate(() -> {
            List<SseEmitter> list = emitters.get(caseId);
            if (list == null || list.isEmpty()) {
                stopHeartbeat(caseId);
                return;
            }
            for (SseEmitter emitter : list) {
                try {
                    // 注释事件（comment）不触发前端 EventSource 的 message 收集，纯保活
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException e) {
                    remove(caseId, emitter);
                }
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private ScheduledExecutorService newHeartbeatScheduler(Long caseId) {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + caseId);
            t.setDaemon(true);
            return t;
        });
    }

    private void stopHeartbeat(Long caseId) {
        ScheduledExecutorService scheduler = heartbeats.remove(caseId);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void remove(Long caseId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(caseId);
                stopHeartbeat(caseId);
            }
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
