package com.bank.aml.investigation;

import com.bank.aml.TestClocks;
import com.bank.aml.TestProperties;
import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.observability.MetricsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseIntakeServiceTest {

    private final CaseRepository cases = mock(CaseRepository.class);

    private final AmlAlertRepository alerts = mock(AmlAlertRepository.class);

    private final CustomerDataPort customerData = mock(CustomerDataPort.class);

    private final InvestigationService investigation = mock(InvestigationService.class);

    private final WorkflowCommandService commands = mock(WorkflowCommandService.class);

    /** 与生产装配器同容量口径（默认 8），保证归并前置校验与 Worker 快照装配一致。 */
    private final AlertSnapshotAssembler assembler = new AlertSnapshotAssembler(
            new ObjectMapper().findAndRegisterModules(), 8);

    private final CaseIntakeService service = new CaseIntakeService(cases, alerts, customerData,
            new InvestigationPlaybookCatalog(), investigation, commands, mock(MetricsRecorder.class),
            mock(AuditOutboxService.class), assembler, TestProperties.aml(), TestClocks.FIXED);

    // ---- 既有锁顺序回归：拆分保持“案件 → 预警” ----

    @Test
    void refusesToMergeNewAlertIntoLegacyCompatibilityCase() {
        AmlAlert alert = linkedOrNewAlert(AlertStatus.NEW, null);
        CaseEntity legacy = new CaseEntity();
        legacy.setStatus(CaseStatus.PENDING);
        legacy.setCustomerId("C001");
        legacy.setInvestigationContractVersion(0);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.linkToCase(11L, 7L, 0, "同一客户但目标案件属于存量兼容范围", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("存量兼容案件不能接收新预警");
    }

    @Test
    void splitLocksCaseBeforeAlertToMatchCoverageUpdateOrder() {
        AmlAlert observed = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        AmlAlert locked = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        CaseEntity source = new CaseEntity();
        source.setStatus(CaseStatus.PENDING);
        when(alerts.findById(11L)).thenReturn(Optional.of(observed));
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(source));
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(locked));
        when(alerts.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(1L);

        assertThatThrownBy(() -> service.splitToNewCase(11L, 0, false, "该预警的交易主体和调查范围应独立处理", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("单预警案件无需拆分");

        InOrder order = inOrder(cases, alerts);
        order.verify(alerts).findById(11L);
        order.verify(cases).findByIdForUpdate(7L);
        order.verify(alerts).findByIdForUpdate(11L);
    }

    // ---- A2：容量在归并时前置校验，案件保持可拆分的 PENDING 状态 ----

    @Test
    void mergeBeyondCapacityIsRejectedBeforeExecutionAndKeepsCaseAdjustable() {
        CaseEntity target = new CaseEntity();
        setId(target, 7L);
        target.setStatus(CaseStatus.PENDING);
        target.setCustomerId("C001");
        target.setInvestigationContractVersion(1);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        // 已有 8 条 LINKED（达到上限），再归并第 9 条必须拒绝
        when(alerts.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(8L);
        AmlAlert alert = linkedOrNewAlert(AlertStatus.NEW, null);
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.linkToCase(11L, 7L, 0, "同客户新增第九条预警尝试归并到同一案件", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("容量上限")
            .hasMessageContaining("拆分");
        // 案件状态未被改变，仍可拆分调整边界
        assertThat(target.getStatus()).isEqualTo(CaseStatus.PENDING);
        // 预警未被归并保存，也不会入队
        verify(alerts, never()).save(any());
        verify(commands, never()).enqueueCaseCreated(any());
    }

    @Test
    void mergeAtCapacityBoundaryIsAllowed() {
        CaseEntity target = new CaseEntity();
        setId(target, 7L);
        target.setStatus(CaseStatus.PENDING);
        target.setCustomerId("C001");
        target.setInvestigationContractVersion(1);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(alerts.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(7L);
        AmlAlert alert = linkedOrNewAlert(AlertStatus.NEW, null);
        alert.setExternalAlertId("ALERT-9");
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));
        when(alerts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> service.linkToCase(11L, 7L, 0, "同客户新增预警，恰好达到容量上限", "analyst")).doesNotThrowAnyException();
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.LINKED);
        verify(investigation).initializeForAlert(7L, alert, "analyst");
    }

    // ---- A2：容量超限的 FAILED 案件可受控拆分恢复，不需要与重试消费者竞态 ----

    @Test
    void failedCapacityCaseCanBeSplitForControlledRecovery() {
        AmlAlert observed = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        AmlAlert locked = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        CaseEntity source = new CaseEntity();
        setId(source, 7L);
        source.setStatus(CaseStatus.FAILED);
        source.setCustomerId("C001");
        source.setInvestigationContractVersion(1);
        // 容量失败发生在任何调查产出之前：报告与原始模型输出均为空
        source.setReportJson(null);
        source.setRawReportJson(null);
        when(alerts.findById(11L)).thenReturn(Optional.of(observed));
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(source));
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(locked));
        when(alerts.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(2L);
        when(customerData.findCustomer("C001")).thenReturn(
                Optional.of(new CustomerProfile("C001", "张伟", "110101198506123456", "企业", "贸易", "上海", "5000万")));
        InvestigationHypothesis newHypothesis = mock(InvestigationHypothesis.class);
        when(newHypothesis.getId()).thenReturn(55L);
        when(investigation.initializeForAlert(any(), any(), any())).thenReturn(newHypothesis);
        when(cases.save(any())).thenAnswer(invocation -> {
            CaseEntity saved = invocation.getArgument(0);
            try {
                var field = CaseEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(saved, 88L);
            }
            catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return saved;
        });

        CaseEntity target = service.splitToNewCase(11L, 0, false, "容量超限失败，拆出该预警后恢复原案件", "analyst");

        assertThat(target.getId()).isEqualTo(88L);
        assertThat(target.getStatus()).isEqualTo(CaseStatus.PENDING);
        // 拆分出的案件默认不自动入队；原案件保持 FAILED 等待人工重试
        verify(commands, never()).enqueueCaseCreated(any());
        verify(investigation).validateCanSplit(7L);
        verify(investigation).resetCoverageForSplit(11L, 88L, 55L);
    }

    @Test
    void failedCaseWithInvestigationOutputCannotBeSplit() {
        AmlAlert observed = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        AmlAlert locked = linkedOrNewAlert(AlertStatus.LINKED, 7L);
        CaseEntity source = new CaseEntity();
        setId(source, 7L);
        source.setStatus(CaseStatus.FAILED);
        source.setInvestigationContractVersion(1);
        source.setReportJson("{\"riskLevel\":\"高风险\"}");
        when(alerts.findById(11L)).thenReturn(Optional.of(observed));
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(source));
        when(alerts.findByIdForUpdate(11L)).thenReturn(Optional.of(locked));
        when(alerts.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(2L);

        assertThatThrownBy(() -> service.splitToNewCase(11L, 0, false, "尝试拆分已产生调查产物的失败案件", "analyst"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("调查开始后不能拆分");
        verify(investigation, never()).validateCanSplit(any());
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

    private AmlAlert linkedOrNewAlert(AlertStatus status, Long caseId) {
        AmlAlert alert = new AmlAlert();
        alert.setCustomerId("C001");
        alert.setStatus(status);
        alert.setCaseId(caseId);
        alert.setRevision(0);
        alert.setHitReason("交易与客户画像不匹配");
        alert.setOccurredAt(LocalDateTime.now().minusHours(1));
        return alert;
    }

}
