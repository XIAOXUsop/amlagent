package com.bank.aml.agent;

import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对模型报告做不改变业务判断的确定性稳定化。
 *
 * <p>法规证据在调用模型前已经由后端检索并冻结。模型偶尔会在结构化输出中遗漏或只在一侧
 * 写入 evidenceId；这里仅把冻结快照中真实存在的证据引用同步到 legalBasis/evidenceChain，
 * 不补风险代码、不修改评级，也不接受模型自造的证据 ID。后续仍由 {@code AgentOutputValidator}
 * 校验证据归属及高影响处置是否得到条文内容支持。</p>
 */
public final class AgentReportStabilizer {

    private static final Pattern EVIDENCE_ID = Pattern.compile(
            "(?i)\\b(?:[A-Z0-9]+-)*LEGAL-[A-Z0-9][A-Z0-9_-]*\\b");

    private AgentReportStabilizer() {
    }

    public static DueDiligenceReport attachFrozenLegalEvidence(
            InvestigationSnapshot snapshot, DueDiligenceReport report
    ) {
        return attachFrozenLegalEvidence(snapshot, report, null);
    }

    /**
     * @param returnedEvidenceIds 法规工具在本次调用中真实返回的 ID；null 仅供已确认暴露完整证据包的适配器使用。
     */
    public static DueDiligenceReport attachFrozenLegalEvidence(
            InvestigationSnapshot snapshot, DueDiligenceReport report, Collection<String> returnedEvidenceIds
    ) {
        if (snapshot == null || report == null || snapshot.legalEvidence().isEmpty()) {
            return report;
        }

        List<String> legalBasis = mutable(report.legalBasis());
        List<String> evidenceChain = mutable(report.evidenceChain());
        Set<String> allowedIds = new LinkedHashSet<>();
        snapshot.legalEvidence().stream()
                .map(LegalDoc::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(allowedIds::add);
        if (returnedEvidenceIds != null) {
            allowedIds.retainAll(new LinkedHashSet<>(returnedEvidenceIds));
        }
        if (allowedIds.isEmpty()) {
            return report;
        }

        // 删除模型自造 ID 由 Validator 负责；这里只同步真实冻结证据，避免掩盖越权引用。
        Set<String> citedAllowed = new LinkedHashSet<>(extractAllowed(legalBasis, allowedIds));
        citedAllowed.addAll(extractAllowed(evidenceChain, allowedIds));
        // 法规工具的完整结果就是本次冻结证据包；即使模型漏抄 ID，后端仍可恢复其来源引用。
        if (citedAllowed.isEmpty()) {
            citedAllowed.addAll(allowedIds);
        }

        for (LegalDoc doc : snapshot.legalEvidence()) {
            if (doc.evidenceId() == null || !citedAllowed.contains(doc.evidenceId())) {
                continue;
            }
            if (!containsId(legalBasis, doc.evidenceId())) {
                legalBasis.add(formatBasis(doc));
            }
            if (!containsId(evidenceChain, doc.evidenceId())) {
                evidenceChain.add("冻结法规证据：" + doc.evidenceId());
            }
        }

        return new DueDiligenceReport(
                report.customerId(), report.customerName(), report.riskLevel(),
                report.transactionProfile(), report.corporateProfile(), report.sanctions(),
                List.copyOf(legalBasis), report.riskPoints(), report.conclusion(),
                List.copyOf(evidenceChain), report.manualReviewRequired(),
                report.findingCodes(), report.actionCodes());
    }

    private static String formatBasis(LegalDoc doc) {
        List<String> parts = new ArrayList<>();
        parts.add(doc.evidenceId());
        if (doc.title() != null && !doc.title().isBlank()) parts.add(doc.title());
        if (doc.articleNumber() != null && !doc.articleNumber().isBlank()) parts.add(doc.articleNumber());
        return "冻结法规依据：" + String.join(" ", parts);
    }

    private static List<String> mutable(List<String> values) {
        return new ArrayList<>(values == null ? List.of() : values);
    }

    private static Set<String> extractAllowed(List<String> values, Set<String> allowedIds) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            Matcher matcher = EVIDENCE_ID.matcher(value);
            while (matcher.find()) {
                if (allowedIds.contains(matcher.group())) result.add(matcher.group());
            }
        }
        return result;
    }

    private static boolean containsId(List<String> values, String id) {
        return extractAllowed(values, Set.of(id)).contains(id);
    }
}
