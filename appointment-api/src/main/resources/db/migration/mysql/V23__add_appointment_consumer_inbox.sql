-- V23: persist tenant-scoped consumer deduplication and metadata-only quarantine.

CREATE TABLE scheduling_appointment_consumer_inbox (
    logical_consumer_id VARCHAR(128) NOT NULL,
    logical_stream_id   VARCHAR(128) NOT NULL,
    event_id            VARCHAR(128) NOT NULL,
    topic               VARCHAR(249) NOT NULL,
    partition_number    INT NOT NULL,
    offset_value        BIGINT NOT NULL,
    schema_version      INT NOT NULL,
    tenant_group_id     BIGINT NOT NULL,
    clinic_id           BIGINT NOT NULL,
    payload_sha256      VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    attempt_count       INT NOT NULL,
    failure_code        VARCHAR(64),
    received_at         DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    processed_at        DATETIME(6),
    CONSTRAINT pk_appointment_consumer_inbox
        PRIMARY KEY (logical_consumer_id, logical_stream_id, event_id),
    CONSTRAINT ck_consumer_inbox_partition CHECK (partition_number >= 0),
    CONSTRAINT ck_consumer_inbox_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_consumer_inbox_schema CHECK (schema_version > 0),
    CONSTRAINT ck_consumer_inbox_scope CHECK (tenant_group_id > 0 AND clinic_id > 0),
    CONSTRAINT ck_consumer_inbox_attempt CHECK (attempt_count > 0),
    CONSTRAINT ck_consumer_inbox_status CHECK (
        status IN ('PROCESSING', 'PROCESSED', 'RETRYABLE', 'QUARANTINED')
    )
);

CREATE INDEX idx_appointment_consumer_inbox_status_received
    ON scheduling_appointment_consumer_inbox(logical_consumer_id, status, received_at);
CREATE INDEX idx_appointment_consumer_inbox_scope
    ON scheduling_appointment_consumer_inbox(logical_consumer_id, tenant_group_id, clinic_id, received_at);

CREATE TABLE scheduling_appointment_consumer_quarantine (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    logical_consumer_id VARCHAR(128) NOT NULL,
    logical_stream_id   VARCHAR(128) NOT NULL,
    event_id            VARCHAR(128) NOT NULL,
    failure_code        VARCHAR(64) NOT NULL,
    topic               VARCHAR(249) NOT NULL,
    partition_number    INT NOT NULL,
    offset_value        BIGINT NOT NULL,
    schema_version      INT NOT NULL,
    tenant_group_id     BIGINT NOT NULL,
    clinic_id           BIGINT NOT NULL,
    payload_sha256      VARCHAR(64) NOT NULL,
    created_at          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_appointment_consumer_quarantine_event
        UNIQUE (logical_consumer_id, logical_stream_id, event_id),
    CONSTRAINT ck_consumer_quarantine_partition CHECK (partition_number >= 0),
    CONSTRAINT ck_consumer_quarantine_offset CHECK (offset_value >= 0),
    CONSTRAINT ck_consumer_quarantine_schema CHECK (schema_version > 0),
    CONSTRAINT ck_consumer_quarantine_scope CHECK (tenant_group_id > 0 AND clinic_id > 0)
);

CREATE INDEX idx_appointment_consumer_quarantine_created
    ON scheduling_appointment_consumer_quarantine(created_at);

CREATE TABLE scheduling_appointment_stats_projection (
    tenant_group_id     BIGINT NOT NULL,
    clinic_id           BIGINT NOT NULL,
    event_date          DATE NOT NULL,
    status              VARCHAR(32) NOT NULL,
    appointment_count   BIGINT NOT NULL,
    last_event_version  BIGINT NOT NULL,
    last_event_id       VARCHAR(128) NOT NULL,
    updated_at          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_appointment_stats_projection
        PRIMARY KEY (tenant_group_id, clinic_id, event_date, status),
    CONSTRAINT ck_appointment_stats_projection_scope CHECK (tenant_group_id > 0 AND clinic_id > 0),
    CONSTRAINT ck_appointment_stats_projection_count CHECK (appointment_count > 0),
    CONSTRAINT ck_appointment_stats_projection_version CHECK (last_event_version >= 0)
);

CREATE INDEX idx_appointment_stats_projection_scope_date
    ON scheduling_appointment_stats_projection(tenant_group_id, clinic_id, event_date);
CREATE INDEX idx_appointment_stats_projection_scope_status_date
    ON scheduling_appointment_stats_projection(tenant_group_id, clinic_id, status, event_date);

CREATE TABLE scheduling_appointment_consumer_replay_audit (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(128) NOT NULL,
    logical_consumer_id VARCHAR(128) NOT NULL,
    logical_stream_id   VARCHAR(128) NOT NULL,
    tenant_group_id     BIGINT NOT NULL,
    clinic_id           BIGINT NOT NULL,
    from_offset         BIGINT NOT NULL,
    to_offset           BIGINT NOT NULL,
    dry_run             BOOLEAN NOT NULL,
    approved_by         VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    created_at          DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    completed_at        DATETIME(6),
    CONSTRAINT uq_appointment_consumer_replay_request UNIQUE (request_id),
    CONSTRAINT ck_appointment_consumer_replay_scope CHECK (tenant_group_id > 0 AND clinic_id > 0),
    CONSTRAINT ck_appointment_consumer_replay_offsets CHECK (from_offset >= 0 AND to_offset >= from_offset),
    CONSTRAINT ck_appointment_consumer_replay_status CHECK (status IN ('REQUESTED', 'DRY_RUN', 'EXECUTED', 'REJECTED'))
);

CREATE INDEX idx_appointment_consumer_replay_audit_scope_created
    ON scheduling_appointment_consumer_replay_audit(tenant_group_id, clinic_id, created_at);
