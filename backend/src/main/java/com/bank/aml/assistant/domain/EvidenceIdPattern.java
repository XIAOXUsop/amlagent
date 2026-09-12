package com.bank.aml.assistant.domain;

import java.util.regex.Pattern;

/**
 * 证据标识的**唯一**识别正则。
 *
 * <p>
 * 为什么必须收敛到一处：此前 {@code AssistantOutputGuard} 与 {@code AssistantEvidenceCitationAppender}
 * 各自定义了一套，且**宽窄不一致**—— 护栏只认 {@code [A-Z_]+:[a-f0-9]{64}}（必须恰好 64 位小写十六进制）， 而追加器认
 * {@code [A-Z_]+:[A-Za-z0-9]{32,80}}。
 *
 * <p>
 * 这构成一个真实的漏检缺陷：模型若吐出大写形态或长度不为 64 的标识 （如 {@code CUSTOMER_PROFILE:AB12…}），**护栏的正则扫不到它**，
 * {@code EVIDENCE_NOT_IN_SNAPSHOT} 就不会触发，伪造或过期的引用可以穿过输出校验， 直接威胁 Spec §17「证据引用有效率 100%」。
 *
 * <p>
 * 本类采用两者中**更宽**的形态（即"模型可能说出口的全部标识"）， 因为安全校验的职责是"宁可多查，不可漏查"： 查到了不属于快照就阻断，比根本没查到安全得多。
 */
public final class EvidenceIdPattern {

    /**
     * 证据标识候选：两种命名空间。
     * <ul>
     * <li>{@code TYPE:hash} —— 客户事实类，如 {@code CUSTOMER_PROFILE:…}、{@code TX_AGG:…}</li>
     * <li>{@code KB-…} / {@code LEGAL-…} —— 知识证据类，内容寻址（{@code sha256(title|article|text)}
     * 前 16 位）， 因此同一文档跨轮次稳定</li>
     * </ul>
     */
    public static final Pattern PATTERN = Pattern
        .compile("\\b(?:[A-Z_]+:[A-Za-z0-9]{32,80}|(?:KB|LEGAL)-[A-Za-z0-9_-]{6,80})\\b");

    private EvidenceIdPattern() {
    }

}
