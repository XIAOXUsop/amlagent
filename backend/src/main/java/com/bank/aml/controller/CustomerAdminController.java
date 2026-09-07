package com.bank.aml.controller;

import com.bank.aml.audit.AuditService;
import com.bank.aml.dto.CustomerDto;
import com.bank.aml.service.CustomerAdminService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 客户/人员管理接口（仅 ADMIN）。
 * <p>新增/编辑/删除/导入后，新建预警工单下拉与 Agent 数据源会同步刷新。
 */
@RestController
@RequestMapping("/api/admin/customers")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerAdminController {

    private final CustomerAdminService customerAdminService;
    private final AuditService audit;

    public CustomerAdminController(CustomerAdminService customerAdminService, AuditService audit) {
        this.customerAdminService = customerAdminService;
        this.audit = audit;
    }

    @GetMapping
    public Page<CustomerDto> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  @RequestParam(required = false) String keyword) {
        if (page < 0) {
            throw new IllegalArgumentException("页码不能为负数");
        }
        if (size <= 0 || size > 100) {
            throw new IllegalArgumentException("每页条数需在 1 ~ 100 之间");
        }
        return customerAdminService.list(page, size, keyword);
    }

    /** 当前管理员查看的银行客户详情；证件号始终脱敏。 */
    @GetMapping("/{id}")
    public CustomerDto detail(@PathVariable Long id) {
        return customerAdminService.detail(id);
    }

    @PostMapping
    public CustomerDto create(@RequestBody CustomerAdminService.CreateRequest req) {
        CustomerDto created = customerAdminService.create(req, currentUser());
        // 客户主数据变更必须审计；明细字段（姓名/证件号）不写入审计，仅记主键与摘要
        audit.record(currentUser(), "CUSTOMER_CREATE", "CUSTOMER", String.valueOf(created.id()),
                "SUCCESS", "type=" + created.type(), null);
        return created;
    }

    @PutMapping("/{id}")
    public CustomerDto update(@PathVariable Long id, @RequestBody CustomerAdminService.UpdateRequest req) {
        CustomerDto updated = customerAdminService.update(id, req);
        audit.record(currentUser(), "CUSTOMER_UPDATE", "CUSTOMER", String.valueOf(id),
                "SUCCESS", "type=" + updated.type(), null);
        return updated;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        customerAdminService.delete(id);
        audit.record(currentUser(), "CUSTOMER_DELETE", "CUSTOMER", String.valueOf(id), "SUCCESS", null, null);
    }

    @PutMapping("/{id}/status")
    public CustomerDto setStatus(@PathVariable Long id, @RequestParam String status) {
        CustomerDto updated = customerAdminService.setStatus(id, status);
        audit.record(currentUser(), "CUSTOMER_STATUS_CHANGE", "CUSTOMER", String.valueOf(id),
                "SUCCESS", "status=" + status, null);
        return updated;
    }

    /** Excel 导入：表头 姓名/证件号/类型/行业/地区/注册资本 */
    @PostMapping("/import")
    public CustomerAdminService.ImportResult importExcel(@RequestParam("file") MultipartFile file) {
        CustomerAdminService.ImportResult result = customerAdminService.importExcel(file, currentUser());
        // 只审计导入规模，不落 Excel 内容
        audit.record(currentUser(), "CUSTOMER_IMPORT", "CUSTOMER", null, "SUCCESS",
                "total=" + result.total() + ",success=" + result.success()
                        + ",failed=" + result.failed() + ",errorCount=" + result.errors().size(), null);
        return result;
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
