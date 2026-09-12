package com.bank.aml.investigation;

import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.config.AmlProperties;
import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 预警上下文装配：把关联预警冻结为不可变快照并计算规范化摘要。
 * <p>
 * 两条入口，不能混用：
 * <ul>
 * <li>{@link #fromLinkedAlerts}：生产链路，Worker 抢占案件后读取的 LINKED 预警；</li>
 * <li>{@link #fromAlertRuleText}：版本 0 / 不绑定数据库案件的评测夹具的显式兼容适配器， 只允许存量兼容路径调用，禁止掩盖版本 1
 * 的数据缺失。</li>
 * </ul>
 * 摘要对规范化后的预警字段（含命中原因与版本）做 SHA-256；同一预警集合不因数据库返回顺序改变摘要。
 */
@Component
public class AlertSnapshotAssembler {

    /** 单次尽调可冻结的最大关联预警数：超出必须明确拒绝，禁止截断尾部预警后当作完整调查。 */
    private final int maxLinkedAlerts;

    private final ObjectMapper objectMapper;

    @Autowired
    public AlertSnapshotAssembler(ObjectMapper objectMapper, AmlProperties properties) {
        this(objectMapper, properties.agent().maxLinkedAlerts());
    }

    /** 便捷构造（测试/无 Spring 场景）。 */
    public AlertSnapshotAssembler(ObjectMapper objectMapper, int maxLinkedAlerts) {
        this.objectMapper = objectMapper;
        this.maxLinkedAlerts = Math.max(1, maxLinkedAlerts);
    }

    /** 单次尽调允许的关联预警上限（供上下文/配置校验提示）。 */
    public int capacity() {
        return maxLinkedAlerts;
    }

    /** 生产路径：冻结案件的全部有效 LINKED 预警（occurredAt 后按 alertId 稳定排序）。 */
    public List<InvestigationAlertSnapshot> fromLinkedAlerts(Long caseId, List<AmlAlert> alerts) {
        List<InvestigationAlertSnapshot> frozen = new ArrayList<>();
        for (AmlAlert alert : alerts) {
            if (alert == null || alert.getStatus() != AlertStatus.LINKED
                    || !Objects.equals(alert.getCaseId(), caseId)) {
                continue;
            }
            frozen.add(new InvestigationAlertSnapshot(alert.getId(), alert.getExternalAlertId(), alert.getRuleCode(),
                    alert.getScenarioCode(), alert.getHitReason(), alert.getOccurredAt(), alert.getRevision()));
        }
        frozen.sort(Comparator
            .comparing(InvestigationAlertSnapshot::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(InvestigationAlertSnapshot::alertId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (frozen.size() > maxLinkedAlerts) {
            throw new NonRetryableWorkflowException(
                    "案件关联预警数量 " + frozen.size() + " 超出单次尽调容量上限 " + maxLinkedAlerts + "，请先归并到更少案件或拆分后分别调查");
        }
        return List.copyOf(frozen);
    }

    /**
     * 兼容路径：版本 0 / 评测夹具只有预警文本时的显式适配。 生成一条标记为 LEGACY_ALERT_RULE
     * 的伪预警，仅用于保持旧快照/旧评测语义，不代表真实预警记录。
     */
    public List<InvestigationAlertSnapshot> fromAlertRuleText(String alertRule) {
        String reason = alertRule == null || alertRule.isBlank() ? "历史工单预警文本缺失" : alertRule.trim();
        return List.of(new InvestigationAlertSnapshot(null, "LEGACY-ALERT-RULE", "LEGACY_ALERT_RULE",
                "PROFILE_MISMATCH", reason, null, 0));
    }

    /**
     * 预警输入摘要：对规范化序列化后的预警字段做 SHA-256。 包含命中原因与版本，因此修改原因或归并状态变化都能通过摘要识别；不使用有歧义的字符串简单拼接。
     */
    public String digest(List<InvestigationAlertSnapshot> alerts) {
        StringBuilder canonical = new StringBuilder("[");
        for (int i = 0; i < alerts.size(); i++) {
            InvestigationAlertSnapshot alert = alerts.get(i);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("alertId", alert.alertId());
            fields.put("externalAlertId", alert.externalAlertId());
            fields.put("ruleCode", alert.ruleCode());
            fields.put("scenarioCode", alert.scenarioCode());
            fields.put("hitReason", alert.hitReason() == null ? "" : alert.hitReason().trim());
            fields.put("occurredAt", alert.occurredAt() == null ? "" : alert.occurredAt().toString());
            fields.put("alertRevision", alert.alertRevision());
            if (i > 0)
                canonical.append(',');
            canonical.append(canonicalJson(fields));
        }
        canonical.append(']');
        return sha256(canonical.toString());
    }

    /** 规范化 JSON：键序固定，转义交给 ObjectMapper，避免手写拼接引入歧义。 */
    private String canonicalJson(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        }
        catch (JsonProcessingException e) {
            // ObjectMapper 对 Map<String,Object> 序列化不会失败；防御性兜底保持确定性
            throw new IllegalStateException("预警快照规范化序列化失败", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

}
