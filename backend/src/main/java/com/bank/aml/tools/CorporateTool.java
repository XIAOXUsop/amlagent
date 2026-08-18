package com.bank.aml.tools;

import com.bank.aml.domain.ShareholdingRecord;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工商股权格式化工具：把股权穿透记录格式化为可读文本。
 * <p>当前作为纯静态格式化器被 {@link SnapshotToolSuite} 复用。
 * 不直接暴露给 LLM 作为 Agent Tool，规避无身份校验的越权面；Agent 一律经快照套件读取数据。
 */
public final class CorporateTool {

    private CorporateTool() {
    }

    /** 从已冻结的股权原始数据生成画像文本（快照套件复用同一格式化逻辑） */
    public static String format(List<ShareholdingRecord> list, String customerId) {
        if (list.isEmpty()) {
            return "未查询到客户 " + customerId + " 的股权结构信息。";
        }
        String body = list.stream()
                .map(s -> String.format("- [%s] %s（%s），持股 %.0f%%", s.level(), s.holder(), s.holderType(),
                        s.ratio().movePointRight(2).doubleValue()))
                .collect(Collectors.joining("\n"));
        return "客户 " + customerId + " 股权穿透信息：\n" + body;
    }
}
