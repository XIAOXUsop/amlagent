package com.bank.aml.controller;

import com.bank.aml.rag.LegalIndexMaintenanceService;
import com.bank.aml.rag.LegalIndexVersionService;
import com.bank.aml.rag.RagAdminAuditService;
import com.bank.aml.rag.RagAdminQueryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 法规索引发布、回滚、清理与隔离审计管理面。 */
@RestController
@RequestMapping("/api/admin/rag")
@PreAuthorize("hasRole('ADMIN')")
public class RagAdminController {

    private static final Logger log = LoggerFactory.getLogger(RagAdminController.class);

    private final LegalIndexVersionService versions;

    private final LegalIndexMaintenanceService maintenance;

    private final RagAdminQueryService queries;

    private final RagAdminAuditService audit;

    public RagAdminController(LegalIndexVersionService versions, LegalIndexMaintenanceService maintenance,
            RagAdminQueryService queries, RagAdminAuditService audit) {
        this.versions = versions;
        this.maintenance = maintenance;
        this.queries = queries;
        this.audit = audit;
    }

    @GetMapping("/indexes")
    public List<RagAdminQueryService.ManifestView> indexes() {
        return queries.manifests();
    }

    @PostMapping("/indexes/{version}/rollback")
    public ActionResult rollback(@PathVariable String version) {
        validateVersion(version);
        String actor = currentUser();
        // STARTED 必须先独立落库；若审计库不可用，不执行高风险管理动作。
        audit.record(actor, "INDEX_ROLLBACK", version, "STARTED", null);
        try {
            boolean changed = versions.rollback(version);
            if (!changed)
                throw new IllegalStateException("索引状态已变化，请刷新后重试");
            outcomeAudit(actor, "INDEX_ROLLBACK", version, "SUCCEEDED", null);
            return new ActionResult(true, version, "ROLLED_BACK");
        }
        catch (RuntimeException e) {
            outcomeAudit(actor, "INDEX_ROLLBACK", version, "FAILED", e.getClass().getSimpleName());
            throw e;
        }
    }

    @PostMapping("/indexes/cleanup")
    public LegalIndexMaintenanceService.CleanupResult cleanup() {
        String actor = currentUser();
        audit.record(actor, "INDEX_CLEANUP", null, "STARTED", null);
        try {
            var result = maintenance.cleanup();
            outcomeAudit(actor, "INDEX_CLEANUP", null, "SUCCEEDED", "DELETED_" + result.deletedVersions().size());
            return result;
        }
        catch (RuntimeException e) {
            outcomeAudit(actor, "INDEX_CLEANUP", null, "FAILED", e.getClass().getSimpleName());
            throw e;
        }
    }

    @GetMapping("/quarantines")
    public List<RagAdminQueryService.QuarantineView> quarantines() {
        return queries.quarantines();
    }

    private void validateVersion(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("索引版本格式错误");
        }
    }

    private String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }

    private void outcomeAudit(String actor, String action, String target, String outcome, String detail) {
        try {
            audit.record(actor, action, target, outcome, detail);
        }
        catch (RuntimeException auditFailure) {
            // STARTED 已落库，真实动作结果优先返回；避免“动作成功但接口报失败”诱发重复操作。
            log.error("RAG 管理动作结果审计写入失败: action={}, outcome={}", action, outcome, auditFailure);
        }
    }

    public record ActionResult(boolean success, String activeVersion, String status) {
    }

}
