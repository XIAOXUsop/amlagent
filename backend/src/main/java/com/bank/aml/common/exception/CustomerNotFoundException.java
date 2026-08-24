package com.bank.aml.common.exception;

/** 客户不存在或已逻辑删除；对外统一映射为 404，避免暴露底层数据状态。 */
public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(Long id) {
        super("客户不存在：" + id);
    }
}
