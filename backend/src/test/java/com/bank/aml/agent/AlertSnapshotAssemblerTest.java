package com.bank.aml.agent;

import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.AlertStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T07：预警快照不可变、摘要稳定、原因/版本变化可识别；容量与兼容路径行为。 */
class AlertSnapshotAssemblerTest {

    private final AlertSnapshotAssembler assembler =
            new AlertSnapshotAssembler(new ObjectMapper().findAndRegisterModules(), 8);

    @Test
    void freezesLinkedAlertsInStableOrderIgnoringDatabaseOrder() {
        AmlAlert first = alert(11L, "ALERT-A", "STRUCTURING", "客户通过拆分现金交易规避监测",
                LocalDateTime.of(2026, 8, 1, 10, 0));
        AmlAlert second = alert(12L, "ALERT-B", "CROSS_BORDER_ANOMALY", "客户连续发生夜间跨境转账",
                LocalDateTime.of(2026, 8, 1, 9, 0));

        var byInsertion = assembler.fromLinkedAlerts(7L, List.of(first, second));
        var byReverse = assembler.fromLinkedAlerts(7L, List.of(second, first));

        // 稳定顺序：occurredAt 后按 alertId，数据库返回顺序不改变摘要
        assertThat(byInsertion).isEqualTo(byReverse);
        assertThat(byInsertion).extracting(InvestigationAlertSnapshot::externalAlertId)
                .containsExactly("ALERT-B", "ALERT-A");
        assertThat(assembler.digest(byInsertion)).isEqualTo(assembler.digest(byReverse));
    }

    @Test
    void digestChangesWhenHitReasonOrRevisionChanges() {
        var original = assembler.fromLinkedAlerts(7L, List.of(
                alert(11L, "ALERT-A", "STRUCTURING", "客户通过拆分现金交易规避监测",
                        LocalDateTime.of(2026, 8, 1, 10, 0))));
        var reasonChanged = assembler.fromLinkedAlerts(7L, List.of(
                alert(11L, "ALERT-A", "STRUCTURING", "命中原因已修订：拆分现金交易规避监测",
                        LocalDateTime.of(2026, 8, 1, 10, 0))));
        var revisionChanged = assembler.fromLinkedAlerts(7L, List.of(
                alertWithRevision(11L, "ALERT-A", "STRUCTURING", "客户通过拆分现金交易规避监测", 1,
                        LocalDateTime.of(2026, 8, 1, 10, 0))));

        assertThat(assembler.digest(original)).isNotEqualTo(assembler.digest(reasonChanged));
        assertThat(assembler.digest(original)).isNotEqualTo(assembler.digest(revisionChanged));
    }

    @Test
    void rejectsLinkingAlertsOfOtherCasesAndOnlyKeepsLinkedOnes() {
        AmlAlert linked = alert(11L, "ALERT-A", "STRUCTURING", "客户通过拆分现金交易规避监测",
                LocalDateTime.of(2026, 8, 1, 10, 0));
        AmlAlert otherCase = alert(12L, "ALERT-B", "STRUCTURING", "其他案件预警",
                LocalDateTime.of(2026, 8, 2, 10, 0));
        otherCase.setCaseId(99L);
        AmlAlert unlinked = alert(13L, "ALERT-C", "PROFILE_MISMATCH", "尚未归并",
                LocalDateTime.of(2026, 8, 3, 10, 0));
        unlinked.setStatus(AlertStatus.NEW);
        unlinked.setCaseId(null);

        var frozen = assembler.fromLinkedAlerts(7L, List.of(linked, otherCase, unlinked));
        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).externalAlertId()).isEqualTo("ALERT-A");
    }

    @Test
    void exceedingCapacityIsRejectedInsteadOfTruncating() {
        AlertSnapshotAssembler small = new AlertSnapshotAssembler(new ObjectMapper(), 2);
        AmlAlert first = alert(11L, "ALERT-A", "STRUCTURING", "原因一",
                LocalDateTime.of(2026, 8, 1, 10, 0));
        AmlAlert second = alert(12L, "ALERT-B", "STRUCTURING", "原因二",
                LocalDateTime.of(2026, 8, 1, 11, 0));
        AmlAlert third = alert(13L, "ALERT-C", "STRUCTURING", "原因三",
                LocalDateTime.of(2026, 8, 1, 12, 0));

        assertThatThrownBy(() -> small.fromLinkedAlerts(7L, List.of(first, second, third)))
                .isInstanceOf(NonRetryableWorkflowException.class)
                .hasMessageContaining("容量上限");
    }

    /** 兼容适配器只服务版本 0：生成显式标记的 LEGACY 伪预警。 */
    @Test
    void legacyAdapterMarksAlertRuleTextExplicitly() {
        var legacy = assembler.fromAlertRuleText("大额频繁跨国转账");
        assertThat(legacy).hasSize(1);
        assertThat(legacy.get(0).externalAlertId()).isEqualTo("LEGACY-ALERT-RULE");
        assertThat(legacy.get(0).ruleCode()).isEqualTo("LEGACY_ALERT_RULE");
        assertThat(legacy.get(0).alertId()).isNull();
        assertThat(assembler.digest(legacy)).hasSize(64);
    }

    private AmlAlert alert(Long id, String externalId, String scenario, String reason,
                           LocalDateTime occurredAt) {
        return alertWithRevision(id, externalId, scenario, reason, 0, occurredAt);
    }

    private AmlAlert alertWithRevision(Long id, String externalId, String scenario, String reason,
                                       int revision, LocalDateTime occurredAt) {
        AmlAlert alert = new AmlAlert();
        try {
            var field = AmlAlert.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(alert, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        alert.setExternalAlertId(externalId);
        alert.setCustomerId("C001");
        alert.setRuleCode("RULE-" + id);
        alert.setScenarioCode(scenario);
        alert.setHitReason(reason);
        alert.setOccurredAt(occurredAt);
        alert.setStatus(AlertStatus.LINKED);
        alert.setCaseId(7L);
        alert.setRevision(revision);
        return alert;
    }
}
