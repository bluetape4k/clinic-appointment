-- V17: booking reliability event, decision, override, and reevaluation ledgers.
-- Only opaque member identifiers and bounded reason/audit codes are persisted.

ALTER TABLE scheduling_appointment_commitments
    ADD COLUMN booking_reliability_decision_id BIGINT,
    ADD COLUMN booking_reliability_policy_version_id BIGINT,
    ADD COLUMN booking_reliability_policy_hash VARCHAR(64),
    ADD COLUMN booking_reliability_evaluation_digest VARCHAR(64),
    ADD COLUMN booking_reliability_expires_at TIMESTAMP(6);
ALTER TABLE scheduling_appointment_proposals
    ADD COLUMN booking_reliability_decision_id BIGINT,
    ADD COLUMN booking_reliability_policy_version_id BIGINT,
    ADD COLUMN booking_reliability_policy_hash VARCHAR(64),
    ADD COLUMN booking_reliability_evaluation_digest VARCHAR(64),
    ADD COLUMN booking_reliability_expires_at TIMESTAMP(6);

CREATE TABLE booking_reliability_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    appointment_id BIGINT NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    responsibility VARCHAR(32) NOT NULL,
    scheduled_start_at TIMESTAMP(6) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    source_version BIGINT NOT NULL,
    event_hash VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(160),
    retention_class VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_booking_reliability_event_type CHECK (event_type IN ('NO_SHOW', 'CANCELLED')),
    CONSTRAINT ck_booking_reliability_responsibility CHECK (
        responsibility IN ('PATIENT', 'CLINIC', 'OPERATIONAL_EXCEPTION', 'DATA_CORRECTION', 'UNKNOWN')
    ),
    CONSTRAINT ck_booking_reliability_event_source CHECK (
        source IN ('APPOINTMENT', 'CLINIC_OPERATION', 'STAFF_OVERRIDE', 'IMPORT')
    ),
    CONSTRAINT ck_booking_reliability_event_hash CHECK (REGEXP_LIKE(event_hash, '^[0-9a-f]{64}$')),
    CONSTRAINT ux_booking_reliability_event_identity UNIQUE (
        tenant_group_id, clinic_id, member_id, event_id, source_version
    ),
    INDEX idx_booking_reliability_event_member_time (
        tenant_group_id, clinic_id, member_id, occurred_at, event_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking_reliability_decisions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    policy_version_id BIGINT,
    policy_hash VARCHAR(64),
    evaluated_at TIMESTAMP(6) NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    reason_codes_csv VARCHAR(512) NOT NULL,
    trigger_appointment_ids_csv VARCHAR(2048) NOT NULL,
    trigger_types_csv VARCHAR(2048) NOT NULL,
    no_show_count INT NOT NULL,
    late_cancellation_count INT NOT NULL,
    effective_from TIMESTAMP(6),
    expires_at TIMESTAMP(6),
    decision_digest VARCHAR(64) NOT NULL,
    has_additional_triggers BOOLEAN NOT NULL DEFAULT FALSE,
    audit_cursor VARCHAR(512),
    actor_ref VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_booking_reliability_decision_verdict CHECK (
        verdict IN ('ELIGIBLE', 'REQUIRES_STAFF_APPROVAL', 'RESTRICTED', 'OVERRIDDEN', 'POLICY_DISABLED', 'STALE', 'UNAVAILABLE')
    ),
    CONSTRAINT ck_booking_reliability_decision_digest CHECK (REGEXP_LIKE(decision_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ux_booking_reliability_decision_digest UNIQUE (
        tenant_group_id, clinic_id, member_id, decision_digest
    ),
    INDEX idx_booking_reliability_decision_member_latest (
        tenant_group_id, clinic_id, member_id, evaluated_at, id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking_reliability_overrides (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
    effective_from TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6),
    correlation_id VARCHAR(160),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_booking_reliability_override_action CHECK (action IN ('OVERRIDE', 'CLEAR')),
    CONSTRAINT ck_booking_reliability_override_verdict CHECK (
        verdict IS NULL OR verdict IN ('ELIGIBLE', 'REQUIRES_STAFF_APPROVAL', 'RESTRICTED', 'OVERRIDDEN', 'POLICY_DISABLED')
    ),
    CONSTRAINT ux_booking_reliability_override_idempotency UNIQUE (
        tenant_group_id, clinic_id, member_id, idempotency_key_hash
    ),
    INDEX idx_booking_reliability_override_active (
        tenant_group_id, clinic_id, member_id, effective_from, expires_at, id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking_reliability_reevaluation_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    policy_version_id BIGINT,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMP(6),
    attempt_count INT NOT NULL DEFAULT 0,
    cursor_occurred_at TIMESTAMP(6),
    cursor_event_id VARCHAR(160),
    scanned_count BIGINT NOT NULL DEFAULT 0,
    decision_count BIGINT NOT NULL DEFAULT 0,
    last_failure_code VARCHAR(96),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_booking_reliability_reevaluation_status CHECK (
        status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'PAUSED', 'COMPLETED', 'FAILED', 'DEAD_LETTER', 'STALE')
    ),
    CONSTRAINT ux_booking_reliability_reevaluation_idempotency UNIQUE (
        tenant_group_id, clinic_id, member_id, idempotency_key_hash
    ),
    INDEX idx_booking_reliability_reevaluation_due (
        status, next_attempt_at, clinic_id, id
    ),
    INDEX idx_booking_reliability_reevaluation_lease (
        status, lease_expires_at, clinic_id, id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
