package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.messaging.ExecutionLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 将已经落库的最终结构化报告确定性地转换为 SSE 摘要。
 *
 * <p>这里故意不再发起第二次 LLM 调用：前端看到的流式内容与最终评级、发现和动作来自同一份报告，
 * 不会产生双模型调用的额外 Token/延迟，也不会出现“流式解释”和最终报告互相矛盾。
 */
@Service
public class FinalReportStreamingService {

    private static final Logger log = LoggerFactory.getLogger(FinalReportStreamingService.class);

    private final WorkflowEventService workflowEventService;
    private final ExecutorService summaryExecutor;

    public FinalReportStreamingService(WorkflowEventService workflowEventService,
                                       ExecutorService summaryExecutor) {
        this.workflowEventService = workflowEventService;
        this.summaryExecutor = summaryExecutor;
    }

    /** 提交成功返回 true；提交失败由调用方直接发送终态。 */
    public boolean stream(Long caseId, CaseStatus terminalStatus,
                          DueDiligenceReport report, ExecutionLease lease) {
        List<String> chunks = renderChunks(report);
        try {
            summaryExecutor.execute(() -> {
                try {
                    for (String chunk : chunks) {
                        if (lease != null && !lease.isValid()) {
                            return;
                        }
                        workflowEventService.emitToken(caseId, chunk);
                    }
                } catch (RuntimeException exception) {
                    log.warn("确定性报告流推送失败 caseId={}", caseId, exception);
                } finally {
                    if (lease == null || lease.isValid()) {
                        workflowEventService.complete(caseId, terminalStatus);
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            log.warn("确定性报告流任务提交失败 caseId={}", caseId, exception);
            return false;
        }
    }

    List<String> renderChunks(DueDiligenceReport report) {
        String findings = join(report.findingCodes());
        String actions = join(report.actionCodes());
        return List.of(
                "最终风险评级：" + report.riskLevel() + "。\n",
                "风险发现：" + findings + "。\n",
                "处置动作：" + actions + "。\n",
                Boolean.TRUE.equals(report.manualReviewRequired())
                        ? "该工单需要人工复核。"
                        : "该工单可按自动流程继续处理。"
        );
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "无结构化代码" : String.join("、", values);
    }
}
