-- V21: introduce tenant ownership for legacy event logs and direct notification lookup.
-- This rolling migration deliberately keeps event_log.tenant_group_id nullable.  The
-- follow-up release performs the zero-null check and NOT NULL transition after old
-- application nodes have drained.

ALTER TABLE scheduling_appointment_event_logs
    ADD COLUMN tenant_group_id BIGINT NULL;

UPDATE scheduling_appointment_event_logs event_log
SET tenant_group_id = clinic.tenant_group_id
FROM scheduling_clinics clinic
WHERE clinic.id = event_log.clinic_id
  AND event_log.tenant_group_id IS NULL;

CREATE INDEX idx_appointment_event_logs_tenant_scope
    ON scheduling_appointment_event_logs(tenant_group_id, clinic_id, created_at, id);

ALTER TABLE scheduling_appointment_event_logs
    ADD CONSTRAINT fk_appointment_event_logs_tenant_group
        FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id)
        ON DELETE RESTRICT;

CREATE INDEX idx_notification_outbox_tenant_direct_lookup
    ON clinic_notification_outbox(
        tenant_group_id,
        clinic_id,
        appointment_id,
        event_type,
        row_kind,
        status,
        available_at,
        next_retry_at,
        id
    );
