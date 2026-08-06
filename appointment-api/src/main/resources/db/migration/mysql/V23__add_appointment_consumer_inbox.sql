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
