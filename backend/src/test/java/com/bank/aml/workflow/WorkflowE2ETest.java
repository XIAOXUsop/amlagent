package com.bank.aml.workflow;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.service.DueDiligenceService;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流端到端集成测试（复用本机 Docker 的 MySQL/Redis/PGVector）。
 * 运行：./mvnw -Pintegration-test test
 * <p>队列依赖独立的 Redis Stream/消费者组，且 Outbox 表为多个测试上下文共享：
 * 本类运行前强制销毁先前缓存的 Spring 上下文（关闭它们的 Outbox 发布器与消费者），
 * 避免其他测试上下文的后台轮询器与本类竞争投递同一批 Outbox 事件。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class WorkflowE2ETest {

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry registry) {
        com.bank.aml.testinfra.IntegrationTestDatabase.configure(registry, "aml_workflow_e2e_test");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        registry.add("aml.queue.stream", () -> "aml:workflow:cases-e2e-" + suffix);
        registry.add("aml.queue.dead-stream", () -> "aml:workflow:dead-e2e-" + suffix);
        registry.add("aml.queue.group", () -> "aml-workers-e2e-" + suffix);
    }

    @Autowired
    private DueDiligenceService service;
    @Autowired
    private ToolExecutionTraceRepository toolTraces;

    @Test
    void mockAgentCompletesFourToolsAndGuardrailCorrectsFinalRisk() throws Exception {
        CaseEntity c = service.createCase("C001", "大额频繁跨国转账、夜间集中交易", true);
        waitTerminal(c.getId());

        CaseEntity done = service.getCase(c.getId());
        assertThat(done.getStatus()).isEqualTo(CaseStatus.HOLD);
        assertThat(done.getRawRiskLevel()).isEqualTo("低风险");
        assertThat(done.getRiskLevel()).isEqualTo("高风险");
        assertThat(done.getReportJson()).isNotBlank();
        assertThat(done.getRawReportJson()).isNotBlank()
                .doesNotContain("customerId", "customerName", "C001", "张伟");
        assertThat(done.getExecutionVersion()).isEqualTo(1);
        assertThat(done.getReportSource()).isEqualTo("AGENT");
        assertThat(done.isModelFallback()).isTrue();
        var traces = toolTraces.findByCaseIdAndExecutionVersionOrderBySequenceNoAsc(done.getId(), 1);
        assertThat(traces).hasSize(4).allSatisfy(trace -> {
            assertThat(trace.isRequested()).isTrue();
            assertThat(trace.isExecuted()).isTrue();
            assertThat(trace.isSuccess()).isTrue();
            assertThat(trace.isArgumentValid()).isTrue();
        });
        assertThat(traces).extracting(trace -> trace.getToolName())
                .containsExactlyInAnyOrder("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal");
    }

    @Test
    void duplicateEnqueueIsIdempotent() throws Exception {
        CaseEntity c = service.createCase("C003", "常规监测", true);
        waitTerminal(c.getId());
        int version = service.getCase(c.getId()).getExecutionVersion();

        // 重复投递：已完成工单无法再次抢占，executionVersion 不变
        service.enqueue(c.getId());
        Thread.sleep(3000);
        assertThat(service.getCase(c.getId()).getExecutionVersion()).isEqualTo(version);
    }

    private void waitTerminal(Long caseId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            CaseEntity cur = service.getCase(caseId);
            if (List.of("DONE", "HOLD", "FAILED").contains(cur.getStatus().name())) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("工单未在超时时间内到达终态: " + caseId);
    }
}
