package com.bank.aml.service;

import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工单 HTTP 查询所需的只读投影，隔离 Repository、实体与外部数据端口。 */
@Service
public class CaseQueryService {

    private final CaseExecutionRepository caseExecutions;

    private final CustomerDataPort customerData;

    private final ToolExecutionTraceRepository toolTraces;

    public CaseQueryService(CaseExecutionRepository caseExecutions, CustomerDataPort customerData,
            ToolExecutionTraceRepository toolTraces) {
        this.caseExecutions = caseExecutions;
        this.customerData = customerData;
        this.toolTraces = toolTraces;
    }

    @Transactional(readOnly = true)
    public List<CaseExecutionView> executions(Long caseId) {
        return caseExecutions.findByCaseIdOrderByStartedAtAsc(caseId)
            .stream()
            .map(execution -> new CaseExecutionView(execution.getExecutionVersion(), execution.getStage(),
                    execution.getStatus(), toInstant(execution.getStartedAt()), toInstant(execution.getCompletedAt()),
                    execution.getDurationMs(), execution.getErrorCode()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ToolTraceView> toolTraces(Long caseId) {
        return toolTraces.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(caseId)
            .stream()
            .map(trace -> new ToolTraceView(trace.getExecutionVersion(), trace.getSequenceNo(), trace.getToolName(),
                    trace.isSuccess(), trace.isArgumentValid(), trace.getDurationMs(), trace.getResultDigest(),
                    trace.getErrorCode()))
            .toList();
    }

    public List<CustomerSummary> customers() {
        return customerData.allCustomers().stream().map(CustomerSummary::from).toList();
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record CustomerSummary(String id, String name, String type, String industry, String region,
            String regCapital) {

        static CustomerSummary from(CustomerProfile customer) {
            return new CustomerSummary(customer.id(), customer.name(), customer.type(), customer.industry(),
                    customer.region(), customer.regCapital());
        }
    }

    public record ToolTraceView(int executionVersion, long sequenceNo, String toolName, boolean success,
            boolean argumentValid, long durationMs, String resultDigest, String errorCode) {
    }

    public record CaseExecutionView(int executionVersion, WorkflowStage stage, CaseExecution.ExecutionStatus status,
            Instant startedAt, Instant completedAt, Long durationMs, String errorCode) {
    }

}
