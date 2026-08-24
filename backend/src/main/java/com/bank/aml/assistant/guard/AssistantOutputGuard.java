package com.bank.aml.assistant.guard;

import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 最终回答的确定性校验；失败时整条阻断，不让模型自行修复。 */
@Component
public class AssistantOutputGuard {
    private static final Pattern EVIDENCE_ID = Pattern.compile(
            "\\b(?:[A-Z_]+:[a-f0-9]{64}|(?:KB|LEGAL)-[A-Za-z0-9_-]{6,80})\\b");
    private static final Pattern CUSTOMER_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Z0-9])C-?(?=[A-Z0-9]*[0-9])[A-Z0-9]{3,}(?![A-Z0-9])");
    private static final Pattern WRITE_CLAIM = Pattern.compile(
            "(已|已经|成功).{0,8}(修改|更新|删除|冻结|解冻|转账|汇款|提交|审批|创建|关闭|调整)");
    private static final Pattern TRANSACTION_COUNT = Pattern.compile(
            "(?<!大额)(?<!异常)交易(?:笔数)?(?:为|共|：|:)?\\s*(\\d+)\\s*笔");

    private final SensitiveDataDetector sensitiveData;

    public AssistantOutputGuard(SensitiveDataDetector sensitiveData) {
        this.sensitiveData = sensitiveData;
    }

    public OutputValidation validate(CustomerAssistantSnapshot snapshot, String output) {
        List<String> violations = new ArrayList<>();
        String content = output == null ? "" : output.trim();
        if (content.isEmpty()) violations.add("OUTPUT_EMPTY");
        List<String> allowedEvidenceIds = snapshot == null ? List.of()
                : snapshot.evidence().stream().map(item -> item.evidenceId()).toList();
        if (sensitiveData.containsSensitiveData(content, allowedEvidenceIds)) {
            violations.add("SENSITIVE_DATA_LEAKED");
        }
        String identifierCheck = content;
        for (String evidenceId : allowedEvidenceIds) {
            identifierCheck = identifierCheck.replace(evidenceId, "[VERIFIED_EVIDENCE_ID]");
        }
        if (CUSTOMER_IDENTIFIER.matcher(identifierCheck).find()) violations.add("CUSTOMER_IDENTIFIER_LEAKED");
        if (WRITE_CLAIM.matcher(content).find()) violations.add("WRITE_ACTION_CLAIMED");
        Matcher evidence = EVIDENCE_ID.matcher(content);
        while (evidence.find()) {
            if (snapshot == null || !snapshot.ownsEvidence(evidence.group())) {
                violations.add("EVIDENCE_NOT_IN_SNAPSHOT");
            }
        }
        if (snapshot != null) {
            Matcher count = TRANSACTION_COUNT.matcher(content);
            while (count.find()) {
                if (Integer.parseInt(count.group(1)) != snapshot.transactionRisk().transactionCount()) {
                    violations.add("TRANSACTION_COUNT_UNSUPPORTED");
                }
            }
        }
        return new OutputValidation(List.copyOf(new java.util.LinkedHashSet<>(violations)));
    }

    public record OutputValidation(List<String> violations) {
        public boolean valid() { return violations == null || violations.isEmpty(); }
    }
}
