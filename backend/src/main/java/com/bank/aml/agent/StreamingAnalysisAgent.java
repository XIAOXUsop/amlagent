package com.bank.aml.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 流式风险分析 Agent：token 级流式输出风险分析推理过程，供前端实时展示模型分析思路。
 * <p>与 {@link DueDiligenceAgent}（同步 + 工具）分离，使用流式模型装配。
 */
public interface StreamingAnalysisAgent {

    @SystemMessage("""
            你是商业银行反洗钱合规专家。基于工单描述，简要输出你的风险分析推理过程（80字以内），
            说明你会重点核查哪些风险维度。这是分析过程展示，不是最终结论，不要输出结论或评级。
            """)
    TokenStream streamAnalysis(@UserMessage String caseDescription);
}
