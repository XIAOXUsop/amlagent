package com.bank.aml.audit;

import java.text.Normalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在业务事务内可靠登记高影响操作，实际审计记录由后台投递。 */
@Service
public class AuditOutboxService {

    private final AuditOutboxRepository repository;

    public AuditOutboxService(AuditOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void enqueue(String eventKey, String actor, String action, String targetType, String targetId,
            String detail) {
        String key = sanitize(eventKey, 160, null);
        if (key == null)
            throw new IllegalArgumentException("审计事件键不能为空");
        if (repository.existsByEventKey(key))
            return;
        AuditOutboxEvent event = new AuditOutboxEvent();
        event.setEventKey(key);
        event.setActor(sanitize(actor, 64, "unknown"));
        event.setActionName(sanitize(action, 64, "UNKNOWN"));
        event.setTargetType(sanitize(targetType, 64, null));
        event.setTargetId(sanitize(targetId, 64, null));
        event.setOutcome("SUCCESS");
        event.setDetail(sanitize(detail, 256, null));
        repository.save(event);
    }

    private String sanitize(String value, int max, String fallback) {
        if (value == null || value.isBlank())
            return fallback;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\p{Cntrl}", " ").strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

}
