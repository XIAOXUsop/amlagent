package com.bank.aml.agent.validation;

import com.bank.aml.agent.AgentReportVocabulary;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalEvidenceSupportValidator;
import com.bank.aml.risk.RiskContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产 Agent 输出契约校验器。
 * <p>所有检查均为确定性检查：结构、闭集词表、人工复核一致性、法规证据归属和关键事实前置条件。
 * 该组件不自动吞掉或改写模型错误，调用方必须显式决定重试、降级或转人工。
 */
@Component
public class AgentOutputValidator {

    private static final int MAX_TEXT_LENGTH = 8_000;
    private static final int MAX_LIST_SIZE = 50;
    private static final Pattern LEGAL_EVIDENCE_ID = Pattern.compile(
            "(?i)\\b(?:[A-Z0-9]+-)*LEGAL-[A-Z0-9][A-Z0-9_-]*\\b");
    private final LegalEvidenceSupportValidator evidenceSupportValidator;

    public AgentOutputValidator() {
        this(new LegalEvidenceSupportValidator());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentOutputValidator(LegalEvidenceSupportValidator evidenceSupportValidator) {
        this.evidenceSupportValidator = evidenceSupportValidator;
    }

    public ValidationResult validate(InvestigationSnapshot snapshot, DueDiligenceReport report) {
        List<String> violations = new ArrayList<>();
        if (report == null) {
            return new ValidationResult(List.of("REPORT_NULL"));
        }

        if (!AgentReportVocabulary.RISK_LEVELS.contains(report.riskLevel())) {
            violations.add("RISK_LEVEL_INVALID");
        }
        requireText(report.transactionProfile(), "TRANSACTION_PROFILE", violations);
        requireText(report.corporateProfile(), "CORPORATE_PROFILE", violations);
        requireText(report.conclusion(), "CONCLUSION", violations);
        requireList(report.sanctions(), "SANCTIONS", false, null, violations);
        requireList(report.legalBasis(), "LEGAL_BASIS", true, null, violations);
        requireList(report.riskPoints(), "RISK_POINTS", true, null, violations);
        requireList(report.evidenceChain(), "EVIDENCE_CHAIN", true, null, violations);
        requireList(report.findingCodes(), "FINDING_CODES", true,
                AgentReportVocabulary.FINDING_CODES, violations);
        requireList(report.actionCodes(), "ACTION_CODES", true,
                AgentReportVocabulary.ACTION_CODES, violations);

        validateManualReview(report, violations);
        if (snapshot != null) {
            validateIdentityLeakage(snapshot, report, violations);
            validateLegalEvidence(snapshot, report, violations);
            validateFactPrerequisites(snapshot.riskFacts(), report, violations);
            violations.addAll(evidenceSupportValidator.validate(snapshot, report));
        }
        return new ValidationResult(List.copyOf(new LinkedHashSet<>(violations)));
    }

    /**
     * DTO 无身份字段仍不足以阻止模型在自由文本中复述身份；只返回通用违规码，避免日志二次泄露。
     */
    private void validateIdentityLeakage(InvestigationSnapshot snapshot, DueDiligenceReport report,
                                         List<String> violations) {
        if (snapshot.customer() == null) {
            return;
        }
        String content = String.join("\n", reportContent(report)).toLowerCase(Locale.ROOT);
        String customerId = lower(snapshot.customer().id());
        String customerName = lower(snapshot.customer().name());
        String idCard = lower(snapshot.customer().idCard());
        boolean leaked = containsIdentifier(content, customerId)
                || (!customerName.isBlank() && content.contains(customerName))
                || containsIdentifier(content, idCard);
        if (leaked) {
            violations.add("IDENTITY_DATA_LEAKED");
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 编号必须以完整 token 出现，避免把 ID-1 误判为 LEGAL-VALID-1 的泄露。 */
    private boolean containsIdentifier(String content, String identifier) {
        if (identifier.isBlank()) return false;
        int from = 0;
        while (from <= content.length() - identifier.length()) {
            int index = content.indexOf(identifier, from);
            if (index < 0) return false;
            int end = index + identifier.length();
            boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(content.charAt(index - 1));
            boolean rightBoundary = end == content.length() || !Character.isLetterOrDigit(content.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from = index + 1;
        }
        return false;
    }

    private List<String> reportContent(DueDiligenceReport report) {
        List<String> values = new ArrayList<>();
        add(values, report.transactionProfile());
        add(values, report.corporateProfile());
        add(values, report.conclusion());
        addAll(values, report.sanctions());
        addAll(values, report.legalBasis());
        addAll(values, report.riskPoints());
        addAll(values, report.evidenceChain());
        return values;
    }

    private void add(List<String> values, String value) {
        if (value != null) values.add(value);
    }

    private void addAll(List<String> values, List<String> additions) {
        if (additions != null) additions.stream().filter(java.util.Objects::nonNull).forEach(values::add);
    }

    private void validateManualReview(DueDiligenceReport report, List<String> violations) {
        if (report.manualReviewRequired() == null) {
            violations.add("MANUAL_REVIEW_REQUIRED_NULL");
            return;
        }
        if (report.actionCodes() != null) {
            boolean hasManualAction = report.actionCodes().contains("MANUAL_REVIEW");
            if (report.manualReviewRequired() != hasManualAction) {
                violations.add("MANUAL_REVIEW_ACTION_INCONSISTENT");
            }
        }
    }

    private void validateLegalEvidence(InvestigationSnapshot snapshot, DueDiligenceReport report,
                                       List<String> violations) {
        Set<String> allowed = snapshot.legalEvidence().stream()
                .map(LegalDoc::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> legalBasisIds = extractEvidenceIds(report.legalBasis());
        Set<String> evidenceChainIds = extractEvidenceIds(report.evidenceChain());

        Set<String> referenced = new LinkedHashSet<>(legalBasisIds);
        referenced.addAll(evidenceChainIds);
        referenced.stream()
                .filter(id -> !allowed.contains(id))
                .forEach(id -> violations.add("EVIDENCE_ID_NOT_IN_SNAPSHOT:" + id));

        if (!allowed.isEmpty()) {
            Set<String> allowedInBasis = intersection(legalBasisIds, allowed);
            Set<String> allowedInChain = intersection(evidenceChainIds, allowed);
            if (allowedInBasis.isEmpty() || allowedInChain.isEmpty()) {
                violations.add("LEGAL_EVIDENCE_ID_MISSING");
            } else if (!allowedInBasis.equals(allowedInChain)) {
                violations.add("LEGAL_EVIDENCE_CHAIN_MISMATCH");
            }
        }
    }

    private void validateFactPrerequisites(RiskContext facts, DueDiligenceReport report,
                                           List<String> violations) {
        if (facts == null || report.findingCodes() == null) {
            return;
        }
        Set<String> findings = new HashSet<>(report.findingCodes());
        Set<String> actions = report.actionCodes() == null ? Set.of() : new HashSet<>(report.actionCodes());

        unsupported(findings.contains("NO_SANCTION_HIT") && facts.sanctionHit(),
                "NO_SANCTION_HIT_UNSUPPORTED", violations);
        unsupported(findings.contains("SANCTION_LEVEL_1_MATCH") && facts.maxSeverity() != 1,
                "SANCTION_LEVEL_1_MATCH_UNSUPPORTED", violations);
        unsupported(findings.contains("DOMESTIC_WATCHLIST_MATCH")
                        && (!facts.sanctionHit() || facts.maxSeverity() < 2),
                "DOMESTIC_WATCHLIST_MATCH_UNSUPPORTED", violations);
        unsupported(findings.contains("TRANSACTION_DATA_UNAVAILABLE") && facts.transactionDataComplete(),
                "TRANSACTION_DATA_UNAVAILABLE_UNSUPPORTED", violations);
        unsupported(findings.contains("UBO_UNVERIFIED") && facts.uboRiskSeverity() < 2,
                "UBO_UNVERIFIED_UNSUPPORTED", violations);
        unsupported(findings.contains("UBO_DOCUMENTS_INCOMPLETE") && facts.uboRiskSeverity() < 1,
                "UBO_DOCUMENTS_INCOMPLETE_UNSUPPORTED", violations);
        unsupported(findings.contains("CROSS_BORDER_ACTIVITY") && facts.crossRatio() <= 0,
                "CROSS_BORDER_ACTIVITY_UNSUPPORTED", violations);
        unsupported((findings.contains("STRUCTURING_PATTERN") || findings.contains("LAYERING_PATTERN"))
                        && facts.transactionPatternSeverity() < 2,
                "HIGH_RISK_TRANSACTION_PATTERN_UNSUPPORTED", violations);
        unsupported(actions.contains("FREEZE_ASSETS") && facts.maxSeverity() != 1,
                "FREEZE_ASSETS_UNSUPPORTED", violations);
        boolean suspiciousFacts = facts.maxSeverity() == 1 || facts.transactionPatternSeverity() >= 2
                || findings.contains("RISK_ASSESSMENT_UNCERTAIN")
                || findings.contains("TRANSACTION_PURPOSE_UNVERIFIED")
                || findings.contains("SOURCE_OF_FUNDS_UNVERIFIED");
        unsupported(actions.contains("REPORT_TO_AUTHORITY") && !suspiciousFacts,
                "REPORT_TO_AUTHORITY_UNSUPPORTED", violations);
    }

    private void requireText(String value, String code, List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(code + "_BLANK");
        } else if (value.length() > MAX_TEXT_LENGTH) {
            violations.add(code + "_TOO_LONG");
        }
    }

    private void requireList(List<String> values, String code, boolean nonEmpty,
                             Set<String> vocabulary, List<String> violations) {
        if (values == null) {
            violations.add(code + "_NULL");
            return;
        }
        if (nonEmpty && values.isEmpty()) {
            violations.add(code + "_EMPTY");
        }
        if (values.size() > MAX_LIST_SIZE) {
            violations.add(code + "_TOO_LARGE");
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            violations.add(code + "_HAS_BLANK");
        }
        if (new HashSet<>(values).size() != values.size()) {
            violations.add(code + "_HAS_DUPLICATE");
        }
        if (values.stream().filter(value -> value != null).anyMatch(value -> value.length() > MAX_TEXT_LENGTH)) {
            violations.add(code + "_ITEM_TOO_LONG");
        }
        if (vocabulary != null && values.stream().filter(java.util.Objects::nonNull)
                .anyMatch(value -> !vocabulary.contains(value))) {
            violations.add(code + "_OUT_OF_VOCABULARY");
        }
    }

    private Set<String> extractEvidenceIds(List<String> values) {
        Set<String> ids = new LinkedHashSet<>();
        if (values == null) {
            return ids;
        }
        for (String value : values) {
            if (value == null) continue;
            Matcher matcher = LEGAL_EVIDENCE_ID.matcher(value);
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private void unsupported(boolean condition, String code, List<String> violations) {
        if (condition) {
            violations.add(code);
        }
    }

    public record ValidationResult(List<String> violations) {
        public ValidationResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public boolean valid() {
            return violations.isEmpty();
        }
    }
}
