package com.bank.aml.service;

import com.bank.aml.TestClocks;
import com.bank.aml.agent.AgentAnalysis;
import com.bank.aml.agent.DueDiligenceAgent;
import com.bank.aml.agent.DueDiligenceAgentFactory;
import com.bank.aml.agent.InvestigationSnapshotFactory;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.agent.validation.AgentOutputValidator;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.fault.FaultInjector;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.cost.CostRouter;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.RiskContext;
import com.bank.aml.investigation.AlertSnapshotAssembler;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.CaseIntakeService;
import com.bank.aml.investigation.InvestigationService;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.observability.MetricsRecorder;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.security.PromptInjectionGuard;
import com.bank.aml.tools.SnapshotToolSuite;
import com.bank.aml.tools.ToolExecutionTrace;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T01/T02：自动分析完成与最终结案分离。 调查契约 v1 的案件自动分析后进入 HOLD 待人工处置；版本 0 保留自动完成行为。
 */
class DueDiligenceServiceTest {

    private final CaseRepository cases = mock(CaseRepository.class);

    private final CaseLogRepository logs = mock(CaseLogRepository.class);

    private final CaseExecutionRepository executions = mock(CaseExecutionRepository.class);

    private final WorkflowCommandService commands = mock(WorkflowCommandService.class);

    private final MetricsRecorder metrics = mock(MetricsRecorder.class);

    private final DueDiligenceAgentFactory agentFactory = mock(DueDiligenceAgentFactory.class);

    private final RuleBasedReporter ruleReporter = mock(RuleBasedReporter.class);

    private final InvestigationSnapshotFactory snapshotFactory = mock(InvestigationSnapshotFactory.class);

    private final WorkflowEventService events = mock(WorkflowEventService.class);

    private final CustomerDataPort dataSource = mock(CustomerDataPort.class);

    private final AmlAlertRepository alertRepository = mock(AmlAlertRepository.class);

    private final AlertSnapshotAssembler alertAssembler = mock(AlertSnapshotAssembler.class);

    private final FaultInjector faultInjector = mock(FaultInjector.class);

    private final FinalReportStreamingService streaming = mock(FinalReportStreamingService.class);

    private final SnapshotArchiveService archive = mock(SnapshotArchiveService.class);

    private final ToolExecutionTraceRepository toolTraces = mock(ToolExecutionTraceRepository.class);

    private final InvestigationService investigation = mock(InvestigationService.class);

    private final CaseIntakeService intake = mock(CaseIntakeService.class);

    private final RiskRuleEngine ruleEngine = mock(RiskRuleEngine.class);

    private final RiskFactAssembler riskFactAssembler = mock(RiskFactAssembler.class);

    private LlmProperties llmProperties;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.setActiveProvider("deepseek");
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setApiKey("test-key");
        provider.setModelName("test-model");
        llmProperties.getProviders().put("deepseek", provider);
        when(ruleEngine.evaluate(any())).thenReturn(List.of());
        when(cases.finishCase(any(), anyString(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean()))
            .thenReturn(1);
    }

    private DueDiligenceService service() {
        return new DueDiligenceService(cases, logs, executions, commands, metrics, agentFactory, ruleReporter,
                new GuardrailEngine(riskFactAssembler, ruleEngine), new AgentOutputValidator(),
                new FinalDecisionAssembler(), snapshotFactory, events, dataSource, alertRepository, alertAssembler,
                faultInjector, new PromptInjectionGuard(), new CostRouter(), false, false, streaming,
                new ObjectMapper().findAndRegisterModules(), archive, toolTraces, llmProperties, intake,
                TestClocks.FIXED);
    }

    /** T01：契约 v1 低风险正常输出 → HOLD，报告人工标志/动作/正文一致。 */
    @Test
    void contractV1LowRiskNormalOutputEndsInHoldWithConsistentManualReview() {
        CaseEntity entity = caseEntity(1);
        when(cases.findById(7L)).thenReturn(Optional.of(entity));
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(entity));
        when(dataSource.findCustomer("C001")).thenReturn(Optional.of(customer()));
        when(alertRepository.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of());
        when(alertAssembler.fromLinkedAlerts(eq(7L), any())).thenReturn(List.of(alertSnapshot()));
        InvestigationSnapshot snapshot = snapshot(1);
        when(snapshotFactory.create(eq(7L), eq(1), any(), eq((String) null), anyList())).thenReturn(snapshot);
        stubAgent(snapshot);

        CaseEntity result = service().process(7L, "worker-1", 1, null);

        assertThat(result.getStatus()).isEqualTo(CaseStatus.HOLD);
        // 契约策略 HOLD 不改变风险评级：低风险进入人工处理不等于提升为高风险
        assertThat(entity.getRiskLevel()).isEqualTo("低风险");
        assertThat(entity.getReportSource()).isEqualTo("AGENT");

        ArgumentCaptor<CaseStatus> statusCaptor = ArgumentCaptor.forClass(CaseStatus.class);
        verify(cases).finishCase(eq(7L), eq("worker-1"), eq(1), statusCaptor.capture(), eq("低风险"), eq("低风险"),
                anyString(), anyString(), anyString(), eq("AGENT"), eq("case-7-v1"), eq("deepseek"), eq("test-model"),
                anyBoolean());
        assertThat(statusCaptor.getValue()).isEqualTo(CaseStatus.HOLD);

        String reportJson = entity.getReportJson();
        assertThat(reportJson).contains("\"manualReviewRequired\":true");
        assertThat(reportJson).contains("MANUAL_REVIEW");
        assertThat(reportJson).contains("人工复核");
        // 终态事件推送到前端（SSE），由工作流结束统一刷新
        verify(events).complete(7L, CaseStatus.HOLD);
    }

    /** T02：版本 0 自动完成行为不变。 */
    @Test
    void contractV0NormalOutputStillAutoCompletes() {
        CaseEntity entity = caseEntity(0);
        when(cases.findById(7L)).thenReturn(Optional.of(entity));
        when(dataSource.findCustomer("C001")).thenReturn(Optional.of(customer()));
        when(alertRepository.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of());
        when(alertAssembler.fromLinkedAlerts(eq(7L), any())).thenReturn(List.of(alertSnapshot()));
        InvestigationSnapshot snapshot = snapshot(0);
        when(snapshotFactory.create(eq(7L), eq(1), any(), eq((String) null), anyList())).thenReturn(snapshot);
        stubAgent(snapshot);

        CaseEntity result = service().process(7L, "worker-1", 1, null);

        assertThat(result.getStatus()).isEqualTo(CaseStatus.DONE);
        verify(cases).finishCase(eq(7L), eq("worker-1"), eq(1), eq(CaseStatus.DONE), eq("低风险"), eq("低风险"), anyString(),
                anyString(), anyString(), eq("AGENT"), eq("case-7-v1"), eq("deepseek"), eq("test-model"), anyBoolean());
        verify(events).complete(7L, CaseStatus.DONE);
    }

    /** T01 补充：契约 v1 案件缺少有效关联预警时给出明确数据错误，不静默退回泛化输入。 */
    @Test
    void contractV1WithoutLinkedAlertsIsRejected() {
        CaseEntity entity = caseEntity(1);
        when(cases.findById(7L)).thenReturn(Optional.of(entity));
        when(dataSource.findCustomer("C001")).thenReturn(Optional.of(customer()));
        when(alertRepository.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of());
        when(alertAssembler.fromLinkedAlerts(eq(7L), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service().process(7L, "worker-1", 1, null))
            .isInstanceOf(NonRetryableWorkflowException.class)
            .hasMessageContaining("没有有效关联预警");
    }

    /** A2：启动前容量前置校验 —— 超限案件保持可拆分的 PENDING 状态，不进入执行后才失败。 */
    @Test
    void startingInvestigationBeyondCapacityIsRejectedBeforeEnqueue() {
        CaseEntity entity = caseEntity(1);
        when(cases.findByIdForUpdate(7L)).thenReturn(Optional.of(entity));
        when(alertRepository.countByCaseIdAndStatus(7L, AlertStatus.LINKED)).thenReturn(9L);

        assertThatThrownBy(() -> service().trigger(7L)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("容量上限");
        // 未入队：案件保持 PENDING，用户可拆分后重新开始
        verify(commands, never()).triggerManual(anyLong(), anyInt());
        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING);
    }

    // ---- 辅助 ----

    private CaseEntity caseEntity(int contractVersion) {
        CaseEntity entity = new CaseEntity();
        try {
            var field = CaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, 7L);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        entity.setCustomerId("C001");
        entity.setAlertRule("常规监测");
        entity.setStatus(CaseStatus.PENDING);
        entity.setExecutionVersion(1);
        entity.setInvestigationContractVersion(contractVersion);
        return entity;
    }

    private CustomerProfile customer() {
        return new CustomerProfile("C001", "张伟", "110101198506123456", "企业法人", "国际贸易", "上海", "5000万");
    }

    private InvestigationAlertSnapshot alertSnapshot() {
        return new InvestigationAlertSnapshot(11L, "ALERT-A", "RULE-001", "PROFILE_MISMATCH", "交易与客户画像不匹配",
                LocalDateTime.of(2026, 8, 1, 10, 0), 0);
    }

    private InvestigationSnapshot snapshot(int contractVersion) {
        CustomerProfile customer = customer();
        RiskContext riskFacts = new RiskContext(0, false, 0, 0, 0, true, true, 0, 0, "低风险", 1);
        return new InvestigationSnapshot("case-7-v1", 7L, 1, Instant.parse("2026-08-19T00:00:00Z"), customer,
                contractVersion >= 1 ? List.of(alertSnapshot()) : List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of("尽职调查"), riskFacts, "v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                contractVersion >= 1 ? "alerts-digest" : null,
                contractVersion >= 1 ? InvestigationSnapshot.SCHEMA_VERSION_WITH_ALERTS
                        : InvestigationSnapshot.SCHEMA_VERSION_LEGACY);
    }

    private void stubAgent(InvestigationSnapshot snapshot) {
        DueDiligenceAgent agent = mock(DueDiligenceAgent.class);
        SnapshotToolSuite tools = mock(SnapshotToolSuite.class);
        when(tools.traces()).thenReturn(List.<ToolExecutionTrace>of());
        when(agentFactory.createWithTraces(snapshot))
            .thenReturn(new DueDiligenceAgentFactory.AgentWithTools(agent, tools));
        when(agent.investigate(anyString())).thenReturn(lowRiskAnalysis());
    }

    private AgentAnalysis lowRiskAnalysis() {
        // 自由文本不得包含客户身份（C001 / 张伟 / 证件号），否则生产契约校验会拒绝
        return new AgentAnalysis("低风险", "近期交易笔数与金额稳定，未见异常集中", "股权结构清晰，受益所有人为自然人", List.of(),
                List.of("依据客户尽职调查监管要求完成持续监测"), List.of("暂无新增异常交易模式"), "基于冻结证据维持常规持续监测，无需升级处置",
                List.of("交易画像：近期交易笔数与金额稳定"), false, List.of("NORMAL_TRANSACTION_PATTERN", "NO_SANCTION_HIT"),
                List.of("MAINTAIN_STANDARD_MONITORING"));
    }

}
