package com.bank.aml.tools;

import com.bank.aml.domain.TransactionRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易数据格式化工具：把交易记录聚合为可读的风险画像。
 * <p>当前作为纯静态格式化器被 {@link SnapshotToolSuite} 复用。
 * 注意：此工具不直接暴露给 LLM 作为 Agent Tool（避免无身份校验的 IDOR 越权面），
 * Agent 一律通过绑定冻结快照的 {@link SnapshotToolSuite} 读取数据。
 */
public final class TransactionTool {

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal HALF_MILLION = new BigDecimal("500000");

    private TransactionTool() {
    }

    /** 从已冻结的交易原始数据生成画像文本（快照套件复用同一格式化逻辑） */
    public static String format(List<TransactionRecord> txns, String customerId) {
        if (txns.isEmpty()) {
            return "未查询到交易记录。";
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
                近180天交易画像：
                - 交易笔数：%d 笔
                - 交易总额：约 %s 万元
                - 夜间交易（22:00-06:00）：%d 笔，占比 %.1f%%
                - 跨境交易：%d 笔，占比 %.1f%%，涉及地区：%s
                - 大额交易（≥100万）：%d 笔；（≥50万）：%d 笔
                - 去重交易对手数量：%d
                """.formatted(total, amountWan,
                nightCount, 100.0 * nightCount / total,
                cross.size(), 100.0 * cross.size() / total, countries.isEmpty() ? "无" : countries,
                largeCount, large50,
                txns.stream().map(TransactionRecord::counterparty).distinct().count());
    }

    private static boolean isNight(LocalDateTime date) {
        int hour = date.getHour();
        return hour >= 22 || hour < 6;
    }
}
