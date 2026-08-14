package com.bank.aml.tools;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.TransactionRecord;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易数据工具：查询客户近 180 天交易流水并聚合风险画像。
 */
@Component
public class TransactionTool {

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal HALF_MILLION = new BigDecimal("500000");

    private final CustomerDataPort dataSource;

    public TransactionTool(CustomerDataPort dataSource) {
        this.dataSource = dataSource;
    }

    @Tool("查询客户近180天交易画像，返回交易笔数、总额、夜间交易占比、跨境交易占比、大额交易笔数与涉及地区等风险特征")
    public String transactionProfile(@P("客户编号，如 C001") String customerId) {
        List<TransactionRecord> txns = dataSource.transactionsOf(customerId);
        if (txns.isEmpty()) {
            return "未查询到客户 " + customerId + " 的交易记录。";
        }

        int total = txns.size();
        BigDecimal totalAmount = txns.stream()
                .map(TransactionRecord::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long nightCount = txns.stream().filter(t -> isNight(t.date())).count();
        List<TransactionRecord> cross = txns.stream()
                .filter(t -> t.country().isCrossBorder()).toList();
        long largeCount = txns.stream().filter(t -> t.amount().compareTo(MILLION) >= 0).count();
        long large50 = txns.stream().filter(t -> t.amount().compareTo(HALF_MILLION) >= 0).count();
        Map<String, Long> countries = cross.stream()
                .collect(Collectors.groupingBy(t -> t.country().label(), Collectors.counting()));
        String amountWan = totalAmount.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP).toPlainString();

        return """
                客户 %s 近180天交易画像：
                - 交易笔数：%d 笔
                - 交易总额：约 %s 万元
                - 夜间交易（22:00-06:00）：%d 笔，占比 %.1f%%
                - 跨境交易：%d 笔，占比 %.1f%%，涉及地区：%s
                - 大额交易（≥100万）：%d 笔；（≥50万）：%d 笔
                - 典型交易对手含：%s
                """.formatted(customerId, total, amountWan,
                nightCount, 100.0 * nightCount / total,
                cross.size(), 100.0 * cross.size() / total, countries.isEmpty() ? "无" : countries,
                largeCount, large50,
                txns.stream().map(TransactionRecord::counterparty).distinct().limit(5).collect(Collectors.joining("、")));
    }

    private boolean isNight(LocalDateTime date) {
        int hour = date.getHour();
        return hour >= 22 || hour < 6;
    }
}
