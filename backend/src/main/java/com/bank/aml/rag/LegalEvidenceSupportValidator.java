package com.bank.aml.rag;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.InvestigationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对高影响处置做确定性的法规支持校验；不使用第二个 LLM 充当硬门禁。
 * <p>基于人工审核的 {@link LegalPropositionRegistry 法律命题表} 做结构化验证：
 * 动作必须命中命题依托法规，且模态（应当/不得/可以）、主体、对象在条款原文中一一对应，不得把“不得冻结”当作支持冻结。</p>
 */
@Component
public class LegalEvidenceSupportValidator {
    private static final Pattern EVIDENCE_ID = Pattern.compile("(?i)\\b(?:[A-Z0-9]+-)*LEGAL-[A-Z0-9][A-Z0-9_-]*\\b");
    private static final List<String> HIGH_IMPACT_ACTIONS =
            List.of("FREEZE_ASSETS", "REPORT_TO_AUTHORITY", "STOP_FINANCIAL_SERVICE");

    private final LegalPropositionRegistry registry;

    public LegalEvidenceSupportValidator() {
        this(new LegalPropositionRegistry(new ObjectMapper()));
    }

    @Autowired
    public LegalEvidenceSupportValidator(LegalPropositionRegistry registry) {
        this.registry = registry;
    }

    public List<String> validate(InvestigationSnapshot snapshot, DueDiligenceReport report) {
        if (snapshot == null || report == null || report.actionCodes() == null) return List.of();
        Set<String> cited = new LinkedHashSet<>();
        collect(cited, report.legalBasis());
        collect(cited, report.evidenceChain());
        List<LegalDoc> citedDocs = snapshot.legalEvidence().stream()
                .filter(doc -> cited.contains(doc.evidenceId())).toList();
        List<String> violations = new ArrayList<>();
        for (String actionCode : HIGH_IMPACT_ACTIONS) {
            if (!report.actionCodes().contains(actionCode)) continue;
            List<LegalActionProposition> propositions = registry.findByCode(actionCode);
            if (propositions.isEmpty()) {
                violations.add(actionCode + "_NO_LEGAL_PROPOSITION");
                continue;
            }
            boolean supported = propositions.stream().anyMatch(proposition ->
                    citedDocs.stream().anyMatch(doc -> supportedBy(doc, proposition)));
            if (!supported) {
                violations.add(actionCode + "_LEGAL_SUPPORT_MISSING");
            }
        }
        return List.copyOf(violations);
    }

    /** 结构化命题验证：依托法规 + 动作词 + 主体 + 模态一致性（不得把否定条款误判为支持）。 */
    private boolean supportedBy(LegalDoc doc, LegalActionProposition proposition) {
        if (doc == null) return false;
        String text = value(doc.title()) + value(doc.articleNumber()) + value(doc.content());
        boolean authorityMatched;
        if (proposition.evidenceId() != null && !proposition.evidenceId().isBlank()) {
            authorityMatched = proposition.evidenceId().equals(doc.evidenceId());
        } else {
            authorityMatched = !proposition.evidenceTitle().isBlank() && text.contains(proposition.evidenceTitle());
        }
        if (!authorityMatched) return false;
        boolean actionMatched = proposition.actionTerms().stream().anyMatch(text::contains);
        if (!actionMatched) return false;
        // 模态一致性：不能把“不得冻结/禁止冻结”当作支持“冻结”
        if ("FREEZE_ASSETS".equals(proposition.actionCode())
                && (text.contains("不得冻结") || text.contains("禁止冻结"))) {
            return false;
        }
        // 豁免条款出现且包含豁免关键词时不算积极支持
        boolean modalityMatched = proposition.modalityTerms().stream().anyMatch(text::contains);
        return modalityMatched;
    }

    private void collect(Set<String> ids, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            Matcher matcher = EVIDENCE_ID.matcher(value);
            while (matcher.find()) ids.add(matcher.group());
        }
    }

    private String value(String value) { return value == null ? "" : value; }
}