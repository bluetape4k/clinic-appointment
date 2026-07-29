ALTER TABLE scheduling_quarantine_events
    ADD COLUMN resolved_at TIMESTAMP;

CREATE INDEX idx_quarantine_resolved_retention
    ON scheduling_quarantine_events(
        tenant_group_id,
        clinic_id,
        legal_hold,
        resolved_at,
        id,
        status
    );
