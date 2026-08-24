package com.bank.aml.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 本地 Mock 模型：未配置 API Key 时用于演示完整 Agent 链路。
 * <p>支持模拟 agentic 工具调用循环：
 * <ol>
 *   <li>请求含工具定义 → 返回 ToolExecutionRequest（依次请求全部工具）</li>
 *   <li>对话中出现工具结果消息 → 返回最终回答（结构化输出场景返回 {@code {}}）</li>
 *   <li>普通对话 → 回显最后一条用户消息</li>
 * </ol>
 * 仅用于离线演示，不具备真实模型能力。
 */
public class MockChatModel implements ChatModel {

    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("C\\d{3}");
    private static final Pattern LEGAL_KEYWORDS_PATTERN = Pattern.compile("法规检索关键词[^：:]*[：:]\\s*([^\\n]+)");
    private static final Pattern LEGAL_EVIDENCE_PATTERN = Pattern.compile("\\bLEGAL-[A-Za-z0-9_-]+\\b");

    private final String modelName;

    public MockChatModel(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        boolean hasToolResults = messages.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage);

        // 已有工具结果：结束工具循环，给出最终回答
        if (hasToolResults) {
            String text = wantsJson(messages)
                    ? mockReportJson(allText(messages))
                    : "【Mock 模型】工具执行完成，已完成数据采集与分析。";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        List<ToolSpecification> tools = request.toolSpecifications();
        // 请求声明了工具：发起一轮工具调用（模拟模型自主规划）
        if (tools != null && !tools.isEmpty()) {
            String contextText = allText(messages);
            List<ToolExecutionRequest> reqs = tools.stream()
                    .map(t -> buildToolRequest(t, contextText))
                    .toList();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reqs))
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
        }

        // 普通对话
        String text = lastUserText(messages);
        String reply = wantsJson(messages) ? "{}" : "【Mock 模型】已收到：" + text.trim();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(reply))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private ToolExecutionRequest buildToolRequest(ToolSpecification tool, String contextText) {
        Map<String, JsonSchemaElement> props = tool.parameters() != null
                ? tool.parameters().properties()
                : Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (String prop : props.keySet()) {
            args.put(prop, defaultValueFor(prop, contextText));
        }
        String arguments = args.isEmpty() ? "{}" : toJson(args);
        return ToolExecutionRequest.builder()
                .id("mock-" + UUID.randomUUID())
                .name(tool.name())
                .arguments(arguments)
                .build();
    }

    /** 依据参数名推断演示用默认值，使 Mock 能驱动工具执行 */
    private String defaultValueFor(String prop, String contextText) {
        String p = prop.toLowerCase();
        if (p.contains("customer") || p.contains("client")) {
            return extractCustomerId(contextText);
        }
        if (p.contains("name")) {
            return "张伟";
        }
        if (p.contains("idcard") || p.contains("id_card") || p.contains("cert")) {
            return "110101198506123456";
        }
        if (p.contains("query") || p.contains("keyword") || p.contains("text")) {
            return extractLegalKeywords(contextText);
        }
        return "";
    }

    private String toJson(Map<String, String> args) {
        return args.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue().replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String extractCustomerId(String text) {
        Matcher m = CUSTOMER_ID_PATTERN.matcher(text);
        return m.find() ? m.group() : "C001";
    }

    private String extractLegalKeywords(String text) {
        Matcher matcher = LEGAL_KEYWORDS_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "反洗钱";
    }

    /** 结构化离线报告：用于验证真实 AiServices 工具循环，不再依赖规则 fallback 假装 Agent 成功。 */
    private String mockReportJson(String contextText) {
        Matcher evidenceMatcher = LEGAL_EVIDENCE_PATTERN.matcher(contextText);
        String evidenceId = evidenceMatcher.find() ? evidenceMatcher.group() : null;
        String legalBasis = evidenceId == null ? "离线 Mock 未检索到法规证据" : "依据法规证据 " + evidenceId;
        String evidenceChain = evidenceId == null ? "离线 Mock 工具执行链" : "法规检索证据 " + evidenceId;
        return """
                {"riskLevel":"低风险",
                 "transactionProfile":"已基于交易工具完成画像","corporateProfile":"已基于股权工具完成核验",
                 "sanctions":[],"legalBasis":["%s"],"riskPoints":["离线 Mock 仅验证工程链路"],
                 "conclusion":"离线 Mock 初审完成，最终评级以 Guardrails 为准","evidenceChain":["%s"],
                 "manualReviewRequired":true,"findingCodes":["RISK_ASSESSMENT_UNCERTAIN"],"actionCodes":["MANUAL_REVIEW"]}
                """.formatted(legalBasis, evidenceChain).replaceAll("\\s+", " ");
    }

    private String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof UserMessage um) {
                return um.singleText();
            }
        }
        return "";
    }

    private String allText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                sb.append(um.singleText()).append('\n');
            } else if (msg instanceof AiMessage am) {
                String t = am.text();
                if (t != null) sb.append(t).append('\n');
            } else if (msg instanceof ToolExecutionResultMessage tm) {
                if (tm.text() != null) sb.append(tm.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private boolean wantsJson(List<ChatMessage> messages) {
        String all = allText(messages).toLowerCase();
        return all.contains("json") || all.contains("输出格式") || all.contains("只输出结果");
    }

    @Override
    public String toString() {
        return "MockChatModel(" + modelName + ")";
    }
}
