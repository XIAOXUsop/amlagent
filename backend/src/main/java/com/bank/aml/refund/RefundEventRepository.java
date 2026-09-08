package com.bank.aml.refund;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 退款事件数据访问（G2-1）。 */
public interface RefundEventRepository extends JpaRepository<RefundEvent, Long> {

    Optional<RefundEvent> findByCaseIdAndSourceSystemAndExternalEventId(
            Long caseId, String sourceSystem, String externalEventId);

    List<RefundEvent> findByCaseIdOrderByIdAsc(Long caseId);
}
