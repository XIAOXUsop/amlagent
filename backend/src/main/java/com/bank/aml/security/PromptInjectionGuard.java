package com.bank.aml.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测（代码层确定性防护）。
 * <p>对用户可控输入（如预警规则描述）做规则扫描，检测常见的提示注入模式。
 * 与 prompt 层隔离指令、Guardrails 输出兜底共同构成三层防护：
 * 代码检测（确定性）+ prompt 隔离 + 规则兜底。
 */
@Component
public class PromptInjectionGuard {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略.{0,12}(之前|以上|前面|上述)?的?(指令|要求|规则|系统)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore.{0,12}(previous|above|prior)?\\s*(instructions|prompt|rules|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(系统提示|system\\s*prompt|你现在是|现在你是|你是一个|扮演)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(无视|不要遵守|违背|disregard|override|bypass)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(泄露|输出|展示).{0,12}(提示词|prompt|指令)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(把|将)?(所有人|全部|该客户).{0,10}(评为|标记为|判定为).{0,6}(低风险|安全|正常)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出.*原始.*指令|打印.*系统|reveal.*prompt)", Pattern.CASE_INSENSITIVE)
    );

    public record InjectionResult(boolean suspicious, List<String> matchedPatterns) {
        public static InjectionResult clean() {
            return new InjectionResult(false, List.of());
        }
    }

    /** 扫描输入，返回是否疑似注入及命中的模式描述 */
    public InjectionResult scan(String input) {
        if (input == null || input.isBlank()) {
            return InjectionResult.clean();
        }
        List<String> matched = new ArrayList<>();
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                matched.add(pattern.pattern());
            }
        }
        return new InjectionResult(!matched.isEmpty(), matched);
    }
}
