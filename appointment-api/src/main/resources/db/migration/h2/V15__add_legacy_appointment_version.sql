ALTER TABLE scheduling_appointments
    ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE scheduling_appointments
    ADD CONSTRAINT chk_appointments_version_nonnegative
        CHECK (version >= 0);
