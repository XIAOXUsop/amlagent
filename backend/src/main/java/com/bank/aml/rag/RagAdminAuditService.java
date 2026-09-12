package com.bank.aml.rag;

import com.bank.aml.datasource.entity.RagAdminAuditEntity;
import com.bank.aml.datasource.repository.RagAdminAuditRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 管理动作独立事务审计，业务动作失败时审计记录也不能被回滚。 */
@Service
public class RagAdminAuditService {

    private final RagAdminAuditRepository repository;

    private final Clock clock;

    public RagAdminAuditService(RagAdminAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String target, String outcome, String detailCode) {
        RagAdminAuditEntity entity = new RagAdminAuditEntity();
        entity.setActor(actor == null || actor.isBlank() ? "unknown" : actor);
        entity.setActionName(action);
        entity.setTargetVersion(target == null || target.isBlank() ? null : target);
        entity.setOutcome(outcome);
        entity.setDetailCode(detailCode);
        entity.setOccurredAt(LocalDateTime.now(clock));
        repository.save(entity);
    }

}
