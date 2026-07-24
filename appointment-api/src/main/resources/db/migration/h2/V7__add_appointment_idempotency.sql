-- ============================================================
-- V7: Add appointment creation idempotency records
-- ============================================================

CREATE TABLE IF NOT EXISTS scheduling_appointment_idempotency (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id     BIGINT       NOT NULL,
    clinic_id           BIGINT       NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    appointment_id      BIGINT       NOT NULL,
    expires_at          TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_appointment_idempotency_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_idempotency_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointment_idempotency_appointment FOREIGN KEY (appointment_id)
        REFERENCES scheduling_appointments(id) ON DELETE CASCADE,
    CONSTRAINT uq_appointment_idempotency_scope_key UNIQUE (tenant_group_id, clinic_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_appointment_idempotency_expires_at
    ON scheduling_appointment_idempotency(expires_at);
