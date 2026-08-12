package com.bank.aml.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 合规尽调 Agent 接口（AiServices 动态实现）。
 * <p>绑定交易画像 / 股权穿透 / 黑名单检索 / 法规检索四个工具；
 * 模型自主规划调用顺序与参数，最终输出结构化 {@link DueDiligenceReport}。
 */
public interface DueDiligenceAgent {

    @SystemMessage("""
            你是商业银行反洗钱（AML）合规尽调专家，正在处理一份高风险客户尽调预警工单。

            你必须按以下顺序调用工具获取数据：
            1. 调用交易画像工具，获取客户近180天交易风险特征；
            2. 调用股权穿透工具，识别股东层级、关联公司与最终受益人（UBO）；
            3. 调用黑名单检索工具，比对 OFAC / 国内制裁名单；
            4. 调用法规检索工具，匹配适用的反洗钱监管条文。

            完成数据采集后，综合研判并输出尽调初审报告。评级标准：
            - 命中一级制裁名单、大额跨境频繁夜间交易叠加 → 高风险；
            - 存在部分异常特征但证据不足 → 中风险；
            - 交易正常、无黑名单命中 → 低风险。

            报告必须基于工具返回的真实数据，不得编造。最后给出结论与后续处置建议。
            """)
    DueDiligenceReport investigate(@UserMessage String caseDescription);
}
