-- V28: expand only. Existing writers can ignore these nullable snapshot columns.

ALTER TABLE scheduling_appointment_cancellation_details
    ADD COLUMN from_commitment_status VARCHAR(32),
    ADD COLUMN patient_scope_fingerprint VARCHAR(128);

-- 기존 row의 scope 복구는 V30 checkpoint runner가 bounded batch로 수행한다.
-- 복구 불가능한 legacy row는 null로 남기고 readiness gate가 API 노출을 차단한다.
