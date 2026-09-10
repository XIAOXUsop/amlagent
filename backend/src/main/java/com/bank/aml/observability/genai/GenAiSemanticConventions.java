package com.bank.aml.observability.genai;

/**
 * OpenTelemetry <b>GenAI 语义约定</b>（semantic conventions）的属性名与取值常量。
 *
 * <p>为什么不用自定义指标名：Prometheus 指标只能回答"调用了多少次、花了多少 token"，
 * 无法还原<b>单次调用</b>的上下文（哪次请求、哪个模型、耗时落在哪一段、失败在哪个环节）。
 * 采用 OTel GenAI SIG 正在收敛的标准命名后，本项目的模型调用追踪可以被任何兼容
 * OTel 的后端（Jaeger / Tempo / Datadog / Langfuse 等）<b>直接理解</b>，
 * 不需要为每个观测平台再写一套私有埋点。
 *
 * <p>标准仍在演进，属性名以 OTel 语义约定仓库为准；此处集中定义，升级时只改一处。
 */
public final class GenAiSemanticConventions {

    // ---- 操作与模型 ----
    /** 操作类型，取值见 {@link #OPERATION_CHAT} */
    public static final String OPERATION_NAME = "gen_ai.operation.name";
    /** 提供商标识，如 openai / anthropic / deepseek */
    public static final String PROVIDER_NAME = "gen_ai.provider.name";
    /** 请求侧模型名 */
    public static final String REQUEST_MODEL = "gen_ai.request.model";
    /** 请求侧温度 */
    public static final String REQUEST_TEMPERATURE = "gen_ai.request.temperature";
    /** 响应侧实际模型名（可能与请求不同，如别名解析） */
    public static final String RESPONSE_MODEL = "gen_ai.response.model";
    /** 结束原因 */
    public static final String RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
    /** 多轮会话标识 */
    public static final String CONVERSATION_ID = "gen_ai.conversation.id";

    // ---- 用量 ----
    public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    // ---- 工具 ----
    public static final String TOOL_NAME = "gen_ai.tool.name";

    // ---- 错误（OTel 通用约定）----
    public static final String ERROR_TYPE = "error.type";

    /** chat 操作；span 名按约定为 "{操作} {模型}" */
    public static final String OPERATION_CHAT = "chat";

    /**
     * 项目自定义扩展：调用用途（main_agent / summary / assistant）。
     * 标准未定义该维度，但成本归属分析必须有它，故以 {@code aml.} 前缀扩展，不污染标准命名空间。
     */
    public static final String AML_PURPOSE = "aml.purpose";

    private GenAiSemanticConventions() {
    }

    /** OTel 约定：span 名形如 {@code chat deepseek-chat} */
    public static String spanName(String model) {
        return OPERATION_CHAT + " " + (model == null || model.isBlank() ? "unknown" : model);
    }
}
