-- Durable notification delivery outbox (MySQL 8).
-- Stores only opaque routing, template metadata, and privacy-safe parameters.

CREATE TABLE clinic_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    row_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key_version INTEGER NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    idempotency_key_id VARCHAR(128) NOT NULL,
    audit_fingerprint_version INTEGER NOT NULL,
    audit_fingerprint VARCHAR(128) NOT NULL,
    audit_fingerprint_key_id VARCHAR(128) NOT NULL,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    appointment_id BIGINT NULL,
    member_id VARCHAR(255) NULL,
    channel VARCHAR(32) NULL,
    event_type VARCHAR(32) NULL,
    notification_slot VARCHAR(32) NULL,
    provider_key VARCHAR(128) NULL,
    template_key VARCHAR(128) NULL,
    template_version INTEGER NULL,
    parameter_type VARCHAR(64) NULL,
    parameters_json TEXT NULL,
    suppression_reason VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    provider_message_reference VARCHAR(128) NULL,
    destination_fingerprint VARCHAR(128) NULL,
    correlation_id VARCHAR(128) NULL,
    trace_id VARCHAR(128) NULL,
    available_at TIMESTAMP NOT NULL,
    next_retry_at TIMESTAMP NULL,
    lease_owner VARCHAR(128) NULL,
    lease_token VARCHAR(128) NULL,
    lease_until TIMESTAMP NULL,
    attempt_number INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    terminal_at TIMESTAMP NULL,
    CONSTRAINT uk_notification_outbox_idempotency UNIQUE (
        idempotency_key_version, idempotency_key
    ),
    CONSTRAINT ck_notification_outbox_row_kind CHECK (
        row_kind IN ('SENDABLE', 'LEGACY_SUPPRESSION')
    ),
    CONSTRAINT ck_notification_outbox_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'SUPPRESSED', 'EXHAUSTED')
    ),
    CONSTRAINT ck_notification_outbox_channel CHECK (
        channel IS NULL OR channel IN ('DUMMY', 'SMS', 'EMAIL', 'PUSH')
    ),
    CONSTRAINT ck_notification_outbox_event_type CHECK (
        event_type IS NULL OR event_type IN ('CREATED', 'CONFIRMED', 'CANCELLED', 'RESCHEDULED', 'REMINDER')
    ),
    CONSTRAINT ck_notification_outbox_slot CHECK (
        notification_slot IS NULL OR notification_slot IN (
            'CREATED', 'CONFIRMED', 'CANCELLED', 'RESCHEDULED', 'REMINDER_24H', 'REMINDER_SAME_DAY'
        )
    ),
    CONSTRAINT ck_notification_outbox_parameter_type CHECK (
        parameter_type IS NULL OR parameter_type IN ('APPOINTMENT_CONFIRMED')
    ),
    CONSTRAINT ck_notification_outbox_sendable_active_required CHECK (
        row_kind <> 'SENDABLE'
        OR status NOT IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')
        OR (
            appointment_id IS NOT NULL
            AND member_id IS NOT NULL
            AND channel IS NOT NULL
            AND event_type IS NOT NULL
            AND notification_slot IS NOT NULL
            AND template_key IS NOT NULL
            AND template_version IS NOT NULL
            AND parameter_type IS NOT NULL
            AND parameters_json IS NOT NULL
        )
    ),
    CONSTRAINT ck_notification_outbox_sendable_terminal_redaction CHECK (
        row_kind <> 'SENDABLE'
        OR status NOT IN ('SENT', 'SUPPRESSED', 'EXHAUSTED')
        OR (
            appointment_id IS NULL
            AND member_id IS NULL
            AND parameters_json IS NULL
        )
    ),
    CONSTRAINT ck_notification_outbox_legacy_suppression CHECK (
        row_kind <> 'LEGACY_SUPPRESSION'
        OR (
            status = 'SUPPRESSED'
            AND appointment_id IS NULL
            AND member_id IS NULL
            AND channel IS NULL
            AND event_type IS NULL
            AND notification_slot IS NULL
            AND provider_key IS NULL
            AND template_key IS NULL
            AND template_version IS NULL
            AND parameter_type IS NULL
            AND parameters_json IS NULL
        )
    ),
    INDEX idx_notification_outbox_ready_clinic_cursor (
        row_kind, status, available_at, next_retry_at, tenant_group_id, clinic_id
    ),
    INDEX idx_notification_outbox_ready_within_clinic (
        tenant_group_id, clinic_id, row_kind, status, available_at, id, next_retry_at
    ),
    INDEX idx_notification_outbox_lease_recovery (row_kind, status, lease_until, id),
    INDEX idx_notification_outbox_terminal_retention (row_kind, status, terminal_at, id),
    INDEX idx_notification_outbox_pending_oldest (row_kind, status, available_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_notification_delivery_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    outbox_id BIGINT NOT NULL,
    attempt_number INTEGER NOT NULL,
    owner VARCHAR(128) NOT NULL,
    token VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    template_key VARCHAR(128) NOT NULL,
    template_version INTEGER NOT NULL,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    duration_millis BIGINT NULL,
    outcome VARCHAR(32) NULL,
    failure_code VARCHAR(64) NULL,
    provider_message_reference VARCHAR(128) NULL,
    destination_fingerprint VARCHAR(128) NULL,
    correlation_id VARCHAR(128) NULL,
    trace_id VARCHAR(128) NULL,
    CONSTRAINT fk_notification_delivery_attempt_outbox FOREIGN KEY (outbox_id)
        REFERENCES clinic_notification_outbox(id) ON DELETE RESTRICT,
    CONSTRAINT uk_notification_delivery_attempt_number UNIQUE (outbox_id, attempt_number),
    CONSTRAINT ck_notification_delivery_attempt_outcome CHECK (
        outcome IS NULL OR outcome IN ('SUCCESS', 'RETRY_SCHEDULED', 'SUPPRESSED', 'EXHAUSTED', 'LEASE_LOST')
    ),
    INDEX idx_notification_delivery_attempt_completed_retention (completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
