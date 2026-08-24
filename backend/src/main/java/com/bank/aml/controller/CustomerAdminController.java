package com.bank.aml.controller;

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

    public CustomerAdminController(CustomerAdminService customerAdminService) {
        this.customerAdminService = customerAdminService;
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
        return customerAdminService.create(req, currentUser());
    }

    @PutMapping("/{id}")
    public CustomerDto update(@PathVariable Long id, @RequestBody CustomerAdminService.UpdateRequest req) {
        return customerAdminService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        customerAdminService.delete(id);
    }

    @PutMapping("/{id}/status")
    public CustomerDto setStatus(@PathVariable Long id, @RequestParam String status) {
        return customerAdminService.setStatus(id, status);
    }

    /** Excel 导入：表头 姓名/证件号/类型/行业/地区/注册资本 */
    @PostMapping("/import")
    public CustomerAdminService.ImportResult importExcel(@RequestParam("file") MultipartFile file) {
        return customerAdminService.importExcel(file, currentUser());
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
