package com.bank.aml.investigation;

import com.bank.aml.domain.ReviewDecision;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一调查就绪判断（无副作用）：服务层负责按案件批量读取事实，本组件只计算结论。
 * <p>
 * 详情查询、运营队列分流和人工最终复核共用同一份判断，避免出现“页面说就绪但复核被拒”的口径漂移。
 * <p>
 * 判定规则（对每条有效 LINKED 预警逐条检查）：
 * <ol>
 * <li>案件至少有一条有效预警和至少一个调查假设；</li>
 * <li>每条预警恰有一条属于当前案件的覆盖项；</li>
 * <li>覆盖关联的假设存在且属于当前案件（阻断悬空/跨案件关联）；</li>
 * <li>假设已决时覆盖不能停留 PENDING，且 SUSPICIOUS 必须对应 CONFIRMED、EXPLAINED 必须对应 REJECTED；</li>
 * <li>覆盖绑定的假设版本必须等于当前假设版本；NULL（存量数据）视为没有可证明的绑定，要求重新确认；</li>
 * <li>最终假设仍满足必需证据类型与证据方向要求，不信任状态字段。</li>
 * </ol>
 * 业务门槛保留既有口径：确认可疑至少需要一条可疑覆盖；排除要求所有假设已排除且所有预警已解释。 补充尽调不是最终结论，不在本组件阻断范围内。
 */
@Component
public class InvestigationReadinessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationReadinessEvaluator.class);

    /** 单个案件的调查事实（已限定在当前案件范围内）。 */
    public record Facts(int contractVersion, List<AmlAlert> linkedAlerts, List<InvestigationHypothesis> hypotheses,
            List<AlertInvestigationCoverage> coverage,
            Map<Long, List<InvestigationEvidenceLink>> evidenceByHypothesisId) {
        public Facts {
            linkedAlerts = linkedAlerts == null ? List.of() : List.copyOf(linkedAlerts);
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            evidenceByHypothesisId = evidenceByHypothesisId == null ? Map.of() : Map.copyOf(evidenceByHypothesisId);
        }

        public static Facts empty(int contractVersion) {
            return new Facts(contractVersion, List.of(), List.of(), List.of(), Map.of());
        }
    }

    /** 就绪结论：通用阻断 + 两种最终决定各自的阻断；readyForFinalReview 表示两种结案路径至少一条可走。 */
    public record Result(boolean readyForFinalReview, List<String> generalBlockers, List<String> confirmBlockers,
            List<String> excludeBlockers) {
        public Result {
            generalBlockers = generalBlockers == null ? List.of() : List.copyOf(generalBlockers);
            confirmBlockers = confirmBlockers == null ? List.of() : List.copyOf(confirmBlockers);
            excludeBlockers = excludeBlockers == null ? List.of() : List.copyOf(excludeBlockers);
        }

        public static Result ready() {
            return new Result(true, List.of(), List.of(), List.of());
        }
    }

    public Result evaluate(Facts facts) {
        if (facts.contractVersion() < 1) {
            // 存量兼容案件：保留既有自动完成/旧复核行为，不执行新调查契约门禁。
            return Result.ready();
        }
        List<String> general = new ArrayList<>();
        Map<Long, InvestigationHypothesis> hypothesisById = new LinkedHashMap<>();
        for (InvestigationHypothesis hypothesis : facts.hypotheses()) {
            if (hypothesis != null && hypothesis.getId() != null) {
                hypothesisById.put(hypothesis.getId(), hypothesis);
            }
        }
        evaluateAlertsAndCoverage(facts, hypothesisById, general);
        evaluateHypotheses(facts, hypothesisById, general);

        List<String> confirm = new ArrayList<>(general);
        List<String> exclude = new ArrayList<>(general);
        if (hypothesisById.isEmpty()) {
            confirm.add("案件没有调查假设");
            exclude.add("案件没有调查假设");
        }
        else {
            if (hypothesisById.values().stream().noneMatch(item -> item.getStatus() == HypothesisStatus.CONFIRMED)) {
                confirm.add("确认可疑至少需要一个已确认假设");
            }
            if (hypothesisById.values().stream().anyMatch(item -> item.getStatus() != HypothesisStatus.REJECTED)) {
                exclude.add("排除预警要求所有调查假设均已排除");
            }
        }
        boolean anySuspicious = facts.coverage()
            .stream()
            .anyMatch(item -> item.getConclusion() == AlertCoverageConclusion.SUSPICIOUS);
        if (!anySuspicious) {
            confirm.add("确认可疑至少需要一条可疑预警覆盖结论");
        }
        boolean allExplained = !facts.coverage().isEmpty() && facts.coverage()
            .stream()
            .allMatch(item -> item.getConclusion() == AlertCoverageConclusion.EXPLAINED);
        if (!allExplained) {
            exclude.add("排除预警要求所有关联预警均有合理解释");
        }
        boolean ready = dedupe(confirm).isEmpty() || dedupe(exclude).isEmpty();
        return new Result(ready, dedupe(general), dedupe(confirm), dedupe(exclude));
    }

    /** 兼容旧调用：仅给出单个最终决定方向的阻断列表（与既有异常文案保持一致）。 */
    public List<String> blockersFor(Facts facts, ReviewDecision decision) {
        Result result = evaluate(facts);
        return switch (decision) {
            case CONFIRM_SUSPICIOUS -> result.confirmBlockers();
            case EXCLUDE_FALSE_POSITIVE -> result.excludeBlockers();
            case REQUEST_ENHANCED_DUE_DILIGENCE -> List.of();
        };
    }

    private void evaluateAlertsAndCoverage(Facts facts, Map<Long, InvestigationHypothesis> hypothesisById,
            List<String> blockers) {
        if (facts.linkedAlerts().isEmpty()) {
            blockers.add("案件没有有效关联预警");
            return;
        }
        boolean anyPendingCoverage = false;
        for (AmlAlert alert : facts.linkedAlerts()) {
            String label = alertLabel(alert);
            List<AlertInvestigationCoverage> bound = facts.coverage()
                .stream()
                .filter(item -> alert.getId() != null && alert.getId().equals(item.getAlertId()))
                .toList();
            if (bound.isEmpty()) {
                blockers.add("预警 " + label + " 缺少调查覆盖结论");
                continue;
            }
            for (AlertInvestigationCoverage item : bound) {
                if (item.getConclusion() == AlertCoverageConclusion.PENDING) {
                    anyPendingCoverage = true;
                    continue;
                }
                InvestigationHypothesis hypothesis = item.getHypothesisId() == null ? null
                        : hypothesisById.get(item.getHypothesisId());
                if (hypothesis == null) {
                    blockers.add("预警 " + label + " 的覆盖关联了不属于当前案件的调查假设");
                    continue;
                }
                if (hypothesis.getStatus() == HypothesisStatus.OPEN) {
                    blockers.add("预警 " + label + " 的结论与未决假设矛盾，需先确认或排除假设");
                    continue;
                }
                AlertCoverageConclusion expected = hypothesis.getStatus() == HypothesisStatus.CONFIRMED
                        ? AlertCoverageConclusion.SUSPICIOUS : AlertCoverageConclusion.EXPLAINED;
                if (item.getConclusion() != expected) {
                    blockers.add("预警 " + label + " 的结论与关联假设状态矛盾，需重新确认");
                }
                if (item.getHypothesisRevision() == null) {
                    blockers.add("预警 " + label + " 的覆盖缺少假设版本绑定，请重新确认");
                }
                else if (item.getHypothesisRevision() != hypothesis.getRevision()) {
                    blockers.add("预警 " + label + " 的覆盖依据的假设已改判，请重新确认");
                }
            }
        }
        if (anyPendingCoverage) {
            blockers.add("仍有预警未形成覆盖结论");
        }
    }

    private void evaluateHypotheses(Facts facts, Map<Long, InvestigationHypothesis> hypothesisById,
            List<String> blockers) {
        if (facts.hypotheses().isEmpty()) {
            blockers.add("案件没有调查假设");
            return;
        }
        if (hypothesisById.values().stream().anyMatch(item -> item.getStatus() == HypothesisStatus.OPEN)) {
            blockers.add("仍有调查假设未确认或排除");
        }
        for (InvestigationHypothesis hypothesis : hypothesisById.values()) {
            if (hypothesis.getStatus() == HypothesisStatus.OPEN) {
                continue;
            }
            List<InvestigationEvidenceLink> evidence = facts.evidenceByHypothesisId()
                .getOrDefault(hypothesis.getId(), List.of());
            Set<InvestigationEvidenceType> present = new LinkedHashSet<>();
            for (InvestigationEvidenceLink link : evidence) {
                if (link.getEvidenceType() != null) {
                    present.add(link.getEvidenceType());
                }
            }
            List<InvestigationEvidenceType> missing = requiredEvidenceTypes(hypothesis).stream()
                .filter(type -> !present.contains(type))
                .toList();
            if (!missing.isEmpty()) {
                blockers.add("假设「" + hypothesis.getTitle() + "」缺少必需证据类型：" + missing);
            }
            EvidenceStance requiredStance = hypothesis.getStatus() == HypothesisStatus.CONFIRMED
                    ? EvidenceStance.SUPPORTS : EvidenceStance.CONTRADICTS;
            boolean stanceSatisfied = evidence.stream().anyMatch(item -> item.getStance() == requiredStance);
            if (!stanceSatisfied) {
                blockers.add((hypothesis.getStatus() == HypothesisStatus.CONFIRMED ? "确认假设「" : "排除假设「")
                        + hypothesis.getTitle() + "」缺少" + (requiredStance == EvidenceStance.SUPPORTS ? "支持" : "反向")
                        + "证据");
            }
        }
    }

    private List<InvestigationEvidenceType> requiredEvidenceTypes(InvestigationHypothesis hypothesis) {
        if (hypothesis.getRequiredEvidenceTypes() == null || hypothesis.getRequiredEvidenceTypes().isBlank()) {
            return List.of();
        }
        List<InvestigationEvidenceType> types = new ArrayList<>();
        for (String value : hypothesis.getRequiredEvidenceTypes().split(",")) {
            try {
                types.add(InvestigationEvidenceType.valueOf(value.trim()));
            }
            catch (IllegalArgumentException malformedType) {
                // 未知枚举值按“无该类型要求”处理，不在就绪判断中扩权
                log.debug("忽略未知证据类型 type={}", value, malformedType);
            }
        }
        return types;
    }

    private String alertLabel(AmlAlert alert) {
        return alert.getExternalAlertId() != null ? alert.getExternalAlertId() : String.valueOf(alert.getId());
    }

    private List<String> dedupe(Collection<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

}
