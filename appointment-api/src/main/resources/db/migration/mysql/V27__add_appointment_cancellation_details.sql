-- V27: terminal cancellation detail snapshot and redacted audit metadata.

CREATE TABLE scheduling_appointment_cancellation_details (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    commitment_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_detail VARCHAR(500),
    actor_role VARCHAR(16) NOT NULL,
    actor_scope_hash VARCHAR(128) NOT NULL,
    detail_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_cancellation_detail_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cancellation_detail_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT fk_cancellation_detail_appointment FOREIGN KEY (appointment_id)
        REFERENCES scheduling_appointments(id) ON DELETE CASCADE,
    CONSTRAINT fk_cancellation_detail_commitment FOREIGN KEY (commitment_id)
        REFERENCES scheduling_appointment_commitments(id) ON DELETE CASCADE,
    CONSTRAINT fk_cancellation_detail_proposal FOREIGN KEY (proposal_id)
        REFERENCES scheduling_appointment_proposals(id) ON DELETE CASCADE,
    CONSTRAINT uq_cancellation_detail_commitment UNIQUE (commitment_id)
);

CREATE INDEX idx_cancellation_detail_scope_time
    ON scheduling_appointment_cancellation_details(tenant_group_id, clinic_id, occurred_at);
