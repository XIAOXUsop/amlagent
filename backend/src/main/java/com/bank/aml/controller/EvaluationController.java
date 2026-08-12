package com.bank.aml.controller;

import com.bank.aml.evaluation.AgentEvaluator;
import com.bank.aml.evaluation.EvalReportEntity;
import com.bank.aml.evaluation.EvalReportRepository;
import com.bank.aml.evaluation.RagEvaluator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测接口：RAG 检索质量评测 / Agent 风险评测 / 历史报告。
 */
@RestController
@RequestMapping("/api/eval")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {

    private final RagEvaluator ragEvaluator;
    private final AgentEvaluator agentEvaluator;
    private final EvalReportRepository evalReportRepository;

    public EvaluationController(RagEvaluator ragEvaluator, AgentEvaluator agentEvaluator,
                                EvalReportRepository evalReportRepository) {
        this.ragEvaluator = ragEvaluator;
        this.agentEvaluator = agentEvaluator;
        this.evalReportRepository = evalReportRepository;
    }

    /** RAG 检索评测：Recall@5 / Top3 命中率 / MRR / P95 */
    @PostMapping("/rag")
    public RagEvaluator.RagEvalReport rag() {
        return ragEvaluator.evaluate();
    }

    /** Agent 风险评测：高风险召回率 / 误报率 / 准确率 / 混淆矩阵 / 一级制裁漏报 */
    @PostMapping("/run")
    public AgentEvaluator.AgentEvalReport run() {
        AgentEvaluator.AgentEvalReport report = agentEvaluator.run();
        EvalReportEntity entity = new EvalReportEntity();
        entity.setEvalType("AGENT");
        entity.setVersionTag("default");
        entity.setMetricsJson(agentEvaluator.metricsJson(report));
        evalReportRepository.save(entity);
        return report;
    }

    /** 历史评测报告 */
    @GetMapping("/reports")
    public List<EvalReportEntity> reports() {
        return evalReportRepository.findByEvalTypeOrderByCreatedAtDesc("AGENT");
    }
}
