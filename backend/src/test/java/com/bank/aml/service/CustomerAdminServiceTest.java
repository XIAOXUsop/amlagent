package com.bank.aml.service;

import com.bank.aml.datasource.DatabaseCustomerDataPort;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.security.IdCardCipher;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerAdminServiceTest {

    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final DatabaseCustomerDataPort dataPort = mock(DatabaseCustomerDataPort.class);
    private final CustomerAdminService service = new CustomerAdminService(repository, dataPort);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void refreshesDataSnapshotOnlyAfterTransactionCommit() {
        when(repository.existsByIdCardFingerprint(IdCardCipher.fingerprint("ID-001"))).thenReturn(false);
        when(repository.save(any(CustomerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();

        var result = service.create(
                new CustomerAdminService.CreateRequest(" 新客户 ", " ID-001 ", "个人", null, null, null),
                "admin");

        assertThat(result.customerNo()).matches("C-[0-9A-F]{16}");
        assertThat(result.name()).isEqualTo("新客户");
        verify(dataPort, never()).refresh();

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(dataPort).refresh();
    }

    @Test
    void rejectsIdentifierThatExistsInSoftDeletedHistory() {
        when(repository.existsByIdCardFingerprint(IdCardCipher.fingerprint("ID-001"))).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CustomerAdminService.CreateRequest("客户", "ID-001", null, null, null, null), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证件号已存在");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsOversizedExcelBeforeParsing() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        var file = new MockMultipartFile("file", "customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", oversized);

        assertThatThrownBy(() -> service.importExcel(file, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNumericIdentifierToPreventExcelPrecisionLoss() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("customers");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("证件号");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("客户A");
            row.createCell(1).setCellValue(110101198506123456D);
            workbook.write(output);
            content = output.toByteArray();
        }
        var file = new MockMultipartFile("file", "customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);

        var result = service.importExcel(file, "admin");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.success()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement().asString().contains("必须设置为文本格式");
        verify(repository, never()).save(any());
    }

    @Test
    void returnsMaskedCustomerDetailAndAllowsDisabledCustomer() {
        CustomerEntity entity = customer("C-DETAIL", "110101199001011234");
        entity.setStatus("DISABLED");
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(entity));

        var result = service.detail(7L);

        assertThat(result.customerNo()).isEqualTo("C-DETAIL");
        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.idCardMasked()).isEqualTo("110101********1234");
    }

    @Test
    void hidesSoftDeletedCustomerDetail() {
        CustomerEntity entity = customer("C-DELETED", "110101199001011234");
        entity.setDeleted(true);
        when(repository.findById(8L)).thenReturn(java.util.Optional.of(entity));

        assertThatThrownBy(() -> service.detail(8L))
                .isInstanceOf(com.bank.aml.common.exception.CustomerNotFoundException.class);
    }

    private CustomerEntity customer(String number, String idCard) {
        CustomerEntity entity = new CustomerEntity();
        entity.setCustomerNo(number);
        entity.setName("existing");
        entity.setIdCard(idCard);
        return entity;
    }
}
