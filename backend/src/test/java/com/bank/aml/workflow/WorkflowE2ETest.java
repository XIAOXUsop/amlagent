package com.bank.aml.workflow;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.service.DueDiligenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流端到端集成测试（复用本机 Docker 的 MySQL/Redis/PGVector）。
 * 运行：./mvnw test -Dgroups=integration
 * <p>队列依赖独立的 Redis Stream/消费者组，且 Outbox 表为多个测试上下文共享：
 * 本类运行前强制销毁先前缓存的 Spring 上下文（关闭它们的 Outbox 发布器与消费者），
 * 避免其他测试上下文的后台轮询器与本类竞争投递同一批 Outbox 事件。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class WorkflowE2ETest {

    @Autowired
    private DueDiligenceService service;

    @Test
    void autoProcessCompletesWorkflow() throws Exception {
        CaseEntity c = service.createCase("C001", "大额频繁跨国转账、夜间集中交易", true);
        waitTerminal(c.getId());

        CaseEntity done = service.getCase(c.getId());
        assertThat(done.getStatus()).isIn(CaseStatus.DONE, CaseStatus.HOLD);
        assertThat(done.getRiskLevel()).isNotBlank();
        assertThat(done.getReportJson()).isNotBlank();
        assertThat(done.getExecutionVersion()).isEqualTo(1);
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
