package com.bank.aml.risk;

import com.bank.aml.common.enums.RiskLevel;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.sanction.SanctionMatchScorer;
import com.bank.aml.sanction.SanctionScreeningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 风险事实组装器：把工具原始结果（交易/股权/黑名单）转换为统一的 {@link RiskContext}。
 * <p>Guardrails 使用的结构化事实必须来自工具结果或数据质量元数据，不能依赖模型自行声明。
 * 提供纯函数 {@link #assembleFrom(List, List, List, String)}，可从已冻结的原始数据计算风险事实，
 * 供快照工厂复用；生产与评测链路共用同一组装逻辑，数据源可替换。
 */
@Component
public class RiskFactAssembler {

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final CustomerDataPort dataSource;
    private final SanctionMatchScorer sanctionMatchScorer;
    private final SanctionScreeningService sanctionScreeningService;

    public RiskFactAssembler(CustomerDataPort dataSource) {
        this(dataSource, new SanctionMatchScorer(), null);
    }

    public RiskFactAssembler(CustomerDataPort dataSource, SanctionMatchScorer sanctionMatchScorer) {
        this(dataSource, sanctionMatchScorer, null);
    }

    @Autowired
    public RiskFactAssembler(CustomerDataPort dataSource, SanctionMatchScorer sanctionMatchScorer,
                             SanctionScreeningService sanctionScreeningService) {
        this.dataSource = dataSource;
        this.sanctionMatchScorer = sanctionMatchScorer;
        this.sanctionScreeningService = sanctionScreeningService;
    }

    /** 从客户数据源组装完整 RiskContext（制裁 + 交易聚合 + 新增风险事实） */
    public RiskContext assemble(CustomerProfile customer, String modelRiskLevel) {
        return assembleFrom(
                dataSource.transactionsOf(customer.id()),
                dataSource.shareholdingsOf(customer.id()),
                searchSanctions(customer),
                modelRiskLevel);
    }

    /**
     * 纯函数：从已冻结的原始数据计算 RiskContext，不访问数据源。
     * 用于快照工厂在单次读取后派生风险事实，保证 Agent 与 Guardrails 共享同一份数据。
     */
    public RiskContext assembleFrom(List<TransactionRecord> txns, List<ShareholdingRecord> shareholdings,
                                    List<SanctionRecord> hits, String modelRiskLevel) {
        // 名单等级采用“1 级最严重”的业务编码；多名单命中必须保留最严重（数值最小）的正等级。
        // 旧实现取 max 会在同时命中 1/2 级名单时把一级制裁错误降成二级。
        int maxSeverity = hits.stream().mapToInt(SanctionRecord::severity)
                .filter(severity -> severity > 0)
                .min()
                .orElse(0);
        boolean sanctionHit = !hits.isEmpty();

        long night = txns.stream().filter(t -> isNight(t.date())).count();
        long cross = txns.stream().filter(t -> t.country().isCrossBorder()).count();
        long large = txns.stream().filter(t -> t.amount().compareTo(MILLION) >= 0).count();
        double nightRatio = txns.isEmpty() ? 0 : 100.0 * night / txns.size();
        double crossRatio = txns.isEmpty() ? 0 : 100.0 * cross / txns.size();

        boolean dataComplete = !txns.isEmpty();
        boolean riskExplained = false; // Mock 数据源无业务材料概念；真实数据源应由结构化业务字段派生
        int patternSeverity = assessPatternSeverity(txns);
        int uboRiskSeverity = assessUboRisk(shareholdings);

        String modelLevel = modelRiskLevel == null ? RiskLevel.LOW.label() : modelRiskLevel;
        return new RiskContext(maxSeverity, sanctionHit, crossRatio, nightRatio, large,
                dataComplete, riskExplained, patternSeverity, uboRiskSeverity, modelLevel,
                RiskLevel.fromLabel(modelLevel).code());
    }

    /**
     * 检索制裁名单（按姓名 + 证件号），只把高置信候选送入 Guardrail。
     * 数据库模糊召回产生的低分候选仍可通过制裁筛查接口查看，但不会直接触发确定性制裁规则。
     */
    public List<SanctionRecord> searchSanctions(CustomerProfile customer) {
        if (sanctionScreeningService != null) {
            return sanctionScreeningService.actionableRecords(customer);
        }
        List<SanctionRecord> hits = new ArrayList<>(dataSource.searchSanctions(customer.name()));
        if (customer.idCard() != null && !customer.idCard().isBlank()) {
            hits.addAll(dataSource.searchSanctions(customer.idCard()));
        }
        return hits.stream().distinct()
                .filter(candidate -> sanctionMatchScorer.score(customer, candidate).actionable())
                .toList();
    }

    /** 交易模式严重度：拆分/分层（同一金额大量重复）→ 2，否则 0 */
    private int assessPatternSeverity(List<TransactionRecord> txns) {
        if (txns.isEmpty()) {
            return 0;
        }
        Map<BigDecimal, Long> amountCounts = txns.stream()
                .collect(Collectors.groupingBy(TransactionRecord::amount, Collectors.counting()));
        long maxSameAmount = amountCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return maxSameAmount >= 5 ? 2 : 0;
    }

    /** 受益所有人风险：境外/信托股东 → 2（无法核实）；关联公司/L2 → 1（材料待更新）；否则 0 */
    private int assessUboRisk(List<ShareholdingRecord> shareholdings) {
        boolean hasOffshore = shareholdings.stream().anyMatch(s ->
                s.holder().toUpperCase().contains("LTD")
                        || s.holder().toUpperCase().contains("TRUST")
                        || s.holder().contains("境外"));
        if (hasOffshore) {
            return 2;
        }
        boolean hasL2 = shareholdings.stream().anyMatch(s -> "L2".equals(s.level()));
        return hasL2 ? 1 : 0;
    }

    private boolean isNight(LocalDateTime date) {
        int hour = date.getHour();
        return hour >= 22 || hour < 6;
    }
}
