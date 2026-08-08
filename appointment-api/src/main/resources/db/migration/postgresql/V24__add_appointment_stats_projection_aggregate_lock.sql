-- V24: serialize stats projection updates for each tenant/clinic/aggregate key.

CREATE TABLE scheduling_appointment_stats_projection_aggregate_locks (
    tenant_group_id BIGINT NOT NULL,
    clinic_id       BIGINT NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    CONSTRAINT pk_appointment_stats_projection_aggregate_locks
        PRIMARY KEY (tenant_group_id, clinic_id, aggregate_id),
    CONSTRAINT ck_appointment_stats_projection_aggregate_locks_scope
        CHECK (tenant_group_id > 0 AND clinic_id > 0)
);
