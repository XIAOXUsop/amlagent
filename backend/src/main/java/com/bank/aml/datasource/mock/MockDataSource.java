package com.bank.aml.datasource.mock;

import com.bank.aml.common.enums.CountryRegion;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Mock 模拟数据源：内置银行演示数据（客户 / 近180天交易 / 股权结构 / 制裁黑名单）。
 * <p>金融数据统一类型：金额 {@link BigDecimal}、时间 {@link LocalDateTime}、币种与地区枚举。
 * 数据为确定性生成（固定随机种子），保证演示可重复。
 */
@Component
public class MockDataSource {

    /** 客户 */
    public record Customer(String id, String name, String idCard, String type,
                           String industry, String region, String regCapital) {
    }

    /** 单笔交易（金融安全类型） */
    public record Transaction(LocalDateTime date, BigDecimal amount, String direction,
                              String counterparty, CountryRegion country, String channel,
                              String scene, String currency) {
    }

    /** 股权关系 */
    public record Shareholding(String holder, String holderType, BigDecimal ratio, String level) {
    }

    /** 制裁名单条目 */
    public record SanctionEntry(String name, String idCard, String listType, String detail, int severity) {
    }

    private final Map<String, Customer> customers = new LinkedHashMap<>();
    private final Map<String, List<Transaction>> transactions = new HashMap<>();
    private final Map<String, List<Shareholding>> shareholdings = new HashMap<>();
    private final List<SanctionEntry> sanctions = new ArrayList<>();

    @PostConstruct
    public void init() {
        seedCustomers();
        seedSanctions();
        seedShareholdings();
        seedTransactions();
    }

    private void seedCustomers() {
        customers.put("C001", new Customer("C001", "张伟", "110101198506123456", "企业法人",
                "国际贸易", "上海", "注册资本5000万人民币"));
        customers.put("C002", new Customer("C002", "王强", "440301197809112233", "个体工商户",
                "服装零售", "深圳", "—"));
        customers.put("C003", new Customer("C003", "李娜", "330102199002081234", "个人",
                "软件研发", "杭州", "—"));
    }

    private void seedSanctions() {
        // 中英文名同时保留，使中文客户名与英文 OFAC 名单均可命中
        sanctions.add(new SanctionEntry("ZHANG WEI（张伟）", "110101198506123456", "OFAC SDN",
                "Iran sanctions evasion / SDN 名单同名", 1));
        sanctions.add(new SanctionEntry("ZHANGWEI TRADING CO., LTD.（张伟国际贸易有限公司）", "", "OFAC SDN",
                "涉伊朗制裁实体同名", 1));
        sanctions.add(new SanctionEntry("王强", "440301197809112233", "人行可疑交易名单",
                "与地下钱庄账户存在资金往来", 2));
    }

    private void seedShareholdings() {
        shareholdings.put("C001", List.of(
                new Shareholding("张伟", "自然人股东", new BigDecimal("0.65"), "L1"),
                new Shareholding("BRILLIANT TRADE LTD (HK)", "境外法人股东", new BigDecimal("0.30"), "L1"),
                new Shareholding("张伟", "境外壳公司实际控制人", new BigDecimal("1.00"), "L2"),
                new Shareholding("WEI FAMILY TRUST", "信托", new BigDecimal("0.05"), "L1")
        ));
        shareholdings.put("C002", List.of(
                new Shareholding("王强", "自然人股东", new BigDecimal("1.00"), "L1"),
                new Shareholding("天利小额贷款有限公司", "关联公司", new BigDecimal("0.20"), "L2")
        ));
        shareholdings.put("C003", List.of(
                new Shareholding("李娜", "自然人股东", new BigDecimal("1.00"), "L1")
        ));
    }

    private void seedTransactions() {
        // 客户画像参数：(笔数, 夜间占比, 跨境占比, 单笔金额范围[低,高], 大额阈值, 涉及地区, 拆分特征, 种子)
        seedTransactionsFor("C001", 120, 0.50, 0.33, 50_000, 8_000_000, 500_000,
                List.of(CountryRegion.HK, CountryRegion.IRAN, CountryRegion.UAE), true, 987654321L);
        seedTransactionsFor("C002", 80, 0.30, 0.00, 20_000, 300_000, 100_000,
                List.of(CountryRegion.CHINA), false, 123456789L);
        seedTransactionsFor("C003", 15, 0.05, 0.00, 2_000, 80_000, 50_000,
                List.of(CountryRegion.CHINA), false, 555555555L);
    }

    private void seedTransactionsFor(String customerId, int count, double nightRatio, double crossBorderRatio,
                                     double minAmount, double maxAmount, double largeThreshold,
                                     List<CountryRegion> countries, boolean structured, long seed) {
        Random rand = new Random(seed);
        List<Transaction> list = new ArrayList<>();
        String[] counterparties = {"贸易客户A", "供应链B", "代付平台C", "境外买方D", "关联企业E", "个人往来户F"};
        String[] channels = {"企业网银", "柜面", "手机银行", "跨境支付"};
        String[] scenes = {"货款结算", "代收代付", "投资款", "服务贸易", "个人跨境汇款"};

        for (int i = 0; i < count; i++) {
            boolean night = rand.nextDouble() < nightRatio;
            boolean crossBorder = rand.nextDouble() < crossBorderRatio;
            LocalDateTime date = buildDateTime(rand, night);
            double amount = minAmount + rand.nextDouble() * (maxAmount - minAmount);
            if (structured && amount > largeThreshold * 0.8) {
                amount = largeThreshold * 0.98; // 结构化拆分：接近阈值的拆分笔
            }
            CountryRegion country = crossBorder ? countries.get(rand.nextInt(countries.size())) : CountryRegion.CHINA;
            String currency = country.isCrossBorder() ? "USD" : "CNY";
            String counterparty = country.isCrossBorder()
                    ? "境外" + counterparties[rand.nextInt(counterparties.length)] + "(" + country.label() + ")"
                    : counterparties[rand.nextInt(counterparties.length)];
            list.add(new Transaction(date, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP),
                    "转出", counterparty, country,
                    channels[rand.nextInt(channels.length)], scenes[rand.nextInt(scenes.length)], currency));
        }
        list.sort((a, b) -> a.date().compareTo(b.date()));
        transactions.put(customerId, list);
    }

    /** 生成合法时间：夜间(22-23 或 0-5 时)，白天(9-21 时)，避免 24-28 点非法小时 */
    private LocalDateTime buildDateTime(Random rand, boolean night) {
        int hour = night ? (22 + rand.nextInt(6)) % 24 : 9 + rand.nextInt(13);
        return LocalDateTime.of(
                2026, 3 + rand.nextInt(6), 1 + rand.nextInt(28), hour, rand.nextInt(60), rand.nextInt(60));
    }

    // ---------- 查询接口 ----------

    public Optional<Customer> findCustomer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    public List<Customer> allCustomers() {
        return List.copyOf(customers.values());
    }

    public List<Transaction> transactionsOf(String customerId) {
        return transactions.getOrDefault(customerId, List.of());
    }

    public List<Shareholding> shareholdingsOf(String customerId) {
        return shareholdings.getOrDefault(customerId, List.of());
    }

    /** 按名称或证件号模糊检索制裁名单 */
    public List<SanctionEntry> searchSanctions(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.trim().toLowerCase();
        return sanctions.stream()
                .filter(s -> s.name().toLowerCase().contains(k)
                        || (s.idCard() != null && s.idCard().toLowerCase().contains(k)))
                .toList();
    }
}
