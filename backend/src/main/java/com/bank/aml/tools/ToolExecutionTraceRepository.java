package com.bank.aml.tools;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolExecutionTraceRepository extends JpaRepository<ToolExecutionTraceEntity, Long> {

    /** 按执行版本倒序（最近执行在前）、同一执行内按调用序号正序返回某个工单的工具调用轨迹 */
    List<ToolExecutionTraceEntity> findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(Long caseId);
}
