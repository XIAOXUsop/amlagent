package com.bank.aml.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 基础合规尽调助手接口。
 * <p>通过 {@link dev.langchain4j.service.AiServices} 动态实现，验证普通对话与结构化输出（POJO 返回）。
 */
public interface AgentAssistant {

    @SystemMessage("""
            你是商业银行反洗钱（AML）合规尽调助手。回答必须专业、严谨、简洁，
            仅依据已知信息作答，不编造数据。用中文回答。
            """)
    String chat(@UserMessage String message);

    /**
     * 根据客户风险描述给出初步风险评级（结构化输出：返回 JSON 可反序列化对象）。
     */
    @SystemMessage("""
            你是商业银行反洗钱（AML）合规尽调助手。
            根据客户风险描述，给出初步风险评级（仅限：低风险 / 中风险 / 高风险）与一句话摘要。
            只输出结果，不要解释。
            """)
    RiskSummary assess(@UserMessage String caseDescription);
}
