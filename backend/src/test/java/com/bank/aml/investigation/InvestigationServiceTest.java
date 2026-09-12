package com.bank.aml.investigation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.ReviewDecision;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigationServiceTest {

    private final CaseRepository cases = mock(CaseRepository.class);

    private final AmlAlertRepository alerts = mock(AmlAlertRepository.class);

    private final InvestigationHypothesisRepository hypotheses = mock(InvestigationHypothesisRepository.class);

    private final InvestigationEvidenceLinkRepository evidence = mock(InvestigationEvidenceLinkRepository.class);

    private final AlertInvestigationCoverageRepository coverage = mock(AlertInvestigationCoverageRepository.class);

    private final InvestigationService service = new InvestigationService(cases, alerts, hypotheses, evidence, coverage,
            new InvestigationPlaybookCatalog(), new InvestigationReadinessEvaluator(), mock(AuditOutboxService.class));

    {
        // 实体仓库 save 的 mock 默认返回 null；测试需要回传实体以断言状态机结果
        when(hypotheses.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(coverage.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---- 既有门槛：已确认假设 + 版本绑定的可疑覆盖允许确认可疑 ----

    @Test
    void confirmedHypothesisAndSuspiciousCoverageAllowSuspiciousReview() {
        CaseEntity caseEntity = contractedCase();
        AmlAlert alert = mock(AmlAlert.class);
        InvestigationHypothesis hypothesis = confirmedHypothesis(31L, 3);
        AlertInvestigationCoverage item = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 3L);
        when(alert.getId()).thenReturn(11L);
        when(alert.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of(alert));
        when(hypotheses.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of(hypothesis));
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(item));
        when(evidence.findByCaseIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS)));

        assertThatCode(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.EXCLUDE_FALSE_POSITIVE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("所有调查假设均已排除");
    }

    @Test
    void pendingCoverageBlocksFinalReviewButNotEddRequest() {
        CaseEntity caseEntity = contractedCase();
        AmlAlert alert = mock(AmlAlert.class);
        InvestigationHypothesis hypothesis = mock(InvestigationHypothesis.class);
        AlertInvestigationCoverage item = mock(AlertInvestigationCoverage.class);
        when(alert.getId()).thenReturn(12L);
        when(alert.getStatus()).thenReturn(AlertStatus.LINKED);
        when(hypothesis.getId()).thenReturn(21L);
        when(hypothesis.getStatus()).thenReturn(HypothesisStatus.OPEN);
        when(hypothesis.getTitle()).thenReturn("默认假设");
        when(item.getAlertId()).thenReturn(12L);
        when(item.getConclusion()).thenReturn(AlertCoverageConclusion.PENDING);
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of(alert));
        when(hypotheses.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of(hypothesis));
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(item));

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("预警未形成覆盖结论")
            .hasMessageContaining("调查假设未确认或排除");
        assertThatCode(() -> service.validateReadyForReview(caseEntity, ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE))
            .doesNotThrowAnyException();
    }

    @Test
    void legacyCaseBypassesNewContractGate() {
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setInvestigationContractVersion(0);
        assertThatCode(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .doesNotThrowAnyException();
    }

    @Test
    void evidenceOrDecisionMakesAlertSplitUnsafe() {
        when(evidence.existsByCaseId(7L)).thenReturn(true);
        assertThatThrownBy(() -> service.validateCanSplit(7L)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("已录入调查证据");

        when(evidence.existsByCaseId(8L)).thenReturn(false);
        InvestigationHypothesis decided = mock(InvestigationHypothesis.class);
        when(decided.getStatus()).thenReturn(HypothesisStatus.REJECTED);
        when(hypotheses.findByCaseIdOrderByIdAsc(8L)).thenReturn(List.of(decided));
        assertThatThrownBy(() -> service.validateCanSplit(8L)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("已有已决调查假设");
    }

    // ---- T09：一个假设改判只使自身相关覆盖失效，另一假设仍确认不能绕过门禁 ----

    @Test
    void hypothesisRedecisionInvalidatesOnlyItsOwnCoveragesAndBlocksStaleEvidence() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        InvestigationHypothesis first = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 2);
        InvestigationHypothesis second = decidedHypothesis(32L, HypothesisStatus.CONFIRMED, 1);
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(first));
        AlertInvestigationCoverage firstCoverage = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 2L);
        AlertInvestigationCoverage secondCoverage = coverage(12L, 32L, AlertCoverageConclusion.SUSPICIOUS, 1L);
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(firstCoverage, secondCoverage));
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.CONTRADICTS)));

        service.updateHypothesis(7L, 31L, 2, "REJECTED", "反向交易证据证明该假设不成立，予以排除", "analyst");

        // 仅引用第一个假设的覆盖失效；第二个假设的覆盖保持已决
        assertThat(firstCoverage.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
        assertThat(firstCoverage.getHypothesisRevision()).isNull();
        assertThat(firstCoverage.getRevision()).isEqualTo(1);
        assertThat(secondCoverage.getConclusion()).isEqualTo(AlertCoverageConclusion.SUSPICIOUS);
        // 改判后假设版本递增并绑定排除结论
        assertThat(first.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        assertThat(first.getRevision()).isEqualTo(3);
    }

    @Test
    void oneConfirmedHypothesisDoesNotBypassStaleCoverageGate() {
        CaseEntity caseEntity = contractedCase();
        AmlAlert alertA = mock(AmlAlert.class);
        AmlAlert alertB = mock(AmlAlert.class);
        when(alertA.getId()).thenReturn(11L);
        when(alertB.getId()).thenReturn(12L);
        when(alertA.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alertB.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alertA.getExternalAlertId()).thenReturn("ALERT-A");
        when(alertB.getExternalAlertId()).thenReturn("ALERT-B");
        InvestigationHypothesis rejected = decidedHypothesis(31L, HypothesisStatus.REJECTED, 3);
        InvestigationHypothesis confirmed = decidedHypothesis(32L, HypothesisStatus.CONFIRMED, 1);
        AlertInvestigationCoverage stale = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 2L);
        AlertInvestigationCoverage fresh = coverage(12L, 32L, AlertCoverageConclusion.SUSPICIOUS, 1L);
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of(alertA, alertB));
        when(hypotheses.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of(rejected, confirmed));
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(stale, fresh));
        when(evidence.findByCaseIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.CONTRADICTS),
                    evidence(32L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS),
                    evidence(32L, InvestigationEvidenceType.CUSTOMER_PROFILE, EvidenceStance.SUPPORTS)));

        // 第二个假设仍确认且有一条可疑覆盖，但第一个覆盖的假设版本已过期 → 确认可疑被阻断
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("假设已改判");
    }

    // ---- T10：结论矛盾 / 跨案件 / 缺关联 / 旧版本 / 缺必填版本参数 ----

    @Test
    void coverageContradictionCrossCaseMissingLinkAndStaleVersionAreAllRejected() {
        CaseEntity caseEntity = contractedCase();
        AmlAlert alert = mock(AmlAlert.class);
        when(alert.getId()).thenReturn(11L);
        when(alert.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alert.getExternalAlertId()).thenReturn("ALERT-A");
        // SUSPICIOUS 覆盖绑定的假设是 REJECTED：结论与假设状态矛盾
        InvestigationHypothesis confirmed = decidedHypothesis(31L, HypothesisStatus.REJECTED, 1);
        // SUSPICIOUS 覆盖绑定的假设是 REJECTED：结论与假设状态矛盾
        AlertInvestigationCoverage contradictory = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 1L);
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of(alert));
        when(hypotheses.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of(confirmed));
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(contradictory));
        when(evidence.findByCaseIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("结论与关联假设状态矛盾");

        // 覆盖指向不属于当前案件的假设 → 阻断
        AlertInvestigationCoverage dangling = coverage(11L, 99L, AlertCoverageConclusion.SUSPICIOUS, 1L);
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(dangling));
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不属于当前案件的调查假设");

        // 存量覆盖缺版本绑定 → 阻断并要求重新确认
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L))
            .thenReturn(List.of(coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, null)));
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("缺少假设版本绑定");
    }

    @Test
    void coverageUpdateRejectsMissingAndStaleHypothesisRevision() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        AmlAlert alert = new AmlAlert();
        setId(alert, 11L);
        alert.setCaseId(7L);
        alert.setStatus(AlertStatus.LINKED);
        alert.setRevision(0);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        AlertInvestigationCoverage item = coverage(11L, 31L, AlertCoverageConclusion.PENDING, null);
        item.setRevision(0);
        when(coverage.findByAlertIdAndCaseIdForUpdate(11L, 7L)).thenReturn(Optional.of(item));
        InvestigationHypothesis confirmed = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(confirmed));

        // 缺必填版本参数 → 明确参数错误（400），不默认采用当前版本
        assertThatThrownBy(
                () -> service.updateCoverage(7L, 11L, 0, 31L, null, "SUSPICIOUS", "与已确认假设一致的可疑结论说明", "analyst"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expectedHypothesisRevision");

        // 旧假设版本 → 409 冲突
        assertThatThrownBy(
                () -> service.updateCoverage(7L, 11L, 0, 31L, 2L, "SUSPICIOUS", "与已确认假设一致的可疑结论说明", "analyst"))
            .isInstanceOf(InvestigationRevisionConflictException.class)
            .hasMessageContaining("已改判");

        // 当前版本 → 成功并由服务端写入锁定版本
        service.updateCoverage(7L, 11L, 0, 31L, 3L, "SUSPICIOUS", "与已确认假设一致的可疑结论说明", "analyst");
        assertThat(item.getHypothesisRevision()).isEqualTo(3L);
        assertThat(item.getConclusion()).isEqualTo(AlertCoverageConclusion.SUSPICIOUS);
    }

    // ---- W1/V2-01：乙真实改判后，甲基于旧覆盖与旧假设版本提交 → 统一 409 冲突，旧提交不落库 ----

    @Test
    void staleSubmissionAfterRealRedecisionReturnsUnifiedConflictWithoutWriting() {
        AuditOutboxService audit = mock(AuditOutboxService.class);
        InvestigationService audited = new InvestigationService(cases, alerts, hypotheses, evidence, coverage,
                new InvestigationPlaybookCatalog(), new InvestigationReadinessEvaluator(), audit);
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        AmlAlert alert = new AmlAlert();
        setId(alert, 11L);
        alert.setCaseId(7L);
        alert.setStatus(AlertStatus.LINKED);
        alert.setRevision(0);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        InvestigationHypothesis hypothesis = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        hypothesis.setRationale("甲页面持有的旧依据");
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(hypothesis));
        AlertInvestigationCoverage item = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 3L);
        item.setRevision(0);
        when(coverage.findByAlertIdAndCaseIdForUpdate(11L, 7L)).thenReturn(Optional.of(item));
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(item));
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS)));

        // 乙真实调用改判：假设 3→4，覆盖重置 PENDING 且 revision 0→1
        var redecided = audited.updateHypothesis(7L, 31L, 3, "CONFIRMED", "乙基于新增证据链改判后的确认依据", "analystB");
        assertThat(redecided.revision()).isEqualTo(4);
        assertThat(item.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
        assertThat(item.getRevision()).isEqualTo(1);

        // 甲仍持有旧页面（覆盖 revision=0、假设 revision=3）提交 → 冲突（不是 412），旧提交无任何写入
        assertThatThrownBy(() -> audited.updateCoverage(7L, 11L, 0, 31L, 3L, "SUSPICIOUS", "甲基于旧页面输入的可疑分析", "analystA"))
            .isInstanceOf(InvestigationRevisionConflictException.class)
            .satisfies(e -> {
                var conflict = (InvestigationRevisionConflictException) e;
                assertThat(conflict.getConflictType()).isEqualTo(InvestigationRevisionConflictException.TYPE_COVERAGE);
                assertThat(conflict.getConflictId()).isEqualTo(11L);
                assertThat(conflict.getCurrentVersion()).isEqualTo(1);
            });
        // 冲突不写入：覆盖保持乙改判后的状态，不新增覆盖决策审计事件
        assertThat(item.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
        assertThat(item.getRevision()).isEqualTo(1);
        verify(audit, times(1)).enqueue(contains("INVESTIGATION_HYPOTHESIS"), anyString(), anyString(), anyString(),
                anyString(), anyString());
        verify(audit, never()).enqueue(startsWith("ALERT_COVERAGE"), anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    /** V2-02：仅覆盖被他人更新（非改判）→ 相同冲突协议，但不得误报“假设已改判”。 */
    @Test
    void coverageOnlyUpdateYieldsCoverageConflictNotHypothesisRedecisionMessage() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        AmlAlert alert = new AmlAlert();
        setId(alert, 11L);
        alert.setCaseId(7L);
        alert.setStatus(AlertStatus.LINKED);
        alert.setRevision(0);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        // 他人已把覆盖确认到 revision=2（绑定同一假设当前版本 3）
        AlertInvestigationCoverage item = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 3L);
        item.setRevision(2);
        when(coverage.findByAlertIdAndCaseIdForUpdate(11L, 7L)).thenReturn(Optional.of(item));
        InvestigationHypothesis confirmed = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.updateCoverage(7L, 11L, 1, 31L, 3L, "SUSPICIOUS", "基于过期覆盖版本的提交", "analystA"))
            .isInstanceOf(InvestigationRevisionConflictException.class)
            .satisfies(e -> {
                var conflict = (InvestigationRevisionConflictException) e;
                assertThat(conflict.getConflictType()).isEqualTo(InvestigationRevisionConflictException.TYPE_COVERAGE);
                assertThat(conflict.getCurrentVersion()).isEqualTo(2);
                // 同一协议，但不得把覆盖并发更新误报为假设改判
                assertThat(conflict.getMessage()).doesNotContain("改判");
            });
    }

    /** V2-03：旧假设版本 / 缺版本 / 业务前置条件分别对应冲突、参数错误、前置条件协议。 */
    @Test
    void staleHypothesisVersionMissingVersionAndMissingEvidenceFollowDistinctProtocols() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        AmlAlert alert = new AmlAlert();
        setId(alert, 11L);
        alert.setCaseId(7L);
        alert.setStatus(AlertStatus.LINKED);
        alert.setRevision(0);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        AlertInvestigationCoverage item = coverage(11L, 31L, AlertCoverageConclusion.PENDING, null);
        item.setRevision(0);
        when(coverage.findByAlertIdAndCaseIdForUpdate(11L, 7L)).thenReturn(Optional.of(item));
        InvestigationHypothesis confirmed = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(confirmed));

        // 覆盖版本新鲜但假设版本过期 → HYPOTHESIS 类型冲突（携带当前版本，供页面刷新）
        assertThatThrownBy(() -> service.updateCoverage(7L, 11L, 0, 31L, 2L, "SUSPICIOUS", "基于过期假设版本的提交", "analystA"))
            .isInstanceOf(InvestigationRevisionConflictException.class)
            .satisfies(e -> {
                var conflict = (InvestigationRevisionConflictException) e;
                assertThat(conflict.getConflictType())
                    .isEqualTo(InvestigationRevisionConflictException.TYPE_HYPOTHESIS);
                assertThat(conflict.getConflictId()).isEqualTo(31L);
                assertThat(conflict.getCurrentVersion()).isEqualTo(3);
            });

        // 缺必需证据 → 业务前置条件语义保持 412，不得升格或混入版本冲突协议
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(confirmed));
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.updateHypothesis(7L, 31L, 3, "CONFIRMED", "缺少必需证据的确认依据", "analystA"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("缺少必需证据类型");

        // 修改假设时的旧版本 → 同样是 HYPOTHESIS 类型冲突（原为普通状态异常/412）
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS)));
        assertThatThrownBy(() -> service.updateHypothesis(7L, 31L, 2, "CONFIRMED", "基于旧版本提交的确认依据", "analystA"))
            .isInstanceOf(InvestigationRevisionConflictException.class)
            .satisfies(e -> {
                var conflict = (InvestigationRevisionConflictException) e;
                assertThat(conflict.getConflictType())
                    .isEqualTo(InvestigationRevisionConflictException.TYPE_HYPOTHESIS);
                assertThat(conflict.getCurrentVersion()).isEqualTo(3);
            });
    }

    // ---- v2/V2-23：启用解释核验政策的案件旧写入入口必须拒绝；未知契约版本拒绝写入 ----

    @Test
    void v2PolicyCaseRejectsLegacyInvestigationWriteEntryPoints() {
        CaseEntity v2Case = editableCase(CaseStatus.HOLD);
        when(v2Case.getInvestigationContractVersion()).thenReturn(2);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(v2Case));

        assertThatThrownBy(
                () -> service.updateCoverage(7L, 11L, 0, 31L, 3L, "SUSPICIOUS", "绕过单元提交入口的旧 API 调用", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("预警单元提交入口");
        assertThatThrownBy(
                () -> service.addEvidence(7L, 31L, "TRANSACTION", "TXN-REF-1", "SUPPORTS", "绕过守卫的证据登记尝试", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("预警单元提交入口");
        assertThatThrownBy(() -> service.updateHypothesis(7L, 31L, 3, "CONFIRMED", "绕过守卫的假设判断", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("预警单元提交入口");
    }

    @Test
    void unknownContractVersionIsRejectedForWriteAndFinalReview() {
        CaseEntity v3Case = editableCase(CaseStatus.HOLD);
        when(v3Case.getInvestigationContractVersion()).thenReturn(3);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(v3Case));

        assertThatThrownBy(() -> service.updateCoverage(7L, 11L, 0, 31L, 3L, "SUSPICIOUS", "未知契约版本的写入尝试", "analyst"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不受支持");
        assertThatThrownBy(() -> service.validateReadyForReview(v3Case, ReviewDecision.CONFIRM_SUSPICIOUS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不受支持");
    }

    // ---- 幂等重放：完全相同的重复请求不递增版本、不重复失效覆盖 ----

    @Test
    void identicalHypothesisUpdateReplaysWithoutNewRevision() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        InvestigationHypothesis decided = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        decided.setRationale("与已冻结交易证据一致的确认结论");
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(decided));

        var result = service.updateHypothesis(7L, 31L, 3, "CONFIRMED", "与已冻结交易证据一致的确认结论", "analyst");

        assertThat(result.revision()).isEqualTo(3);
        assertThat(decided.getRevision()).isEqualTo(3);
        // 未实际修改依据时不允许复用旧覆盖，但也不需要重复失效
        verify(coverage, never()).findByCaseIdOrderByAlertIdAsc(any());
    }

    @Test
    void sameStatusWithChangedRationaleStillInvalidatesCoverages() {
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        InvestigationHypothesis decided = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        decided.setRationale("旧依据");
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(decided));
        AlertInvestigationCoverage bound = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 3L);
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(bound));
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS)));

        service.updateHypothesis(7L, 31L, 3, "CONFIRMED", "更新后的确认依据：新增证据链说明", "analyst");

        assertThat(decided.getRevision()).isEqualTo(4);
        assertThat(bound.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
        assertThat(bound.getHypothesisRevision()).isNull();
    }

    /** T12：审计登记与假设/覆盖修改在同一事务 —— 审计失败抛出时业务修改必须一同回滚。 */
    @Test
    void auditFailurePropagatesSoHypothesisAndCoverageRollBackTogether() {
        AuditOutboxService failingAudit = mock(AuditOutboxService.class);
        doThrow(new IllegalStateException("审计登记失败")).when(failingAudit)
            .enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        InvestigationService audited = new InvestigationService(cases, alerts, hypotheses, evidence, coverage,
                new InvestigationPlaybookCatalog(), new InvestigationReadinessEvaluator(), failingAudit);
        CaseEntity caseEntity = editableCase(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        InvestigationHypothesis decided = decidedHypothesis(31L, HypothesisStatus.CONFIRMED, 3);
        when(hypotheses.findByIdAndCaseIdForUpdate(31L, 7L)).thenReturn(Optional.of(decided));
        AlertInvestigationCoverage bound = coverage(11L, 31L, AlertCoverageConclusion.SUSPICIOUS, 3L);
        when(coverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of(bound));
        when(evidence.findByHypothesisIdOrderByCreatedAtAsc(31L))
            .thenReturn(List.of(evidence(31L, InvestigationEvidenceType.TRANSACTION, EvidenceStance.SUPPORTS)));

        // 同状态但依据改变：同样触发审计登记；登记失败向外抛出，外层事务回滚后
        // 假设与覆盖的修改不会被部分提交
        assertThatThrownBy(() -> audited.updateHypothesis(7L, 31L, 3, "CONFIRMED", "更新后的确认依据：补充证据链说明", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("审计登记失败");
        // 单元层只能验证异常传播路径；真实回滚由带案件行锁的事务集成测试覆盖
        verify(coverage).findByCaseIdOrderByAlertIdAsc(7L);
    }

    // ---- 辅助构造 ----

    private CaseEntity contractedCase() {
        CaseEntity entity = mock(CaseEntity.class);
        when(entity.getId()).thenReturn(7L);
        when(entity.getInvestigationContractVersion()).thenReturn(1);
        return entity;
    }

    private CaseEntity editableCase(CaseStatus status) {
        CaseEntity entity = mock(CaseEntity.class);
        when(entity.getId()).thenReturn(7L);
        when(entity.getInvestigationContractVersion()).thenReturn(1);
        when(entity.getStatus()).thenReturn(status);
        return entity;
    }

    private InvestigationHypothesis confirmedHypothesis(Long id, int revision) {
        InvestigationHypothesis hypothesis = mock(InvestigationHypothesis.class);
        when(hypothesis.getId()).thenReturn(id);
        when(hypothesis.getRevision()).thenReturn(revision);
        when(hypothesis.getStatus()).thenReturn(HypothesisStatus.CONFIRMED);
        when(hypothesis.getTitle()).thenReturn("确认假设");
        when(hypothesis.getRequiredEvidenceTypes()).thenReturn("TRANSACTION");
        return hypothesis;
    }

    private InvestigationHypothesis decidedHypothesis(Long id, HypothesisStatus status, int revision) {
        InvestigationHypothesis hypothesis = new InvestigationHypothesis();
        setId(hypothesis, id);
        hypothesis.setCaseId(7L);
        hypothesis.setScenarioCode("STRUCTURING");
        hypothesis.setHypothesisCode("H-" + id);
        hypothesis.setTitle("假设" + id);
        hypothesis.setInvestigationQuestion("是否成立？");
        hypothesis.setRequiredEvidenceTypes("TRANSACTION");
        hypothesis.setStatus(status);
        hypothesis.setRationale("既有判断依据");
        hypothesis.setRevision(revision);
        hypothesis.setCreatedBy("analyst");
        return hypothesis;
    }

    private AlertInvestigationCoverage coverage(Long alertId, Long hypothesisId, AlertCoverageConclusion conclusion,
            Long hypothesisRevision) {
        AlertInvestigationCoverage item = new AlertInvestigationCoverage();
        item.setAlertId(alertId);
        item.setCaseId(7L);
        item.setHypothesisId(hypothesisId);
        item.setHypothesisRevision(hypothesisRevision);
        item.setConclusion(conclusion);
        item.setRevision(0);
        return item;
    }

    /** 实体主键没有 setter（由数据库生成）：测试用反射设置，便于断言状态机行为。 */
    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private InvestigationEvidenceLink evidence(Long hypothesisId, InvestigationEvidenceType type,
            EvidenceStance stance) {
        InvestigationEvidenceLink link = new InvestigationEvidenceLink();
        link.setCaseId(7L);
        link.setHypothesisId(hypothesisId);
        link.setEvidenceType(type);
        link.setStance(stance);
        return link;
    }

}
