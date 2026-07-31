ALTER TABLE scheduling_appointments
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_appointments_version_nonnegative
        CHECK (version >= 0);
