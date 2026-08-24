package com.bank.aml.service;

import com.bank.aml.datasource.CustomerDataRefresh;
import com.bank.aml.common.exception.CustomerNotFoundException;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.dto.CustomerDto;
import com.bank.aml.security.IdCardCipher;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 客户/人员管理：CRUD + Excel 批量导入。
 * <p>新增/修改/删除后调用当前数据 Adapter 的刷新钩子，使新建工单与 Agent 读取到已提交数据。
 */
@Service
public class CustomerAdminService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAdminService.class);
    private static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 1_000;

    private final CustomerRepository customerRepository;
    private final CustomerDataRefresh customerDataRefresh;

    public CustomerAdminService(CustomerRepository customerRepository,
                                CustomerDataRefresh customerDataRefresh) {
        this.customerRepository = customerRepository;
        this.customerDataRefresh = customerDataRefresh;
    }

    public Page<CustomerDto> list(int page, int size, String keyword) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CustomerEntity> result = (keyword == null || keyword.isBlank())
                ? customerRepository.findByDeletedFalse(pageable)
                : customerRepository.search(keyword.trim(), pageable);
        return result.map(CustomerDto::from);
    }

    /** 管理员客户详情：允许查看停用客户，但逻辑删除记录对外视为不存在。 */
    @Transactional(readOnly = true)
    public CustomerDto detail(Long id) {
        if (id == null || id <= 0) {
            throw new CustomerNotFoundException(id);
        }
        CustomerEntity entity = customerRepository.findById(id)
                .filter(customer -> !customer.isDeleted())
                .orElseThrow(() -> new CustomerNotFoundException(id));
        return CustomerDto.from(entity);
    }

    @Transactional
    public CustomerDto create(CreateRequest req, String createdBy) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String name = required(req.name(), "姓名", 64);
        String idCard = required(req.idCard(), "证件号", 64);
        if (customerRepository.existsByIdCardFingerprint(IdCardCipher.fingerprint(idCard))) {
            throw new IllegalArgumentException("证件号已存在：" + CustomerDto.maskIdCard(idCard));
        }
        CustomerEntity e = new CustomerEntity();
        e.setCustomerNo(nextCustomerNo());
        e.setName(name);
        e.setIdCard(idCard);
        e.setType(optional(req.type(), 32));
        e.setIndustry(optional(req.industry(), 64));
        e.setRegion(optional(req.region(), 64));
        e.setRegCapital(optional(req.regCapital(), 128));
        e.setStatus("ENABLED");
        e.setCreatedBy(optional(createdBy, 64));
        CustomerEntity saved = customerRepository.save(e);
        reloadAfterCommit();
        return CustomerDto.from(saved);
    }

    @Transactional
    public CustomerDto update(Long id, UpdateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        CustomerEntity e = getActive(id);
        String newIdCard = req.idCard() == null ? e.getIdCard() : required(req.idCard(), "证件号", 64);
        if (!newIdCard.equals(e.getIdCard())
                && customerRepository.existsByIdCardFingerprint(IdCardCipher.fingerprint(newIdCard))) {
            throw new IllegalArgumentException("证件号已存在：" + CustomerDto.maskIdCard(newIdCard));
        }
        if (req.name() != null) {
            e.setName(required(req.name(), "姓名", 64));
        }
        e.setIdCard(newIdCard);
        if (req.type() != null) e.setType(optional(req.type(), 32));
        if (req.industry() != null) e.setIndustry(optional(req.industry(), 64));
        if (req.region() != null) e.setRegion(optional(req.region(), 64));
        if (req.regCapital() != null) e.setRegCapital(optional(req.regCapital(), 128));
        if (req.status() != null) e.setStatus(normalizeStatus(req.status()));
        CustomerEntity saved = customerRepository.save(e);
        reloadAfterCommit();
        return CustomerDto.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        CustomerEntity e = getActive(id);
        e.setDeleted(true);
        customerRepository.save(e);
        reloadAfterCommit();
    }

    @Transactional
    public CustomerDto setStatus(Long id, String status) {
        CustomerEntity e = getActive(id);
        e.setStatus(normalizeStatus(status));
        CustomerEntity saved = customerRepository.save(e);
        reloadAfterCommit();
        return CustomerDto.from(saved);
    }

    /** Excel 导入：约定表头为 姓名/证件号/类型/行业/地区/注册资本（状态列可省略） */
    @Transactional
    public ImportResult importExcel(MultipartFile file, String createdBy) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传 Excel 文件");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("Excel 文件不能超过 5MB");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new IllegalArgumentException("仅支持 .xlsx 或 .xls 文件");
        }
        List<String> errors = new ArrayList<>();
        int total = 0;
        int success = 0;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 中没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            validateHeader(sheet.getRow(0));
            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException("Excel 数据行不能超过 " + MAX_IMPORT_ROWS + " 行");
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row)) {
                    continue;
                }
                total++;
                try {
                    String name = required(cell(row, 0), "第" + (i + 1) + "行姓名", 64);
                    String idCard = identifierCell(row, 1, i + 1);
                    String type = optional(cell(row, 2), 32);
                    String industry = optional(cell(row, 3), 64);
                    String region = optional(cell(row, 4), 64);
                    String regCapital = optional(cell(row, 5), 128);
                    if (customerRepository.existsByIdCardFingerprint(IdCardCipher.fingerprint(idCard))) {
                        throw new IllegalArgumentException("第" + (i + 1) + "行：证件号已存在 " + CustomerDto.maskIdCard(idCard));
                    }
                    CustomerEntity e = new CustomerEntity();
                    e.setCustomerNo(nextCustomerNo());
                    e.setName(name);
                    e.setIdCard(idCard);
                    e.setType(type);
                    e.setIndustry(industry);
                    e.setRegion(region);
                    e.setRegCapital(regCapital);
                    e.setStatus("ENABLED");
                    e.setCreatedBy(optional(createdBy, 64));
                    customerRepository.save(e);
                    success++;
                } catch (IllegalArgumentException ex) {
                    errors.add(ex.getMessage());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Excel 解析失败", e);
            throw new IllegalArgumentException("Excel 解析失败，请使用 .xlsx 格式且表头为：姓名/证件号/类型/行业/地区/注册资本");
        }
        reloadAfterCommit();
        return new ImportResult(total, success, errors.size(), errors);
    }

    private CustomerEntity getActive(Long id) {
        CustomerEntity e = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在：" + id));
        if (e.isDeleted()) {
            throw new IllegalArgumentException("客户不存在：" + id);
        }
        return e;
    }

    /** 无共享计数器的高熵编号，避免多实例同时扫描 max+1 产生唯一键竞争。 */
    private String nextCustomerNo() {
        return "C-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private String cell(Row row, int idx) {
        var c = row.getCell(idx);
        if (c == null) {
            return null;
        }
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> c.getNumericCellValue() == Math.floor(c.getNumericCellValue())
                    ? String.valueOf((long) c.getNumericCellValue()) : String.valueOf(c.getNumericCellValue());
            case BLANK -> null;
            case FORMULA -> throw new IllegalArgumentException("Excel 不允许使用公式单元格");
            default -> null;
        };
    }

    private String identifierCell(Row row, int idx, int excelRow) {
        var c = row.getCell(idx);
        if (c == null || c.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) {
            throw new IllegalArgumentException("第" + excelRow + "行：证件号不能为空");
        }
        if (c.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) {
            throw new IllegalArgumentException("第" + excelRow + "行：证件号必须设置为文本格式，避免长数字精度丢失");
        }
        return required(c.getStringCellValue(), "第" + excelRow + "行证件号", 64);
    }

    private boolean isBlank(Row row) {
        for (int i = 0; i < 6; i++) {
            var c = row.getCell(i);
            if (c != null && c.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK
                    && !(c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && c.getStringCellValue().isBlank())) {
                return false;
            }
        }
        return true;
    }

    private void validateHeader(Row header) {
        if (header == null || !"姓名".equals(trim(cell(header, 0))) || !"证件号".equals(trim(cell(header, 1)))) {
            throw new IllegalArgumentException("Excel 表头前两列必须为：姓名、证件号");
        }
    }

    private String required(String value, String label, int maxLength) {
        String normalized = trim(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String optional(String value, int maxLength) {
        String normalized = trim(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("字段长度不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"ENABLED".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new IllegalArgumentException("状态仅支持 ENABLED / DISABLED");
        }
        return normalized;
    }

    /** 事务真正提交后再刷新内存快照，避免事务回滚却提前暴露未提交数据。 */
    private void reloadAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeReload();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeReload();
            }
        });
    }

    private void safeReload() {
        try {
            customerDataRefresh.refresh();
        } catch (RuntimeException e) {
            // 数据库事务已提交，刷新失败不能伪装成业务写入失败；记录后由下次变更/重启恢复快照。
            log.error("客户数据已提交，但内存快照刷新失败", e);
        }
    }

    public record CreateRequest(String name, String idCard, String type, String industry,
                                String region, String regCapital) {
    }

    public record UpdateRequest(String name, String idCard, String type, String industry,
                                String region, String regCapital, String status) {
    }

    public record ImportResult(int total, int success, int failed, List<String> errors) {
    }
}
