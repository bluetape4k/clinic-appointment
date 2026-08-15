-- V29: online index creation. The DDL must not wait for a table copy or exclusive lock.
SET SESSION lock_wait_timeout = 30;
SET SESSION innodb_lock_wait_timeout = 30;
ALTER TABLE scheduling_appointment_cancellation_details
    ADD INDEX idx_cancellation_detail_patient_scope_time
        (tenant_group_id, patient_scope_fingerprint, occurred_at, id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
