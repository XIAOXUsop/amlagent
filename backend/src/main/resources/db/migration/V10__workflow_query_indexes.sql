-- 工作流与审计查询热点索引。索引顺序与 Repository 的筛选/排序条件保持一致。

ALTER TABLE aml_case
    ADD KEY idx_case_created (created_at),
    ADD KEY idx_case_status_created (status, created_at),
    ADD KEY idx_case_status_locked (status, locked_at),
    ADD KEY idx_case_status_retry (status, next_retry_at),
    ADD KEY idx_case_status_heartbeat (status, heartbeat_at);

ALTER TABLE case_execution
    ADD KEY idx_case_execution_version_started (case_id, execution_version, started_at);

ALTER TABLE tool_execution_trace
    ADD KEY idx_tool_trace_case_version_sequence (case_id, execution_version, sequence_no);

ALTER TABLE outbox_event
    ADD KEY idx_outbox_status_retry_id (status, next_retry_at, id),
    ADD KEY idx_outbox_status_published_id (status, published_at, id);

ALTER TABLE eval_report
    ADD KEY idx_eval_report_type_created (eval_type, created_at);
