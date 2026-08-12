package com.bank.aml.rag;

/**
 * 法规/制度文档条目（带证据元数据，用于可追溯引用）。
 */
public record LegalDoc(
        /** 唯一证据标识，如 LEGAL-AML2024-32；模型只能引用该 ID，不能凭空生成法规名 */
        String evidenceId,
        /** 文档标题，如《中华人民共和国反洗钱法》 */
        String title,
        /** 文号，如 中国人民银行令〔2016〕第3号 */
        String documentNumber,
        /** 条款编号，如 第三十二条 */
        String articleNumber,
        /** 命中条文/片段原文 */
        String content
) {
}
