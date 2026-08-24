-- V12：保存 Guardrail/最终决策组装前的模型原始结构化报告，支持分层审计。
-- 字段不通过普通工单 API 暴露；最终对外报告仍使用 report_json。
ALTER TABLE aml_case
    ADD COLUMN raw_report_json MEDIUMTEXT NULL AFTER report_json;
