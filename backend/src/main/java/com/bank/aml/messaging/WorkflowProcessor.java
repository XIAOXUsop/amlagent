package com.bank.aml.messaging;

/** 消息消费适配器调用的工作流应用端口。 */
public interface WorkflowProcessor {

    void processWorkflow(Long caseId, String worker, int executionVersion, ExecutionLease lease);

}
