package com.bank.aml.datasource;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 客户/人员数据源：客户主数据读 {@code customer} 表，交易/股权/制裁为按客户编号确定性生成的演示数据。
 * <p>标记为 @Primary，替代 MockDataSource 成为新建工单/Agent 工具的默认数据源；
 * 管理员新增客户后调用 {@link #reload()} 即可同步到内存。
 */
@Component
@Primary
@Profile("!prod")
public class DatabaseCustomerDataPort implements CustomerDataPort, CustomerDataRefresh {

    private final CustomerRepository customerRepository;
    private final boolean seedDemoCustomers;
    /**
     * 一次 reload 构造完整不可变快照后原子替换，避免并发读线程观察到 clear/repopulate 的中间状态。
     */
    private volatile CustomerDataSnapshot current = CustomerDataSnapshot.empty();
    private final List<SanctionRecord> sanctions = List.of(
            new SanctionRecord("ZHANG WEI（张伟）", "110101198506123456", "OFAC SDN",
                    "Iran sanctions evasion / SDN 名单同名", 1),
            new SanctionRecord("ZHANGWEI TRADING CO., LTD.（张伟国际贸易有限公司）", "", "OFAC SDN",
                    "涉伊朗制裁实体同名", 1),
            new SanctionRecord("王强", "440301197809112233", "人行可疑交易名单",
                    "与地下钱庄账户存在资金往来", 2)
    );

    public DatabaseCustomerDataPort(CustomerRepository customerRepository,
                                    @Value("${aml.demo.seed-customers:true}") boolean seedDemoCustomers) {
        this.customerRepository = customerRepository;
        this.seedDemoCustomers = seedDemoCustomers;
    }

    @PostConstruct
    public void init() {
        if (seedDemoCustomers) {
            seedDefaultCustomersIfNeeded();
        }
        reload();
    }

    /** 管理员增删改后调用，把 DB 客户同步到内存并生成对应演示数据 */
    public synchronized void reload() {
        Map<String, CustomerProfile> customers = new LinkedHashMap<>();
        Map<String, List<TransactionRecord>> transactions = new LinkedHashMap<>();
        Map<String, List<ShareholdingRecord>> shareholdings = new LinkedHashMap<>();
        for (CustomerEntity e : customerRepository.findByDeletedFalseOrderByCustomerNoAsc()) {
            if (!"ENABLED".equalsIgnoreCase(e.getStatus())) {
                continue;
            }
            CustomerProfile p = new CustomerProfile(
                    e.getCustomerNo(), e.getName(), e.getIdCard(), e.getType(),
                    e.getIndustry(), e.getRegion(), e.getRegCapital());
            customers.put(p.id(), p);
            transactions.put(p.id(), generateTransactions(p));
            shareholdings.put(p.id(), generateShareholdings(p));
        }
        current = new CustomerDataSnapshot(
                immutableOrderedMap(customers),
                immutableOrderedMap(transactions),
                immutableOrderedMap(shareholdings),
                Instant.now());
    }

    @Override
    public void refresh() {
        reload();
    }

    @Override
    public Optional<CustomerProfile> findCustomer(String customerId) {
        return Optional.ofNullable(current.customers().get(customerId));
    }

    @Override
    public List<CustomerProfile> allCustomers() {
        return List.copyOf(current.customers().values());
    }

    @Override
    public List<TransactionRecord> transactionsOf(String customerId) {
        return current.transactions().getOrDefault(customerId, List.of());
    }

    @Override
    public List<ShareholdingRecord> shareholdingsOf(String customerId) {
        return current.shareholdings().getOrDefault(customerId, List.of());
    }

    @Override
    public List<SanctionRecord> searchSanctions(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.trim().toLowerCase();
        return sanctions.stream()
                .filter(s -> s.name().toLowerCase().contains(k)
                        || (s.idCard() != null && s.idCard().toLowerCase().contains(k)))
                .toList();
    }

    @Override
    public String sourceSystem() {
        return "DB+MOCK";
    }

    @Override
    public String sourceVersion() {
        return "customer-db-v2";
    }

    @Override
    public Instant asOfTime() {
        return current.loadedAt();
    }

    private void seedDefaultCustomersIfNeeded() {
        // 只在全新空库初始化演示数据；避免部分数据/逻辑删除场景下撞唯一键或悄悄补入假数据。
        if (customerRepository.count() > 0) {
            return;
        }
        customerRepository.saveAll(List.of(
                entity("C001", "张伟", "110101198506123456", "企业法人", "国际贸易", "上海", "注册资本5000万人民币"),
                entity("C002", "王强", "440301197809112233", "个体工商户", "服装零售", "深圳", "—"),
                entity("C003", "李娜", "330102199002081234", "个人", "软件研发", "杭州", "—")
        ));
    }

    private CustomerEntity entity(String no, String name, String idCard, String type,
                                  String industry, String region, String regCapital) {
        CustomerEntity e = new CustomerEntity();
        e.setCustomerNo(no);
        e.setName(name);
        e.setIdCard(idCard);
        e.setType(type);
        e.setIndustry(industry);
        e.setRegion(region);
        e.setRegCapital(regCapital);
        e.setStatus("ENABLED");
        e.setCreatedBy("system");
        return e;
    }

    private List<TransactionRecord> generateTransactions(CustomerProfile c) {
        // 保留既有演示客户的特征；新客户生成一组正常的确定性交易，保证 Agent 流程可演示
        switch (c.id()) {
            case "C001" -> {
                return txns(120, 0.50, 0.33, 50_000, 8_000_000, List.of(
                        CountryRegion.HK, CountryRegion.IRAN, CountryRegion.UAE), true, 987654321L);
            }
            case "C002" -> {
                return txns(80, 0.30, 0.00, 20_000, 300_000, List.of(CountryRegion.CHINA), true, 123456789L);
            }
            case "C003" -> {
                return txns(15, 0.05, 0.00, 2_000, 80_000, List.of(CountryRegion.CHINA), false, 555555555L);
            }
            default -> {
                long seed = c.id().hashCode() & 0x7fffffffL;
                return txns(20, 0.10, 0.05, 5_000, 200_000, List.of(CountryRegion.CHINA, CountryRegion.HK), false, seed);
            }
        }
    }

    private List<ShareholdingRecord> generateShareholdings(CustomerProfile c) {
        switch (c.id()) {
            case "C001" -> {
                return List.of(
                        new ShareholdingRecord("张伟", "自然人股东", new BigDecimal("0.65"), "L1"),
                        new ShareholdingRecord("BRILLIANT TRADE LTD (HK)", "境外法人股东", new BigDecimal("0.30"), "L1"),
                        new ShareholdingRecord("张伟", "境外壳公司实际控制人", new BigDecimal("1.00"), "L2"),
                        new ShareholdingRecord("WEI FAMILY TRUST", "信托", new BigDecimal("0.05"), "L1")
                );
            }
            case "C002" -> {
                return List.of(
                        new ShareholdingRecord("王强", "自然人股东", new BigDecimal("1.00"), "L1"),
                        new ShareholdingRecord("天利小额贷款有限公司", "关联公司", new BigDecimal("0.20"), "L2")
                );
            }
            default -> {
                return List.of(new ShareholdingRecord(c.name(), "自然人股东", new BigDecimal("1.00"), "L1"));
            }
        }
    }

    private List<TransactionRecord> txns(int count, double nightRatio, double crossBorderRatio,
                                         double minAmount, double maxAmount, List<CountryRegion> countries,
                                         boolean structured, long seed) {
        Random rand = new Random(seed);
        List<TransactionRecord> list = new ArrayList<>();
        String[] counterparties = {"贸易客户A", "供应链B", "代付平台C", "境外买方D", "关联企业E", "个人往来户F"};
        String[] channels = {"企业网银", "柜面", "手机银行", "跨境支付"};
        String[] scenes = {"货款结算", "代收代付", "投资款", "服务贸易", "个人跨境汇款"};
        for (int i = 0; i < count; i++) {
            boolean night = rand.nextDouble() < nightRatio;
            boolean cross = rand.nextDouble() < crossBorderRatio;
            LocalDateTime date = buildDateTime(rand, night);
            double amount = minAmount + rand.nextDouble() * (maxAmount - minAmount);
            if (structured && amount > maxAmount * 0.8) {
                amount = maxAmount * 0.98;
            }
            CountryRegion country = cross && !countries.isEmpty()
                    ? countries.get(rand.nextInt(countries.size())) : CountryRegion.CHINA;
            String currency = country.isCrossBorder() ? "USD" : "CNY";
            String counterparty = country.isCrossBorder()
                    ? "境外" + counterparties[rand.nextInt(counterparties.length)] + "(" + country.label() + ")"
                    : counterparties[rand.nextInt(counterparties.length)];
            list.add(new TransactionRecord(date, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP),
                    "转出", counterparty, country,
                    channels[rand.nextInt(channels.length)], scenes[rand.nextInt(scenes.length)], currency));
        }
        list.sort((a, b) -> a.date().compareTo(b.date()));
        return list;
    }

    private LocalDateTime buildDateTime(Random rand, boolean night) {
        int hour = night ? (22 + rand.nextInt(6)) % 24 : 9 + rand.nextInt(13);
        return LocalDateTime.of(
                2026, 3 + rand.nextInt(6), 1 + rand.nextInt(28), hour, rand.nextInt(60), rand.nextInt(60));
    }

    private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record CustomerDataSnapshot(
            Map<String, CustomerProfile> customers,
            Map<String, List<TransactionRecord>> transactions,
            Map<String, List<ShareholdingRecord>> shareholdings,
            Instant loadedAt
    ) {
        private static CustomerDataSnapshot empty() {
            return new CustomerDataSnapshot(Map.of(), Map.of(), Map.of(), Instant.EPOCH);
        }
    }
}
