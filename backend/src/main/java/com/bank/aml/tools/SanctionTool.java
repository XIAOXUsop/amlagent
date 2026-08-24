package com.bank.aml.tools;

import com.bank.aml.domain.SanctionRecord;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 制裁黑名单格式化工具：把制裁命中条目格式化为可读文本。
 * <p>当前作为纯静态格式化器被 {@link SnapshotToolSuite} 复用。
 * 不直接暴露给 LLM 作为 Agent Tool，规避无身份校验的越权面；Agent 一律经快照套件读取数据。
 */
public final class SanctionTool {

    private SanctionTool() {
    }

    /** 从已冻结的制裁命中生成文本（快照套件复用；内部去重） */
    public static String format(List<SanctionRecord> hits) {
        List<SanctionRecord> distinct = hits.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return "未命中制裁黑名单（OFAC / 国内名单）。";
        }
        String body = distinct.stream()
                .map(s -> String.format("- 名单类别：%s；风险等级：%d级；匹配状态：后端身份要素筛查已确认",
                        controlledCategory(s.severity()), s.severity()))
                .collect(Collectors.joining("\n"));
        return "黑名单命中结果：\n" + body + "\n注意：命中一级制裁名单必须强制标记为高危险并转人工处理。";
    }

    private static String controlledCategory(int severity) {
        if (severity == 1) return "一级制裁名单";
        if (severity >= 2) return "其他关注名单";
        return "等级异常的待复核名单";
    }
}
