package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;

/** 后台恢复与消费流程发布终态事件所依赖的最小端口。 */
public interface WorkflowEventPort {

    void complete(Long caseId, CaseStatus status);

}
