package com.bank.aml.assistant.guard;

import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 法律义务、禁止、许可、期限或处罚结论；命中时必须同时提交结构化 claims。 */
    private static final Pattern LEGAL_CONCLUSION = Pattern.compile(
            "(?:应当|必须|不得|禁止|可以|有权|无权|应予|应在|须在|需要|需在|期限为|至少保存|处以).{0,48}"
                    + "(?:报告|报送|保存|冻结|解冻|识别|核实|尽职调查|处罚|罚款|履行|采取措施|工作日|年|日)"
                    + "|(?:报告|报送|保存|冻结|解冻|识别|核实|尽职调查|处罚|罚款|法定期限).{0,48}"
                    + "(?:应当|必须|不得|禁止|可以|有权|无权|应在|须在|需要|至少|以内)");

    private final SensitiveDataDetector sensitiveData;
    private final ClaimCitationValidator claimValidator;

    public AssistantOutputGuard(SensitiveDataDetector sensitiveData) {
        this(sensitiveData, new ClaimCitationValidator());
    }

    @Autowired
    public AssistantOutputGuard(SensitiveDataDetector sensitiveData, ClaimCitationValidator claimValidator) {
        this.sensitiveData = sensitiveData;
        this.claimValidator = claimValidator;
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
            validateClaims(snapshot, content, violations);
        }
        return new OutputValidation(List.copyOf(new java.util.LinkedHashSet<>(violations)));
    }

    /** claim 级引用校验：解析模型输出中 ```json claims 块并逐条核验。 */
    private void validateClaims(CustomerAssistantSnapshot snapshot, String content, List<String> violations) {
        List<AssistantClaim> claims = parseClaims(content);
        if (claims == null) {
            violations.add("CLAIM_MANIFEST_MALFORMED");
            return;
        }
        boolean containsLegalConclusion = LEGAL_CONCLUSION.matcher(stripFencedJson(content)).find();
        if (claims.isEmpty()) {
            if (containsLegalConclusion) {
                violations.add("LEGAL_CLAIM_MANIFEST_REQUIRED");
            }
            return;
        }
        // 任意 CUSTOMER_FACT/GENERAL_KNOWLEDGE claim 不能充当法律声明的占位符。
        if (containsLegalConclusion && claims.stream().noneMatch(AssistantClaim::legalRequirement)) {
            violations.add("LEGAL_CLAIM_TYPE_REQUIRED");
        }
        violations.addAll(claimValidator.validate(snapshot, claims));
    }

    /** 尝试解析 fenced JSON 块中的 claims；未提供 returns {@code List.of()}，存在但损坏 returns {@code null}。 */
    static List<AssistantClaim> parseClaims(String content) {
        if (content == null || content.isBlank()) return List.of();
        try {
            Matcher matcher = CLAIM_BLOCK.matcher(content);
            if (!matcher.find()) return List.of();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree("{" + matcher.group(1) + "}");
            JsonNode claimsNode = root.path("claims");
            if (!claimsNode.isArray()) return null;
            List<AssistantClaim> claims = new ArrayList<>();
            for (JsonNode node : claimsNode) {
                claims.add(mapper.treeToValue(node, AssistantClaim.class));
            }
            return claims;
        } catch (Exception malformed) {
            return null;
        }
    }

    private static final Pattern CLAIM_BLOCK =
            Pattern.compile("```(?:json)?\\s*\\{([\\s\\S]*?)\\}\\s*```", Pattern.CASE_INSENSITIVE);

    private static String stripFencedJson(String content) {
        return content == null ? "" : CLAIM_BLOCK.matcher(content).replaceAll("");
    }

    public record OutputValidation(List<String> violations) {
        public boolean valid() { return violations == null || violations.isEmpty(); }
    }
}
