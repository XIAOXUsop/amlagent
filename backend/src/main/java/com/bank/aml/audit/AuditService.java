package com.bank.aml.audit;

import com.bank.aml.datasource.entity.AuditLogEntity;
import com.bank.aml.datasource.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;

/**
 * 平台敏感操作统一审计：审批决策、客户管理、死信重放、登录事件、调试动作。
 * <ul>
 *   <li>编程式 REQUIRES_NEW 独立事务：业务事务回滚不能抹掉审计记录；审计写入失败只告警、
 *       不阻断业务（异常在本方法内消化，且内层 rollback-only 不会外泄到调用方事务）；</li>
 *   <li>detail 仅保存短摘要（决策码/计数/原因码），对自由文本做控制字符清理（含换行，防伪造审计行）与截断。</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int DETAIL_MAX = 256;

    private final AuditLogRepository repository;
    private final TransactionTemplate auditTx;

    public AuditService(AuditLogRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.auditTx = new TransactionTemplate(transactionManager);
        // 审计必须独立提交：外层业务回滚不连坐审计；内层失败不污染外层事务
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String actor, String action, String targetType, String targetId,
                       String outcome, String detail, String clientIp) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActor(sanitize(actor, 64, "unknown"));
        entity.setAction(sanitize(action, 64, "UNKNOWN"));
        entity.setTargetType(blankToNull(sanitize(targetType, 64, null)));
        entity.setTargetId(blankToNull(sanitize(targetId, 64, null)));
        entity.setOutcome("FAILURE".equalsIgnoreCase(outcome) ? "FAILURE" : "SUCCESS");
        entity.setDetail(blankToNull(sanitize(detail, DETAIL_MAX, null)));
        entity.setClientIp(blankToNull(sanitize(clientIp, 64, null)));
        try {
            auditTx.executeWithoutResult(status -> repository.save(entity));
        } catch (RuntimeException e) {
            // 审计是合规强需求：失败必须告警可见，但不让审计故障放大为业务不可用。
            log.error("审计记录写入失败 action={} actor={}", action, actor, e);
        }
    }

    /** 清除全部控制字符（含换行——防止伪造审计记录行），NFKC 归一化并截断。 */
    private String sanitize(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cntrl}", " ")
                .strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}