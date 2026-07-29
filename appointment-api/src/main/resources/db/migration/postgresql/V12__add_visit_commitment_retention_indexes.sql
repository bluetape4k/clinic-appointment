CREATE INDEX idx_appointment_idempotency_retention
    ON scheduling_appointment_command_idempotencies (tenant_group_id, clinic_id, created_at, id);

CREATE INDEX idx_inbox_retention
    ON scheduling_inbox_events (tenant_group_id, clinic_id, status, received_at, id);

CREATE INDEX idx_outbox_retention
    ON scheduling_outbox_events (tenant_group_id, clinic_id, status, published_at, id);
