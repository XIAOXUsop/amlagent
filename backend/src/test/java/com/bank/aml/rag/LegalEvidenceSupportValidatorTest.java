package com.bank.aml.rag;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.InvestigationSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalEvidenceSupportValidatorTest {
    private final LegalEvidenceSupportValidator validator = new LegalEvidenceSupportValidator();

    @Test
    void authorityReportRequiresARealCitedSupportingArticle() {
        LegalDoc law = new LegalDoc("LEGAL-REPORT-1", "金融机构大额交易和可疑交易报告管理办法", "文号", "第十条",
                "金融机构发现可疑交易后，应当及时提交可疑交易报告。");
        InvestigationSnapshot snapshot = mock(InvestigationSnapshot.class);
        when(snapshot.legalEvidence()).thenReturn(List.of(law));
        DueDiligenceReport supported = report(List.of("依据 LEGAL-REPORT-1"), List.of("LEGAL-REPORT-1"));
        DueDiligenceReport uncited = report(List.of("依据相关规定"), List.of("未引用法规"));

        assertThat(validator.validate(snapshot, supported)).isEmpty();
        assertThat(validator.validate(snapshot, uncited)).containsExactly("REPORT_TO_AUTHORITY_LEGAL_SUPPORT_MISSING");
    }

    @Test
    void authorityReportAlsoAcceptsCitedSanctionReportingDuty() {
        LegalDoc law = new LegalDoc("LEGAL-SANCTION-1", "涉及恐怖活动资产冻结管理办法", "文号", "第六条",
                "金融机构确认制裁名单命中后，应当停止相关服务并向主管机关报告。");
        InvestigationSnapshot snapshot = mock(InvestigationSnapshot.class);
        when(snapshot.legalEvidence()).thenReturn(List.of(law));

        assertThat(validator.validate(snapshot,
                report(List.of("依据 LEGAL-SANCTION-1"), List.of("LEGAL-SANCTION-1"))))
                .isEmpty();
    }

    @Test
    void negatedFreezeClauseDoesNotSupportFreezeAction() {
        LegalDoc law = new LegalDoc("LEGAL-FREEZE-1", "涉及恐怖活动资产冻结管理办法", "文号", "第五条",
                "除法律另有规定外，金融机构不得冻结该等资产，应立即报告主管机关。");
        InvestigationSnapshot snapshot = mock(InvestigationSnapshot.class);
        when(snapshot.legalEvidence()).thenReturn(List.of(law));
        DueDiligenceReport report = new DueDiligenceReport("C1", "客户", "高风险", "交易", "主体",
                List.of(), List.of("依据 LEGAL-FREEZE-1"), List.of("风险"), "冻结", List.of("LEGAL-FREEZE-1"),
                true, List.of("SANCTION_LEVEL_1_MATCH"), List.of("FREEZE_ASSETS", "MANUAL_REVIEW"));

        // “不得冻结”条款不能被当作支持冻结的依据
        assertThat(validator.validate(snapshot, report))
                .containsExactly("FREEZE_ASSETS_LEGAL_SUPPORT_MISSING");
    }

    @Test
    void freezeActionIsSupportedByConstructiveFreezeClause() {
        LegalDoc law = new LegalDoc("LEGAL-FREEZE-2", "涉及恐怖活动资产冻结管理办法", "文号", "第二条",
                "金融机构发现客户属于恐怖活动组织及恐怖活动人员名单的，应当立即对相关资产采取冻结措施。");
        InvestigationSnapshot snapshot = mock(InvestigationSnapshot.class);
        when(snapshot.legalEvidence()).thenReturn(List.of(law));
        DueDiligenceReport report = new DueDiligenceReport("C1", "客户", "高风险", "交易", "主体",
                List.of(), List.of("依据 LEGAL-FREEZE-2"), List.of("风险"), "冻结", List.of("LEGAL-FREEZE-2"),
                true, List.of("SANCTION_LEVEL_1_MATCH"), List.of("FREEZE_ASSETS", "MANUAL_REVIEW"));

        assertThat(validator.validate(snapshot, report)).isEmpty();
    }

    private DueDiligenceReport report(List<String> basis, List<String> evidence) {
        return new DueDiligenceReport("C1", "客户", "高风险", "交易", "主体", List.of(), basis,
                List.of("风险"), "转主管机关", evidence, true,
                List.of("RISK_ASSESSMENT_UNCERTAIN"), List.of("REPORT_TO_AUTHORITY", "MANUAL_REVIEW"));
    }
}