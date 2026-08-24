package com.bank.aml.rag;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.InvestigationSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对高影响处置做确定性的法规支持校验；不使用第二个 LLM 充当硬门禁。 */
@Component
public class LegalEvidenceSupportValidator {
    private static final Pattern EVIDENCE_ID = Pattern.compile("(?i)\\b(?:[A-Z0-9]+-)*LEGAL-[A-Z0-9][A-Z0-9_-]*\\b");

    public List<String> validate(InvestigationSnapshot snapshot, DueDiligenceReport report) {
        if (snapshot == null || report == null || report.actionCodes() == null) return List.of();
        Set<String> cited = new LinkedHashSet<>();
        collect(cited, report.legalBasis());
        collect(cited, report.evidenceChain());
        List<LegalDoc> citedDocs = snapshot.legalEvidence().stream()
                .filter(doc -> cited.contains(doc.evidenceId())).toList();
        List<String> violations = new ArrayList<>();
        require(report.actionCodes().contains("FREEZE_ASSETS"), citedDocs,
                List.of(List.of("冻结"), List.of("恐怖", "制裁")), "FREEZE_ASSETS_LEGAL_SUPPORT_MISSING", violations);
        requireAny(report.actionCodes().contains("REPORT_TO_AUTHORITY"), citedDocs,
                List.of(
                        List.of(List.of("可疑交易"), List.of("报告")),
                        List.of(List.of("制裁", "名单"), List.of("报告", "主管机关"))
                ), "REPORT_TO_AUTHORITY_LEGAL_SUPPORT_MISSING", violations);
        require(report.actionCodes().contains("STOP_FINANCIAL_SERVICE"), citedDocs,
                List.of(List.of("停止", "终止"), List.of("金融服务", "业务关系")),
                "STOP_SERVICE_LEGAL_SUPPORT_MISSING", violations);
        return List.copyOf(violations);
    }

    /** 每个关键词组至少命中一个词，所有组都必须满足。 */
    private void require(boolean actionPresent, List<LegalDoc> docs, List<List<String>> requiredGroups,
                         String violation, List<String> violations) {
        if (!actionPresent) return;
        boolean supported = docs.stream().anyMatch(doc -> {
            String text = value(doc.title()) + value(doc.articleNumber()) + value(doc.content());
            return requiredGroups.stream().allMatch(group -> group.stream().anyMatch(text::contains));
        });
        if (!supported) violations.add(violation);
    }

    /** 至少一组合法依据成立，例如向主管机关报告既可能来自可疑交易义务，也可能来自制裁命中义务。 */
    private void requireAny(boolean actionPresent, List<LegalDoc> docs,
                            List<List<List<String>>> alternatives,
                            String violation, List<String> violations) {
        if (!actionPresent) return;
        boolean supported = alternatives.stream().anyMatch(groups -> docs.stream().anyMatch(doc -> {
            String text = value(doc.title()) + value(doc.articleNumber()) + value(doc.content());
            return groups.stream().allMatch(group -> group.stream().anyMatch(text::contains));
        }));
        if (!supported) violations.add(violation);
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
