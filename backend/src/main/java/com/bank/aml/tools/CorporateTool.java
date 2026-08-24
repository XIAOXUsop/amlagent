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
            return "未查询到股权结构信息。";
        }
        String body = java.util.stream.IntStream.range(0, list.size())
                .mapToObj(index -> {
                    ShareholdingRecord s = list.get(index);
                    return String.format("- [主体-%d][%s] 类型：%s，持股 %.0f%%，结构特征：%s",
                            index + 1, s.level(), controlledHolderType(s.holderType()),
                            s.ratio().movePointRight(2).doubleValue(),
                            controlledFeature(s.holder()));
                })
                .collect(Collectors.joining("\n"));
        return "股权穿透信息：\n" + body;
    }

    private static String controlledFeature(String holder) {
        String value = holder == null ? "" : holder.toUpperCase(java.util.Locale.ROOT);
        return value.contains("TRUST") || value.contains("信托") || value.contains("LTD") || value.contains("境外")
                ? "境外或信托相关主体" : "一般主体";
    }

    private static String controlledHolderType(String holderType) {
        String value = holderType == null ? "" : holderType.toUpperCase(java.util.Locale.ROOT);
        if (value.contains("自然") || value.contains("个人") || value.contains("PERSON")) return "自然人";
        if (value.contains("信托") || value.contains("TRUST")) return "信托";
        if (value.contains("企业") || value.contains("公司") || value.contains("COMPANY")) return "企业";
        return "其他主体";
    }
}
