-- V22: add appointment messaging envelope and relay lease metadata.
-- Existing plan/policy rows remain nullable and are not backfilled during the
-- rolling deployment. Appointment writers populate the new fields explicitly.

ALTER TABLE scheduling_outbox_events
    ADD COLUMN occurred_at DATETIME(6) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN topic VARCHAR(249) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN partition_key VARCHAR(512) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN lease_owner VARCHAR(160) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN lease_token VARCHAR(128) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN lease_until DATETIME(6) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN last_failure_code VARCHAR(64) NULL;
ALTER TABLE scheduling_outbox_events
    ADD COLUMN last_failure_at DATETIME(6) NULL;

CREATE INDEX idx_outbox_appointment_ready
    ON scheduling_outbox_events(
        status,
        aggregate_type,
        event_type,
        next_attempt_at,
        lease_until,
        created_at,
        id
    );

CREATE INDEX idx_outbox_appointment_lease_recovery
    ON scheduling_outbox_events(
        status,
        aggregate_type,
        event_type,
        lease_until,
        id
    );
