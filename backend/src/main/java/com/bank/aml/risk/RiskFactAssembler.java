package com.bank.aml.risk;

import com.bank.aml.datasource.mock.MockDataSource;
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
 * 生产链路与评测链路共用同一组装逻辑。
 */
@Component
public class RiskFactAssembler {

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final MockDataSource dataSource;

    public RiskFactAssembler(MockDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 从客户数据源组装完整 RiskContext（制裁 + 交易聚合 + 新增风险事实） */
    public RiskContext assemble(MockDataSource.Customer customer, String modelRiskLevel) {
        List<MockDataSource.SanctionEntry> hits = searchSanctions(customer);
        int maxSeverity = hits.stream().mapToInt(MockDataSource.SanctionEntry::severity).max().orElse(0);
        boolean sanctionHit = !hits.isEmpty();

        var txns = dataSource.transactionsOf(customer.id());
        long night = txns.stream().filter(t -> isNight(t.date())).count();
        long cross = txns.stream().filter(t -> t.country().isCrossBorder()).count();
        long large = txns.stream().filter(t -> t.amount().compareTo(MILLION) >= 0).count();
        double nightRatio = txns.isEmpty() ? 0 : 100.0 * night / txns.size();
        double crossRatio = txns.isEmpty() ? 0 : 100.0 * cross / txns.size();

        boolean dataComplete = !txns.isEmpty();
        boolean riskExplained = false; // Mock 数据源无业务材料概念，默认未解释
        int patternSeverity = assessPatternSeverity(txns);
        int uboRiskSeverity = assessUboRisk(dataSource.shareholdingsOf(customer.id()));

        String modelLevel = modelRiskLevel == null ? "低风险" : modelRiskLevel;
        return new RiskContext(maxSeverity, sanctionHit, crossRatio, nightRatio, large,
                dataComplete, riskExplained, patternSeverity, uboRiskSeverity, modelLevel, levelCode(modelLevel));
    }

    /** 检索制裁名单（按姓名 + 证件号），返回命中条目 */
    public List<MockDataSource.SanctionEntry> searchSanctions(MockDataSource.Customer customer) {
        List<MockDataSource.SanctionEntry> hits = new ArrayList<>(dataSource.searchSanctions(customer.name()));
        if (customer.idCard() != null && !customer.idCard().isBlank()) {
            hits.addAll(dataSource.searchSanctions(customer.idCard()));
        }
        return hits.stream().distinct().toList();
    }

    /** 交易模式严重度：拆分/分层（同一金额大量重复）→ 2，否则 0 */
    private int assessPatternSeverity(List<MockDataSource.Transaction> txns) {
        if (txns.isEmpty()) {
            return 0;
        }
        Map<BigDecimal, Long> amountCounts = txns.stream()
                .collect(Collectors.groupingBy(MockDataSource.Transaction::amount, Collectors.counting()));
        long maxSameAmount = amountCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return maxSameAmount >= 5 ? 2 : 0;
    }

    /** 受益所有人风险：境外/信托股东 → 2（无法核实）；关联公司/L2 → 1（材料待更新）；否则 0 */
    private int assessUboRisk(List<MockDataSource.Shareholding> shareholdings) {
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

    private int levelCode(String level) {
        return switch (level) {
            case "高风险" -> 3;
            case "中风险" -> 2;
            default -> 1;
        };
    }
}
