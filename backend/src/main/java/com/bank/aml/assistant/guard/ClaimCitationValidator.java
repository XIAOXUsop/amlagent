package com.bank.aml.assistant.guard;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对结构化声明逐条做确定性引用校验：
 * <ul>
 *   <li>法律声明必须引用快照内的法律证据；</li>
 *   <li>支持片段必须是证据原文连续子串（保证完整上下文）；</li>
 *   <li>数字/期限/金额必须有直接原文依据；</li>
 *   <li>“不得/可以/应当”模态不得与证据矛盾；</li>
 *   <li>高风险操作需要更高支持（多条法律证据佐证）。</li>
 * </ul>
 * 失效法规由检索层排除（过期检索即拒答），本校验确保引用必然落在快照内。
 */
@Component
public class ClaimCitationValidator {

    private static final int MIN_SPAN_LENGTH = 6;
    private static final List<String> HIGH_RISK_TERMS =
            List.of("冻结", "制裁", "可疑交易", "恐怖活动", "名单命中", "立即上报", "停止服务");

    public List<String> validate(CustomerAssistantSnapshot snapshot, List<AssistantClaim> claims) {
        if (snapshot == null || claims == null || claims.isEmpty()) return List.of();
        List<String> violations = new ArrayList<>();
        Map<String, AssistantEvidence> evidenceById = snapshot.evidence().stream()
                .collect(Collectors.toMap(AssistantEvidence::evidenceId, item -> item, (a, b) -> a));
        for (AssistantClaim claim : claims) {
            if (claim.claimId().isBlank()) violations.add("CLAIM_ID_MISSING:?");
            if (claim.text().isBlank()) violations.add("CLAIM_TEXT_MISSING:" + safe(claim.claimId()));
            List<AssistantEvidence> citedLegal = new ArrayList<>();
            List<String> citedText = new ArrayList<>();
            for (String evidenceId : claim.evidenceIds()) {
                AssistantEvidence evidence = evidenceById.get(evidenceId);
                if (evidence == null) {
                    violations.add("CLAIM_EVIDENCE_NOT_IN_SNAPSHOT:" + safe(claim.claimId()));
                    continue;
                }
                if (evidence.type() == AssistantEvidence.EvidenceType.AML_LEGAL) citedLegal.add(evidence);
                citedText.add(evidence.title() + evidence.summary());
            }
            if (claim.legalRequirement()) {
                if (citedLegal.isEmpty() || claim.evidenceIds().isEmpty()) {
                    violations.add("CLAIM_LEGAL_WITHOUT_EVIDENCE:" + safe(claim.claimId()));
                }
                if (claim.supportSpans().isEmpty()) {
                    violations.add("CLAIM_LEGAL_WITHOUT_SUPPORT_SPAN:" + safe(claim.claimId()));
                }
            }
            String joinedEvidenceText = String.join("\n", citedText);
            for (String span : claim.supportSpans()) {
                String normalizedSpan = span == null ? "" : span.trim();
                if (normalizedSpan.length() < MIN_SPAN_LENGTH || !joinedEvidenceText.contains(normalizedSpan)) {
                    violations.add("CLAIM_SPAN_NOT_SUPPORTED:" + safe(claim.claimId()) + ":" + abbreviate(normalizedSpan));
                }
            }
            for (String numberToken : numberTokens(claim.text())) {
                if (!joinedEvidenceText.contains(numberToken)) {
                    violations.add("CLAIM_NUMBER_NOT_IN_EVIDENCE:" + safe(claim.claimId()) + ":" + numberToken);
                }
            }
            if (claim.legalRequirement() && !citedLegal.isEmpty()) {
                if (!modalityConsistent(claim.text(), citedLegal)) {
                    violations.add("CLAIM_MODALITY_MISMATCH:" + safe(claim.claimId()));
                }
                if (isHighRisk(claim.text()) && citedLegal.size() < 2) {
                    violations.add("HIGH_RISK_CLAIM_UNDERSUPPORTED:" + safe(claim.claimId()));
                }
            }
        }
        return List.copyOf(violations);
    }

    /** 声明模态（不得/应当/可以）必须与所引法律证据原文的模态一致，不允许互相矛盾。 */
    private boolean modalityConsistent(String claimText, List<AssistantEvidence> citedLegal) {
        boolean claimNegative = containsAny(claimText, "不得", "禁止");
        boolean claimMandatory = containsAny(claimText, "应当", "必须");
        boolean claimPermissive = containsAny(claimText, "可以");
        for (AssistantEvidence evidence : citedLegal) {
            String text = evidence.title() + evidence.summary();
            if (claimNegative && !containsAny(text, "不得", "禁止")) return false;
            if (claimMandatory && !containsAny(text, "应当", "必须", "立即")) return false;
            if (claimPermissive && !containsAny(text, "可以")) return false;
        }
        return true;
    }

    private boolean isHighRisk(String text) {
        return containsAny(text, HIGH_RISK_TERMS.toArray(new String[0]));
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    /** 抽取声明中的数字/期限/金额记号，要求能在证据原文中找到直接依据。 */
    List<String> numberTokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher ascii = java.util.regex.Pattern
                .compile("[0-9]+(?:\\.[0-9]+)?(?:万元|万美元|元|欧元|年|个月|个工作日|个交易日|%|倍|日|天)")
                .matcher(text);
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("[一二三四五六七八九十百千万]+(?:万元|元|年|个工作日)")
                .matcher(text);
        while (ascii.find()) tokens.add(ascii.group());
        while (chinese.find()) tokens.add(chinese.group());
        return tokens.stream().distinct().toList();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String abbreviate(String value) {
        return value.length() <= 20 ? value : value.substring(0, 20) + "…";
    }
}
