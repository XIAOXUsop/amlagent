package com.bank.aml.investigation;

import java.util.List;

/** 预警归并/拆分同步解释单元所需的最小跨模块端口。 */
public interface ExplanationWorkspacePort {

    void ensureUnitsForLinkedAlerts(Long caseId, List<AmlAlert> linkedAlerts, String operator);

    void removeUnitForAlert(Long caseId, Long alertId, String reason, String operator);

}
