-- V30: durable, non-PII checkpoint for bounded patient-scope backfill.
CREATE TABLE scheduling_patient_history_backfill_checkpoint (
    scope VARCHAR(64) NOT NULL,
    migration_version INT NOT NULL DEFAULT 30,
    dialect VARCHAR(16) NOT NULL DEFAULT 'mysql',
    last_detail_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_patient_history_backfill_checkpoint PRIMARY KEY (scope),
    CONSTRAINT ck_patient_history_backfill_checkpoint_cursor CHECK (last_detail_id >= 0),
    CONSTRAINT ck_patient_history_backfill_checkpoint_version CHECK (migration_version = 30)
);
