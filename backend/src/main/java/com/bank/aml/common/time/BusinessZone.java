package com.bank.aml.common.time;

import java.time.ZoneId;

/**
 * 业务时区。
 *
 * <p>
 * 交易窗口切分、夜间交易判定等时间语义必须锚定在**固定业务时区**，不能用
 * {@link ZoneId#systemDefault()}：否则同一批数据在不同部署时区的机器上会算出不同的 30/90/180 天窗口与夜间交易计数，而 AML
 * 风险结论必须**可复现、可审计**。
 *
 * <p>
 * 此前 {@code TransactionWindowService} 等四处使用系统默认时区，导致同一份数据在 UTC 机器与东八区机器上得到不同的风险窗口，本项目
 * CI（UTC runner）即因此产生失败。
 */
public final class BusinessZone {

    /** 业务时区：中国标准时间 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessZone() {
    }

}
