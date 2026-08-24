package com.bank.aml.rag;

import java.util.List;

/**
 * 人工审核的法律命题：把"冻结/报告/停止服务"等高影响处置动作的结构化语义建模为可验证条目，
 * 用于替换关键词共现式支持校验。
 * <ul>
 *   <li>{@code actionCode}：缺失动作代码（FREEZE_ASSETS 等）；</li>
 *   <li>{@code modality}：规范情态 MUST / MUST_NOT / PERMISSIVE；</li>
 *   <li>{@code subject}/{@code object}：义务主体/对象；</li>
 *   <li>{@code conditions}/{@code exceptions}：触发条件与例外；</li>
 *   <li>{@code evidenceTitle}/{@code evidenceId}：命题依托的法规（标题子串或精确证据ID）；</li>
 *   <li>{@code actionTerms}/{@code modalityTerms}：条款原文中用于判定动作与情态的词；</li>
 *   <li>{@code effectiveFrom}：命题生效起点（用于有效期复核）。</li>
 * </ul>
 */
public record LegalActionProposition(
        String actionCode,
        String modality,
        String subject,
        String object,
        List<String> conditions,
        List<String> exceptions,
        String evidenceTitle,
        String evidenceId,
        String effectiveFrom,
        List<String> actionTerms,
        List<String> modalityTerms
) {
    public LegalActionProposition {
        conditions = def(conditions);
        exceptions = def(exceptions);
        actionTerms = def(actionTerms);
        modalityTerms = def(modalityTerms);
        subject = subject == null ? "" : subject;
        object = object == null ? "" : object;
        evidenceTitle = evidenceTitle == null ? "" : evidenceTitle;
        evidenceId = evidenceId == null ? "" : evidenceId;
        effectiveFrom = effectiveFrom == null ? "" : effectiveFrom;
    }

    private static List<String> def(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}