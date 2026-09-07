package com.bank.aml.explanation;

import java.util.Optional;

/**
 * 解释核验证据来源端口（v3 计划 §9；验收 A5-01）。
 * 服务端真实抓取受控来源内容并计算摘要；调用方不再具有提交"实际哈希"的权威输入。
 * 演示适配器必须真正读取受控夹具内容并计算 SHA-256，能返回 NOT_FOUND / UNAVAILABLE / 版本变化。
 */
public interface EvidenceSourcePort {

    /** 抓取结果：内容字节、服务端摘要与可用性状态。 */
    record FetchedContent(
            String sourceSystem,
            String sourceReference,
            String content,
            String contentSha256,
            Availability availability
    ) {
    }

    enum Availability {
        /** 成功取得内容。 */
        RESOLVED,
        /** 来源系统暂时不可用（可重试或等待，不判虚假）。 */
        UNAVAILABLE,
        /** 来源系统中不存在该记录。 */
        NOT_FOUND,
        /** 无权访问该记录。 */
        FORBIDDEN
    }

    /**
     * 按来源系统与不透明记录编号抓取内容。
     * 返回 Optional.empty() 表示该来源系统未被本端口支持（调用方决定如何提示）。
     */
    Optional<FetchedContent> fetch(String sourceSystem, String sourceReference);

    /** 支持的来源系统集合（用于白名单校验）。 */
    java.util.Set<String> supportedSystems();
}
