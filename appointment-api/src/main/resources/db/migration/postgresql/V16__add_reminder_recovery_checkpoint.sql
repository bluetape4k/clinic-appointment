CREATE TABLE clinic_notification_reminder_checkpoint (
    scope VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    last_appointment_id BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notification_reminder_checkpoint PRIMARY KEY (scope),
    CONSTRAINT ck_notification_reminder_checkpoint_cursor CHECK (last_appointment_id >= 0)
);
