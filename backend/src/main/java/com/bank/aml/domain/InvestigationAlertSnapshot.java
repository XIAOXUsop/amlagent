package com.bank.aml.domain;

import java.time.LocalDateTime;

/**
 * 冻结进尽调快照的单条关联预警事实。
 * <p>
 * 一次执行内模型输入、法规主题计算和加密归档使用同一份预警集合； 身份继续使用后端绑定的当前客户，不允许从预警文本推导客户工具参数。
 *
 * @param alertRevision 预警被冻结时的版本；命中原因或归并状态变化会改变预警摘要
 */
public record InvestigationAlertSnapshot(Long alertId, String externalAlertId, String ruleCode, String scenarioCode,
        String hitReason, LocalDateTime occurredAt, int alertRevision) {
}
