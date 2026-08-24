package com.bank.aml.assistant.guard;

import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.security.PromptInjectionGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** 在加载客户快照和调用主模型之前完成范围、越权、写意图和敏感信息判断。 */
@Component
public class AssistantInputGuard {
    private static final Pattern CUSTOMER_IDENTIFIER = Pattern.compile("(?i)(?<![A-Z0-9])C(?:-?[A-Z0-9]{3,})(?![A-Z0-9])");
    private static final Pattern WRITE_REQUEST = Pattern.compile(
            "(?:(帮我|请|立即|现在|给我|替我|需要你|执行|假装|模拟).{0,12})?(修改|更新|删除|冻结|解冻|转账|汇款|提交|审批|通过|驳回|创建|关闭|销户|调整).{0,20}(客户|账户|风险|工单|审核|资金|等级|状态|成功)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_REQUEST = Pattern.compile(
            "(完整|全部|明文|真实).{0,8}(身份证|证件号|银行卡|卡号|账号|账户号|密钥|token|jwt)|"
                    + "(身份证|证件号|银行卡|卡号|账号|账户号|密钥|token|jwt).{0,8}(完整|全部|明文|真实)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CROSS_CUSTOMER_REFERENCE = Pattern.compile(
            "(上一个|前一个|另一个|其他|别的|两个|多个|所有).{0,5}(客户|用户)|"
                    + "(客户|用户).{0,5}(比较|对比|一起)", Pattern.CASE_INSENSITIVE);
    private static final List<String> CUSTOMER_TERMS = List.of(
            "当前客户", "该客户", "这个客户", "交易", "股权", "受益所有人", "ubo", "制裁", "风险", "异常", "画像", "资金来源", "跨境", "夜间", "大额");
    private static final List<String> BANKING_TERMS = List.of(
            "银行", "金融", "反洗钱", "aml", "kyc", "尽职调查", "存款", "贷款", "利率", "汇率", "银行卡", "账户", "支付", "征信", "监管", "可疑交易", "大额交易", "受益所有人", "制裁");
    private static final List<String> EXPLICIT_CURRENT_CUSTOMER_TERMS = List.of("当前客户", "该客户", "这个客户");
    private static final Pattern CONCEPT_QUESTION = Pattern.compile(
            "(什么是|是什么意思|如何理解|为什么|有何意义|有什么关系|等于|能否|能由|能.{0,8}吗|需要哪些|为何|怎么识别|如何识别)",
            Pattern.CASE_INSENSITIVE);

    private final SensitiveDataDetector sensitiveData;
    private final PromptInjectionGuard injectionGuard;

    public AssistantInputGuard(SensitiveDataDetector sensitiveData, PromptInjectionGuard injectionGuard) {
        this.sensitiveData = sensitiveData;
        this.injectionGuard = injectionGuard;
    }

    public InputDecision inspect(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.isEmpty()) return clarify("请补充需要分析的银行金融问题。");

        if (sensitiveData.containsSensitiveData(normalized) || SENSITIVE_REQUEST.matcher(normalized).find()) {
            return denied(AssistantIntent.SENSITIVE_DATA_REQUEST, AssistantResultType.SENSITIVE_DATA_DENIED,
                    sensitiveData.redact(normalized), "请勿在对话中输入或索取完整身份证、账号、银行卡或密钥信息。");
        }
        if (WRITE_REQUEST.matcher(normalized).find()) {
            return denied(AssistantIntent.WRITE_REQUEST, AssistantResultType.WRITE_NOT_ALLOWED,
                    normalized, "AI 小助仅提供只读分析，不能修改客户、风险、工单或账户状态。");
        }
        if (CUSTOMER_IDENTIFIER.matcher(normalized).find() || CROSS_CUSTOMER_REFERENCE.matcher(normalized).find()) {
            return denied(AssistantIntent.CROSS_CUSTOMER_REQUEST, AssistantResultType.CROSS_CUSTOMER_DENIED,
                    normalized, "AI 小助只能分析当前页面绑定的客户，不能在对话中切换客户。");
        }
        if (injectionGuard.scan(normalized).suspicious()) {
            return denied(AssistantIntent.PROMPT_INJECTION, AssistantResultType.OUT_OF_SCOPE,
                    normalized, "该请求包含无法执行的指令，请直接提出当前客户或银行金融问题。");
        }
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (containsAny(lower, EXPLICIT_CURRENT_CUSTOMER_TERMS)) {
            return allowed(AssistantIntent.CUSTOMER_ANALYSIS, normalized);
        }
        if (containsAny(lower, BANKING_TERMS) && (CONCEPT_QUESTION.matcher(lower).find()
                || lower.contains("银行") || lower.contains("金融机构") || lower.contains("aml")
                || lower.contains("kyc") || lower.contains("反洗钱") || lower.contains("存款保险"))) {
            return allowed(AssistantIntent.BANKING_KNOWLEDGE, normalized);
        }
        if (containsAny(lower, CUSTOMER_TERMS)) return allowed(AssistantIntent.CUSTOMER_ANALYSIS, normalized);
        if (containsAny(lower, BANKING_TERMS)) return allowed(AssistantIntent.BANKING_KNOWLEDGE, normalized);
        if (normalized.length() <= 8 && (normalized.contains("为什么") || normalized.contains("怎么看")
                || normalized.contains("什么意思"))) {
            return clarify("请说明你想了解当前客户的哪项风险或银行金融概念。");
        }
        return denied(AssistantIntent.OUT_OF_SCOPE, AssistantResultType.OUT_OF_SCOPE,
                normalized, "AI 小助仅回答当前客户及银行金融、AML、KYC 相关问题。");
    }

    private boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private InputDecision allowed(AssistantIntent intent, String sanitized) {
        return new InputDecision(intent, true, sanitized, null, null);
    }

    private InputDecision clarify(String response) {
        return new InputDecision(AssistantIntent.AMBIGUOUS, false, "", AssistantResultType.CLARIFICATION_REQUIRED, response);
    }

    private InputDecision denied(AssistantIntent intent, AssistantResultType resultType,
                                 String sanitized, String response) {
        return new InputDecision(intent, false, sanitized, resultType, response);
    }

    public record InputDecision(AssistantIntent intent, boolean allowed, String sanitizedInput,
                                AssistantResultType resultType, String response) {}
}
