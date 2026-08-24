package com.bank.aml.rag;

/**
 * 检索证据支持度判定原因。替代单一二元“有/无”回答，明确给出为何判为支持/弱支持/无证据/过期/无权限/冲突。
 */
public enum EvidenceSupport {
    /** 有足够支持概率 */
    SUPPORTED,
    /** 相关但支持概率低于强阈值，仅可作弱参考 */
    WEAK_SUPPORT,
    /** 无相关证据 */
    NO_RELEVANT_EVIDENCE,
    /** 命中证据全部已失效（超出生效窗口） */
    EVIDENCE_EXPIRED,
    /** 命中证据相关但当前访问范围无权读取 */
    EVIDENCE_ACCESS_DENIED,
    /** 命中条款之间存在规范冲突（如“立即冻结” vs “可等待审批”） */
    EVIDENCE_CONFLICT
}