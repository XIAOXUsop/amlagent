package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic validator for the Agent's structured report. */
@Component
public class AgentEvalSchemaValidator {

    private static final Set<String> RISK_LEVELS = Set.of("低风险", "中风险", "高风险");
    private static final int MAX_TEXT_LENGTH = 8_000;
    private static final int MAX_LIST_SIZE = 50;

    public List<String> validate(AgentEvalDataset.AgentEvalCase evalCase, DueDiligenceReport report) {
        List<String> violations = new ArrayList<>();
        if (report == null) {
            return List.of("REPORT_NULL");
        }

        if (!evalCase.input().customerId().equals(report.customerId())) {
            violations.add("CUSTOMER_ID_MISMATCH");
        }
        if (!normalize(evalCase.input().customerName()).equals(normalize(report.customerName()))) {
            violations.add("CUSTOMER_NAME_MISMATCH");
        }
        if (!RISK_LEVELS.contains(report.riskLevel())) {
            violations.add("RISK_LEVEL_INVALID");
        }
        requireText(report.transactionProfile(), "TRANSACTION_PROFILE", violations);
        requireText(report.corporateProfile(), "CORPORATE_PROFILE", violations);
        requireText(report.conclusion(), "CONCLUSION", violations);
        requireList(report.sanctions(), "SANCTIONS", false, null, violations);
        requireList(report.legalBasis(), "LEGAL_BASIS", true, null, violations);
        requireList(report.riskPoints(), "RISK_POINTS", true, null, violations);
        requireList(report.evidenceChain(), "EVIDENCE_CHAIN", true, null, violations);

        if (report.manualReviewRequired() == null) {
            violations.add("MANUAL_REVIEW_REQUIRED_NULL");
        }
        requireList(report.findingCodes(), "FINDING_CODES", true,
                AgentEvalVocabulary.FINDING_CODES, violations);
        requireList(report.actionCodes(), "ACTION_CODES", true,
                AgentEvalVocabulary.ACTION_CODES, violations);
        if (report.manualReviewRequired() != null && report.actionCodes() != null) {
            boolean hasManualAction = report.actionCodes().contains("MANUAL_REVIEW");
            if (report.manualReviewRequired() != hasManualAction) {
                violations.add("MANUAL_REVIEW_ACTION_INCONSISTENT");
            }
        }
        return List.copyOf(violations);
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
        if (vocabulary != null && !vocabulary.containsAll(values)) {
            violations.add(code + "_OUT_OF_VOCABULARY");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }
}
