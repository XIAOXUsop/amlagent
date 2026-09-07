ALTER TABLE enhanced_due_diligence_request
    ADD COLUMN assigned_to VARCHAR(64) NULL AFTER requested_at,
    ADD COLUMN assigned_unit VARCHAR(64) NULL AFTER assigned_to,
    ADD COLUMN cancelled_by VARCHAR(64) NULL AFTER resolved_at,
    ADD COLUMN cancelled_at DATETIME(6) NULL AFTER cancelled_by,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER cancelled_at,
    ADD KEY idx_edd_assignee_status_due (assigned_to, status, due_at);
