package com.bank.aml.explanation;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示用证据来源适配器（受控夹具，v3 计划 §9）。
 * 页面必须标明演示来源；禁止接收调用方给定的"实际摘要"当作结果。
 *
 * <p>行为约定：
 * <ul>
 *   <li>受控夹具注册表按 (sourceSystem, sourceReference) 提供真实内容；</li>
 *   <li>服务端读取内容并计算 SHA-256（{@link #contentSha256Of}）；</li>
 *   <li>未注册的引用 → NOT_FOUND（不存在该记录）；</li>
 *   <li>引用标记 UNAVAILABLE: 前缀 → UNAVAILABLE（来源系统暂不可用，不判虚假）；</li>
 *   <li>测试可通过 {@link #putFixture} 注入夹具，或 {@link #markUnavailable} 模拟来源不可用；</li>
 *   <li>同源同内容重复抓取返回相同摘要（幂等）；夹具内容变更后摘要随之变化（版本变化可感知）。</li>
 * </ul>
 */
@Component
public class DemoEvidenceSourceAdapter implements EvidenceSourcePort {

    /** 支持的来源系统与受控夹具注册表（value 为内容文本；null 表示 UNAVAILABLE）。 */
    private final Map<String, String> fixtures = new ConcurrentHashMap<>();

    public DemoEvidenceSourceAdapter() {
        // 内置演示夹具：登记 ≠ 已核验；内容为受控演示数据（非真实企业信息）。
        putFixture("CORE_BANKING", "TXN-DOC-001",
                "核心系统交易回单：付款人=丙集团公司；收款人=甲贸易公司；金额=200000.00 CNY；"
                        + "附言=乙制造公司货款（演示夹具数据）");
        putFixture("CORE_BANKING", "TXN-DOC-002",
                "核心系统交易回单：付款人=丙集团公司；收款人=甲贸易公司；金额=120000.00 CNY；"
                        + "附言=乙制造公司货款（演示夹具数据）");
        putFixture("KYC_PLATFORM", "GROUP-REL-001",
                "集团关系登记：乙制造公司与丙集团公司为同一集团成员（来源：KYC 平台集团客户档案，演示夹具）");
        putFixture("DOCUMENT_MANAGEMENT", "AUTH-DOC-001",
                "付款授权书扫描件：乙制造公司授权丙集团公司向甲贸易公司支付货款，额度 320000.00 CNY，"
                        + "有效期至 2026-12-31（客户上传，演示夹具）");
    }

    /** 注册/更新受控夹具内容（测试与演示编排用；生产不调用）。 */
    public void putFixture(String sourceSystem, String sourceReference, String content) {
        fixtures.put(fixtureKey(sourceSystem, sourceReference), content);
    }

    /** 模拟来源系统暂不可用（不判虚假，等待或换替代来源）。 */
    public void markUnavailable(String sourceSystem, String sourceReference) {
        fixtures.put(fixtureKey(sourceSystem, sourceReference), null);
    }

    public void removeFixture(String sourceSystem, String sourceReference) {
        fixtures.remove(fixtureKey(sourceSystem, sourceReference));
    }

    @Override
    public Optional<FetchedContent> fetch(String sourceSystem, String sourceReference) {
        String system = upper(sourceSystem);
        if (!supportedSystems().contains(system)) {
            return Optional.empty();
        }
        String content = fixtures.get(fixtureKey(system, sourceReference));
        if (content == null && fixtures.containsKey(fixtureKey(system, sourceReference))) {
            // 已注册但标记不可用：UNAVAILABLE ≠ 虚假，不产生任何 RESOLVED 记录。
            return Optional.of(new FetchedContent(system, sourceReference, null, null,
                    Availability.UNAVAILABLE));
        }
        if (content == null) {
            return Optional.of(new FetchedContent(system, sourceReference, null, null,
                    Availability.NOT_FOUND));
        }
        return Optional.of(new FetchedContent(system, sourceReference, content,
                contentSha256Of(content), Availability.RESOLVED));
    }

    @Override
    public Set<String> supportedSystems() {
        return Set.of("CORE_BANKING", "KYC_PLATFORM", "DOCUMENT_MANAGEMENT");
    }

    /** 服务端计算内容 SHA-256；任何调用方输入不得替代本结果。 */
    public static String contentSha256Of(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixtureKey(String system, String reference) {
        return system + "|" + (reference == null ? "" : reference.trim());
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
