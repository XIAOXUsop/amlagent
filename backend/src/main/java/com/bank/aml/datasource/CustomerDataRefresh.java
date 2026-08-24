package com.bank.aml.datasource;

/** 客户主数据变更后的 Adapter 刷新钩子；缓存型实现重建快照，直读型实现无需动作。 */
public interface CustomerDataRefresh {
    void refresh();
}
