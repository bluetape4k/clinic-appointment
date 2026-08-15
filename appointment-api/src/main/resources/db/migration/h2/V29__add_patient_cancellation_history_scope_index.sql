-- V29: H2 test/development index. Production dialects use their online index form.
SET LOCK_TIMEOUT 30000;
CREATE INDEX idx_cancellation_detail_patient_scope_time
    ON scheduling_appointment_cancellation_details(tenant_group_id, patient_scope_fingerprint, occurred_at, id);
