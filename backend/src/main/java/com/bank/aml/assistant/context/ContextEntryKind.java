package com.bank.aml.assistant.context;

/**
 * 上下文条目类型。**保留策略由类型决定，而不是由"有多旧"决定**——这是与本项目
 * 此前"最近 12 条窗口硬截断"的本质区别。
 */
public enum ContextEntryKind {

    /** 用户输入。不可协商：既不压缩也不驱逐——用户说过的约束不能在后续轮次里消失。 */
    USER_TURN(true, false),

    /** 已完成回答的正文。可压缩（保留头尾 + 归档索引）。 */
    ASSISTANT_ANSWER(false, true),

    /** 法规证据引用块。**零失真**：evidenceId 与条文原文必须逐字保留，否则引用校验会失败。 */
    LEGAL_EVIDENCE(false, false),

    /** 客户事实引用块。**零失真**：同上。 */
    FACT_REFERENCE(false, false);

    private final boolean pinned;
    private final boolean compressible;

    ContextEntryKind(boolean pinned, boolean compressible) {
        this.pinned = pinned;
        this.compressible = compressible;
    }

    /** 是否不可驱逐（用户输入） */
    public boolean pinned() {
        return pinned;
    }

    /** 是否允许压缩（仅正文类可压；证据类必须逐字） */
    public boolean compressible() {
        return compressible;
    }

    /** 是否零失真：被驱逐时必须以"再水合 stub"形式留下来源，不能让引用凭空消失 */
    public boolean zeroLoss() {
        return this == LEGAL_EVIDENCE || this == FACT_REFERENCE;
    }
}
