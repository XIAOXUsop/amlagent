package com.bank.aml.controller;

import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.dto.CaseDto;
import com.bank.aml.dto.CaseLogDto;
import com.bank.aml.service.DueDiligenceService;
import com.bank.aml.service.WorkflowEventService;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 反洗钱预警工单 REST API（返回 DTO，不暴露 JPA 实体）。
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final DueDiligenceService service;
    private final WorkflowEventService workflowEventService;
    private final CaseExecutionRepository caseExecutionRepository;
    private final MockDataSource dataSource;

    public CaseController(DueDiligenceService service, WorkflowEventService workflowEventService,
                          CaseExecutionRepository caseExecutionRepository, MockDataSource dataSource) {
        this.service = service;
        this.workflowEventService = workflowEventService;
        this.caseExecutionRepository = caseExecutionRepository;
        this.dataSource = dataSource;
    }

    /** 全部工单（倒序，分页） */
    @GetMapping
    public Page<CaseDto> list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "10") int size) {
        return service.listCasesPageable(PageRequest.of(page, Math.min(size, 100))).map(CaseDto::from);
    }

    /** 创建预警工单；autoProcess 默认 true，创建后自动触发尽调 */
    @PostMapping
    public CaseDto create(@Valid @RequestBody CreateCaseRequest req) {
        boolean autoProcess = req.autoProcess() == null || req.autoProcess();
        return CaseDto.from(service.createCase(req.customerId(), req.alertRule(), autoProcess));
    }

    /** 手动触发处理（幂等：已在执行中的工单会因抢占失败而忽略） */
    @PostMapping("/{id}/process")
    public CaseDto process(@PathVariable Long id) {
        service.trigger(id);
        return CaseDto.from(service.getCase(id));
    }

    /** 订阅工单工作流实时进度（SSE） */
    @GetMapping("/{id}/events")
    public SseEmitter events(@PathVariable Long id) {
        return workflowEventService.subscribe(id);
    }

    /** 工单详情 */
    @GetMapping("/{id}")
    public CaseDto detail(@PathVariable Long id) {
        return CaseDto.from(service.getCase(id));
    }

    /** 工单工作流日志 */
    @GetMapping("/{id}/logs")
    public List<CaseLogDto> logs(@PathVariable Long id) {
        return service.listLogs(id).stream().map(CaseLogDto::from).toList();
    }

    /** 阶段执行记录（检查点） */
    @GetMapping("/{id}/executions")
    public List<CaseExecution> executions(@PathVariable Long id) {
        return caseExecutionRepository.findByCaseIdOrderByStartedAtAsc(id);
    }

    /** 人工重试（置回 PENDING 并重新入队） */
    @PostMapping("/{id}/retry")
    public CaseDto retry(@PathVariable Long id) {
        return CaseDto.from(service.retry(id));
    }

    /** 可供选择的演示客户 */
    @GetMapping("/customers")
    public List<MockDataSource.Customer> customers() {
        return dataSource.allCustomers();
    }

    public record CreateCaseRequest(
            @NotBlank(message = "客户编号不能为空") String customerId,
            String alertRule,
            Boolean autoProcess) {
    }
}
