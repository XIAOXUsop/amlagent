package com.bank.aml.controller;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.dto.CaseDto;
import com.bank.aml.dto.CaseLogDto;
import com.bank.aml.service.DueDiligenceService;
import com.bank.aml.service.WorkflowEventService;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.bank.aml.tools.ToolExecutionTraceEntity;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final CustomerDataPort dataSource;
    private final ToolExecutionTraceRepository toolTraceRepository;

    public CaseController(DueDiligenceService service, WorkflowEventService workflowEventService,
                          CaseExecutionRepository caseExecutionRepository, CustomerDataPort dataSource,
                           ToolExecutionTraceRepository toolTraceRepository) {
        this.service = service;
        this.workflowEventService = workflowEventService;
        this.caseExecutionRepository = caseExecutionRepository;
        this.dataSource = dataSource;
        this.toolTraceRepository = toolTraceRepository;
    }

    /** 全部工单（倒序，分页） */
    @GetMapping
    public Page<CaseDto> list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "10") int size) {
        // 显式校验，避免 Spring PageRequest 抛英文内部错误（反人性），改为 400 中文提示
        if (page < 0) {
            throw new IllegalArgumentException("页码不能为负数");
        }
        if (size <= 0 || size > 100) {
            throw new IllegalArgumentException("每页条数需在 1 ~ 100 之间");
        }
        return service.listCasesPageable(PageRequest.of(page, size)).map(CaseDto::from);
    }

    /** 工单全量状态统计（态势概览） */
    @GetMapping("/stats")
    public DueDiligenceService.CaseStats stats() {
        return service.caseStats();
    }

    /** 创建预警工单；autoProcess 默认 true，创建后自动触发尽调（仅 ANALYST/ADMIN） */
    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public CaseDto create(@Valid @RequestBody CreateCaseRequest req) {
        boolean autoProcess = req.autoProcess() == null || req.autoProcess();
        return CaseDto.from(service.createCase(req.customerId(), req.alertRule(), autoProcess));
    }

    /** 手动触发处理（幂等：已在执行中的工单会因抢占失败而忽略；仅 ANALYST/ADMIN） */
    @PostMapping("/{id}/process")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
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

    /** 工具调用轨迹（按执行版本倒序，同一执行内按调用顺序返回；不暴露参数明文与完整结果） */
    @GetMapping("/{id}/tools")
    public List<ToolTraceDto> toolTraces(@PathVariable Long id) {
        return toolTraceRepository.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(id)
                .stream()
                .map(ToolTraceDto::from)
                .toList();
    }

    /** 人工重试（置回 PENDING 并重新入队；仅 ANALYST/ADMIN） */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public CaseDto retry(@PathVariable Long id) {
        return CaseDto.from(service.retry(id));
    }

    /** 可供选择的演示客户（脱敏 DTO，不含证件号） */
    @GetMapping("/customers")
    public List<CustomerSummary> customers() {
        return dataSource.allCustomers().stream().map(CustomerSummary::from).toList();
    }

    /** 演示客户摘要（脱敏，不返回证件号） */
    public record CustomerSummary(String id, String name, String type, String industry, String region, String regCapital) {
        static CustomerSummary from(CustomerProfile c) {
            return new CustomerSummary(c.id(), c.name(), c.type(), c.industry(), c.region(), c.regCapital());
        }
    }

    public record CreateCaseRequest(
            @NotBlank(message = "客户编号不能为空") String customerId,
            @Size(max = 500, message = "预警规则描述过长（最多 500 字）") String alertRule,
            Boolean autoProcess) {
    }

    /** 脱敏工具调用轨迹 DTO（不暴露参数明文、完整结果与结果摘要外的敏感字段） */
    public record ToolTraceDto(
            int executionVersion,
            long sequenceNo,
            String toolName,
            boolean success,
            boolean argumentValid,
            long durationMs,
            String resultDigest,
            String errorCode
    ) {
        static ToolTraceDto from(ToolExecutionTraceEntity e) {
            return new ToolTraceDto(e.getExecutionVersion(), e.getSequenceNo(), e.getToolName(),
                    e.isSuccess(), e.isArgumentValid(), e.getDurationMs(), e.getResultDigest(), e.getErrorCode());
        }
    }
}
