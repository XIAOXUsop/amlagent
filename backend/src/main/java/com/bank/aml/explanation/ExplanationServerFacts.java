package com.bank.aml.explanation;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.domain.TransactionRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 案件客户的**权威交易事实**：从服务端数据源取交易号→金额、交易号→发生日。
 *
 * <h2>为什么这两张表必须来自服务端</h2> 客户端在草稿里声明的付款日期与金额都不可信——它们决定"这笔代付是否在授权有效期内、
 * 是否超出授权额度"。若按客户端给的数字判定，被审核方只要把日期往前写一天、 把金额写小一点，就能把一笔越权代付解释成合规代付。
 *
 * <p>
 * 因此这里只认 {@link CustomerDataPort} 里的记录，客户端传什么都不参与判定。
 *
 * <h2>来源读不到时不降级</h2> 读取失败返回**空集合**，调用方据此按"来源数据缺失"阻断；而不是退回客户端声明值，
 * 也不是静默当作"没有这笔交易"。缺数据与没问题是两件事。
 */
final class ExplanationServerFacts {

    private final CustomerDataPort customerDataPort;

    ExplanationServerFacts(CustomerDataPort customerDataPort) {
        this.customerDataPort = customerDataPort;
    }

    /** 交易来源记录号 → 金额；读不到来源时返回空集合 */
    Map<String, BigDecimal> amountsOf(CaseEntity caseEntity) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        try {
            for (TransactionRecord transaction : customerDataPort.transactionsOf(caseEntity.getCustomerId())) {
                if (transaction.sourceRecordId() == null || transaction.sourceRecordId().isBlank()) {
                    continue;
                }
                amounts.putIfAbsent(transaction.sourceRecordId(), transaction.amount());
            }
        }
        catch (RuntimeException e) {
            // 来源读取失败：返回空集合 → 调用方按"来源数据缺失"阻断，不静默降级为可解释。
            return Map.of();
        }
        return amounts;
    }

    /** 交易来源记录号 → 交易发生日；授权有效期必须用它，客户端 authority.paymentDate 不参与判定 */
    Map<String, LocalDate> datesOf(CaseEntity caseEntity) {
        Map<String, LocalDate> dates = new LinkedHashMap<>();
        try {
            for (TransactionRecord transaction : customerDataPort.transactionsOf(caseEntity.getCustomerId())) {
                if (transaction.sourceRecordId() == null || transaction.sourceRecordId().isBlank()
                        || transaction.date() == null) {
                    continue;
                }
                dates.putIfAbsent(transaction.sourceRecordId(), transaction.date().toLocalDate());
            }
        }
        catch (RuntimeException e) {
            return Map.of();
        }
        return dates;
    }

}
