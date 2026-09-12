package com.bank.aml.investigation;

import com.bank.aml.datasource.CustomerDataPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警范围冻结服务（v4 计划 §5.1 / G1-1 / RF-05）。
 *
 * <p>
 * 服务器冻结每条预警命中的全部交易（sourceRecordId）；客户端 reviewedTransactionIds
 * 只表达调查进度，不定义预警全集。范围缺口（声明集合缺命中交易）在提交校验时阻断； 来源版本变更可检测；允许记录未知（来源无法枚举时显式缺口，不假定完整）。
 */
@Service
public class AlertScopeService {

    private final AmlAlertRepository alertRepository;

    private final ObjectMapper objectMapper;

    private final CustomerDataPort customerDataPort;

    private final Clock clock;

    public AlertScopeService(AmlAlertRepository alertRepository, ObjectMapper objectMapper, Clock clock) {
        this(alertRepository, objectMapper, null, clock);
    }

    @Autowired
    public AlertScopeService(AmlAlertRepository alertRepository, ObjectMapper objectMapper,
            CustomerDataPort customerDataPort, Clock clock) {
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
        this.customerDataPort = customerDataPort;
        this.clock = clock;
    }

    /** 冻结结果视图。 */
    public record ScopeSnapshot(Long alertId, List<String> triggerTransactionIds, List<String> auxiliaryTransactionIds,
            String sourceVersion) {
    }

    /**
     * 冻结预警命中范围（上游/分析员提供，服务器记录冻结时点与操作者）。 幂等：已冻结且来源版本相同 → 返回既有快照；来源版本变化 → 追加更新（历史可回放由审计承载）。
     */
    @Transactional
    public ScopeSnapshot freezeScope(Long alertId, List<String> triggerTransactionIds,
            List<String> auxiliaryTransactionIds, String sourceVersion, String operator) {
        AmlAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("预警不存在：" + alertId));
        String normalizedSourceVersion = sourceVersion == null ? "" : sourceVersion.trim();
        Set<String> trigger = normalizedIds(triggerTransactionIds);
        if (trigger.isEmpty()) {
            throw new IllegalArgumentException("预警命中范围不能为空：来源无法枚举时必须记录范围缺口，" + "不能冻结空集冒充完整（RF-05）");
        }
        Set<String> auxiliary = normalizedIds(auxiliaryTransactionIds);
        for (String auxiliaryId : auxiliary) {
            if (trigger.contains(auxiliaryId)) {
                throw new IllegalArgumentException("交易 " + auxiliaryId + " 同时出现在命中与辅助集合；命中/辅助分类必须唯一");
            }
        }
        if (customerDataPort != null) {
            String currentVersion = customerDataPort.sourceVersion();
            if (normalizedSourceVersion.isBlank() || !normalizedSourceVersion.equals(currentVersion)) {
                throw new IllegalArgumentException(
                        "预警范围来源版本 " + normalizedSourceVersion + " 与当前权威来源版本 " + currentVersion + " 不一致；请刷新后重新冻结");
            }
            Set<String> authoritativeIds = new LinkedHashSet<>();
            customerDataPort.transactionsOf(alert.getCustomerId()).forEach(transaction -> {
                if (transaction.sourceRecordId() != null && !transaction.sourceRecordId().isBlank()) {
                    authoritativeIds.add(transaction.sourceRecordId());
                }
            });
            Set<String> unknown = new LinkedHashSet<>(trigger);
            unknown.addAll(auxiliary);
            unknown.removeAll(authoritativeIds);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("以下交易不属于预警客户的权威来源集合：" + String.join("、", unknown));
            }
        }
        alert.setTriggerTransactionIds(toJson(new ArrayList<>(trigger)));
        alert.setAuxiliaryTransactionIds(toJson(new ArrayList<>(auxiliary)));
        alert.setScopeSourceVersion(normalizedSourceVersion);
        alert.setScopeFrozenAt(LocalDateTime.now(clock));
        alert.setScopeFrozenBy(operator);
        alertRepository.save(alert);
        return snapshot(alert);
    }

    /** 读取冻结范围；未冻结返回 empty（范围未知，提交校验阻断）。 */
    @Transactional(readOnly = true)
    public Optional<ScopeSnapshot> frozenScope(Long alertId) {
        return alertRepository.findById(alertId)
            .map(this::snapshot)
            .filter(snapshot -> !snapshot.triggerTransactionIds().isEmpty())
            .filter(snapshot -> customerDataPort == null
                    || Objects.equals(snapshot.sourceVersion(), customerDataPort.sourceVersion()));
    }

    /**
     * 范围缺口校验（RF-05）：声明的命中交易必须覆盖服务器冻结全集。 缺失 → 缺口清单（含金额不可得的交易 ID）；未冻结 → 范围未知阻断。
     */
    @Transactional(readOnly = true)
    public List<String> scopeGaps(Long alertId, Set<String> declaredReviewed) {
        AmlAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("预警不存在：" + alertId));
        List<String> frozen = fromJson(alert.getTriggerTransactionIds());
        if (frozen.isEmpty()) {
            return List.of("预警 " + externalLabel(alert) + " 的命中范围尚未由服务器冻结；" + "范围未知（RF-05）：需先枚举并冻结命中交易全集");
        }
        if (customerDataPort != null
                && !Objects.equals(alert.getScopeSourceVersion(), customerDataPort.sourceVersion())) {
            return List.of("预警 " + externalLabel(alert) + " 的冻结范围来源版本 " + alert.getScopeSourceVersion() + " 已落后于当前版本 "
                    + customerDataPort.sourceVersion() + "；必须重新冻结范围后再提交");
        }
        List<String> gaps = new ArrayList<>();
        for (String tx : frozen) {
            if (!declaredReviewed.contains(tx)) {
                gaps.add(tx);
            }
        }
        return gaps;
    }

    private ScopeSnapshot snapshot(AmlAlert alert) {
        return new ScopeSnapshot(alert.getId(), fromJson(alert.getTriggerTransactionIds()),
                fromJson(alert.getAuxiliaryTransactionIds()), alert.getScopeSourceVersion());
    }

    private static String externalLabel(AmlAlert alert) {
        return alert.getExternalAlertId() != null ? alert.getExternalAlertId() : String.valueOf(alert.getId());
    }

    private String toJson(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("范围序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("预警范围字段损坏，不能伪装成空范围：" + e.getMessage(), e);
        }
    }

    private static Set<String> normalizedIds(List<String> ids) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(id.trim());
            }
        }
        return normalized;
    }

}
