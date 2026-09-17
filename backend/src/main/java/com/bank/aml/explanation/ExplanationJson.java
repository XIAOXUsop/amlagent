package com.bank.aml.explanation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 解释工作区草稿 JSON 的读写小工具。
 *
 * <p>
 * 这些方法原先散在 {@code ExplanationWorkspaceService} 里当私有静态方法。 拆服务的每一步（草稿校验、就绪度评估、范围与覆盖）都要用到它们，
 * 与其每抽一块就拖着这一组走，不如先提到这里。
 *
 * <p>
 * 抽出来之后是**包内可见**的：它不是对外 API，只是这个包里的公共词汇。 写成 public 会让人以为它是给别人用的。
 *
 * <p>
 * 行为与原实现逐行一致——这一步不含任何语义变化，也不改任何判断条件。
 */
final class ExplanationJson {

    private ExplanationJson() {
    }

    /** 取字符串字段，缺失或为 null 时返回空串（注意仍会 trim） */
    static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText(defaultValue).trim();
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    /** 取字符串数组字段；元素逐个 trim，null 元素跳过。非数组时返回空列表而不是报错 */
    static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode item : value) {
                if (!item.isNull()) {
                    result.add(item.asText().trim());
                }
            }
        }
        return result;
    }

    static List<JsonNode> intList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<JsonNode> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(result::add);
        }
        return result;
    }

    /** 解析枚举；非法值抛 IllegalArgumentException，并把字段名带进消息（便于定位是哪一项写错了） */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + "不在允许范围内：" + value);
        }
    }

    /** 保序去重 */
    static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

}
