package com.bank.aml.refund;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 退款分配数据访问（G2-1）。 */
public interface RefundAllocationRepository extends JpaRepository<RefundAllocation, Long> {

    List<RefundAllocation> findByRefundEventIdOrderByIdAsc(Long refundEventId);

    List<RefundAllocation> findByCaseIdAndOriginalTransactionIdOrderByIdAsc(Long caseId, String originalTransactionId);

}
