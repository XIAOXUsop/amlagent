package com.bank.aml.service;

import com.bank.aml.common.enums.WorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作流实时事件推送（SSE）。
 * <p>前端订阅 {@code /api/cases/{id}/events}，工作流每推进一个阶段即推送
 * {@code stage} 事件（JSON：caseId / stage / content），前端逐步高亮流程节点。
 */
@Service
public class WorkflowEventService {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public WorkflowEventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 订阅某工单的实时事件流 */
    public SseEmitter subscribe(Long caseId) {
        SseEmitter emitter = new SseEmitter(0L); // 不设超时，由连接关闭控制
        emitters.computeIfAbsent(caseId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(caseId, emitter));
        emitter.onTimeout(() -> remove(caseId, emitter));
        emitter.onError(e -> remove(caseId, emitter));
        return emitter;
    }

    /** 推送一个阶段事件 */
    public void emit(Long caseId, WorkflowStage stage, String content) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String payload = toJson(Map.of("caseId", caseId, "stage", stage.name(), "content", content));
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("stage").data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                list.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** 推送一个 token 片段（流式输出），事件名 token */
    public void emitToken(Long caseId, String token) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String payload = toJson(Map.of("caseId", caseId, "token", token));
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("token").data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                list.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    private void remove(Long caseId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(caseId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(caseId);
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
