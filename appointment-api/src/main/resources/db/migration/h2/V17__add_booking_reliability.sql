-- V17: 예약 신뢰성 사건/결정/override/job 원장

ALTER TABLE scheduling_appointment_commitments ADD COLUMN booking_reliability_decision_id BIGINT;
ALTER TABLE scheduling_appointment_commitments ADD COLUMN booking_reliability_policy_version_id BIGINT;
ALTER TABLE scheduling_appointment_commitments ADD COLUMN booking_reliability_policy_hash VARCHAR(64);
ALTER TABLE scheduling_appointment_commitments ADD COLUMN booking_reliability_evaluation_digest VARCHAR(64);
ALTER TABLE scheduling_appointment_commitments ADD COLUMN booking_reliability_expires_at TIMESTAMP;
ALTER TABLE scheduling_appointment_proposals ADD COLUMN booking_reliability_decision_id BIGINT;
ALTER TABLE scheduling_appointment_proposals ADD COLUMN booking_reliability_policy_version_id BIGINT;
ALTER TABLE scheduling_appointment_proposals ADD COLUMN booking_reliability_policy_hash VARCHAR(64);
ALTER TABLE scheduling_appointment_proposals ADD COLUMN booking_reliability_evaluation_digest VARCHAR(64);
ALTER TABLE scheduling_appointment_proposals ADD COLUMN booking_reliability_expires_at TIMESTAMP;

CREATE TABLE booking_reliability_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    appointment_id BIGINT NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    responsibility VARCHAR(32) NOT NULL,
    scheduled_start_at TIMESTAMP NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    source_version BIGINT NOT NULL,
    event_hash VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(160),
    retention_class VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_booking_reliability_event_hash CHECK (REGEXP_LIKE(event_hash, '^[0-9a-f]{64}$'))
);
CREATE UNIQUE INDEX ux_booking_reliability_event_identity
    ON booking_reliability_events (tenant_group_id, clinic_id, member_id, event_id, source_version);
CREATE INDEX idx_booking_reliability_event_member_time
    ON booking_reliability_events (tenant_group_id, clinic_id, member_id, occurred_at, event_id);

CREATE TABLE booking_reliability_decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    policy_version_id BIGINT,
    policy_hash VARCHAR(64),
    evaluated_at TIMESTAMP NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    reason_codes_csv VARCHAR(512) NOT NULL,
    trigger_appointment_ids_csv VARCHAR(2048) NOT NULL,
    trigger_types_csv VARCHAR(2048) NOT NULL,
    no_show_count INTEGER NOT NULL,
    late_cancellation_count INTEGER NOT NULL,
    effective_from TIMESTAMP,
    expires_at TIMESTAMP,
    decision_digest VARCHAR(64) NOT NULL,
    has_additional_triggers BOOLEAN DEFAULT FALSE NOT NULL,
    audit_cursor VARCHAR(512),
    actor_ref VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_booking_reliability_decision_digest CHECK (REGEXP_LIKE(decision_digest, '^[0-9a-f]{64}$'))
);
CREATE UNIQUE INDEX ux_booking_reliability_decision_digest
    ON booking_reliability_decisions (tenant_group_id, clinic_id, member_id, decision_digest);
CREATE INDEX idx_booking_reliability_decision_member_latest
    ON booking_reliability_decisions (tenant_group_id, clinic_id, member_id, evaluated_at, id);

CREATE TABLE booking_reliability_overrides (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    decision_id BIGINT,
    policy_version_id BIGINT,
    previous_decision_digest VARCHAR(64),
    action VARCHAR(16) NOT NULL,
    verdict VARCHAR(32),
    reason_code VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    result_digest VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    correlation_id VARCHAR(160),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX ux_booking_reliability_override_idempotency
    ON booking_reliability_overrides (tenant_group_id, clinic_id, member_id, idempotency_key_hash);
CREATE INDEX idx_booking_reliability_override_active
    ON booking_reliability_overrides (tenant_group_id, clinic_id, member_id, effective_from, expires_at, id);

CREATE TABLE booking_reliability_reevaluation_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    policy_version_id BIGINT,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMP,
    attempt_count INTEGER DEFAULT 0 NOT NULL,
    cursor_occurred_at TIMESTAMP,
    cursor_event_id VARCHAR(160),
    scanned_count BIGINT DEFAULT 0 NOT NULL,
    decision_count BIGINT DEFAULT 0 NOT NULL,
    last_failure_code VARCHAR(96),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_booking_reliability_reevaluation_status CHECK (
        status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'PAUSED', 'COMPLETED', 'FAILED', 'DEAD_LETTER', 'STALE')
    )
);
CREATE UNIQUE INDEX ux_booking_reliability_reevaluation_idempotency
    ON booking_reliability_reevaluation_jobs (tenant_group_id, clinic_id, member_id, idempotency_key_hash);
CREATE INDEX idx_booking_reliability_reevaluation_due
    ON booking_reliability_reevaluation_jobs (status, next_attempt_at, clinic_id, id);
CREATE INDEX idx_booking_reliability_reevaluation_lease
    ON booking_reliability_reevaluation_jobs (status, lease_expires_at, clinic_id, id);
