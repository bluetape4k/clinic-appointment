CREATE TABLE clinic_notification_reminder_checkpoint (
    scope VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    last_appointment_id BIGINT DEFAULT 0 NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_notification_reminder_checkpoint PRIMARY KEY (scope),
    CONSTRAINT ck_notification_reminder_checkpoint_cursor CHECK (last_appointment_id >= 0)
);
