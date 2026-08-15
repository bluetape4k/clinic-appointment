-- V29: online index creation. This migration must run outside a transaction.
SET lock_timeout = '30s';
SET statement_timeout = '30s';
CREATE INDEX CONCURRENTLY idx_cancellation_detail_patient_scope_time
    ON scheduling_appointment_cancellation_details(tenant_group_id, patient_scope_fingerprint, occurred_at DESC, id DESC);
