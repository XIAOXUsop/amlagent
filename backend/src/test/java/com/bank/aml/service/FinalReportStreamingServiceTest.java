package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.common.enums.CaseStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FinalReportStreamingServiceTest {

    @Test
    void streamsOnlyFinalStructuredDecisionWithoutSecondModelCall() {
        WorkflowEventService events = mock(WorkflowEventService.class);
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        FinalReportStreamingService service = new FinalReportStreamingService(events, executor);
        DueDiligenceReport report = report();

        boolean submitted = service.stream(42L, CaseStatus.HOLD, report, null);

        assertThat(submitted).isTrue();
        assertThat(service.renderChunks(report)).containsExactly(
                "最终风险评级：高风险。\n",
                "风险发现：SANCTION_LEVEL_1_MATCH。\n",
                "处置动作：MANUAL_REVIEW。\n",
                "该工单需要人工复核。"
        );
        verify(events).emitToken(42L, "最终风险评级：高风险。\n");
        verify(events).complete(42L, CaseStatus.HOLD);
    }

    private DueDiligenceReport report() {
        return new DueDiligenceReport(
                "C001", "[trusted]", "高风险", "summary", "conclusion-with-untrusted-free-text",
                List.of(), List.of(), List.of(), "actions", List.of(), true,
                List.of("SANCTION_LEVEL_1_MATCH"), List.of("MANUAL_REVIEW")
        );
    }
}
