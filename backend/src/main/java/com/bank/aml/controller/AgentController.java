package com.bank.aml.controller;

import com.bank.aml.agent.AgentAssistant;
import com.bank.aml.agent.RiskSummary;
import com.bank.aml.config.LlmProperties;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 基础验证接口：LLM 连通性、对话、结构化输出。
 * <p>仅限 ADMIN（会触发真实模型调用），且生产环境不注册（调试用途，避免成本滥用面）。
 */
@RestController
@RequestMapping("/api/agent")
@PreAuthorize("hasRole('ADMIN')")
@Profile("!prod")
public class AgentController {

    private final AgentAssistant assistant;
    private final LlmProperties llmProperties;

    public AgentController(AgentAssistant assistant, LlmProperties llmProperties) {
        this.assistant = assistant;
        this.llmProperties = llmProperties;
    }

    /** 连通性验证：返回当前激活的提供商信息，并让模型回答一句话 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        String active = llmProperties.getActiveProvider();
        var p = llmProperties.active();
        String reply = assistant.chat("请回复：反洗钱尽调助手在线。只回复这句话，不要其他内容。");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("activeProvider", active);
        resp.put("modelType", p.typeEnum().name());
        resp.put("modelName", p.getModelName());
        resp.put("modelReply", reply);
        return resp;
    }

    /** 普通对话 */
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest req) {
        String reply = assistant.chat(req.message());
        return Map.of("reply", reply);
    }

    /** 结构化输出验证：风险评级摘要 */
    @PostMapping("/assess")
    public RiskSummary assess(@RequestBody ChatRequest req) {
        return assistant.assess(req.message());
    }

    public record ChatRequest(@NotBlank String message) {
    }
}
