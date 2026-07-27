-- Scheduling policy foundation (MySQL 8).
--
-- Additive V9 only. clinic_scope_key avoids nullable-unique differences, and
-- aggregate_type/aggregate_id remain nullable until the separately gated V10
-- cutover proves every writer dual-writes.

CREATE TABLE scheduling_policy_definitions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    clinic_id BIGINT,
    clinic_scope_key BIGINT NOT NULL,
    policy_kind VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    lifecycle VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6),
    revision BIGINT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    created_by_actor_id VARCHAR(160) NOT NULL,
    created_by_actor_role VARCHAR(24) NOT NULL,
    change_reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_policy_definition UNIQUE (
        tenant_group_id, scope, clinic_scope_key, policy_kind, version
    ),
    CONSTRAINT ck_policy_definition_scope CHECK (
        (scope = 'TENANT_DEFAULT' AND clinic_id IS NULL AND clinic_scope_key = 0)
        OR
        (scope = 'CLINIC_OVERRIDE' AND clinic_id IS NOT NULL
            AND clinic_id > 0 AND clinic_scope_key = clinic_id)
    ),
    CONSTRAINT ck_policy_definition_interval CHECK (
        effective_until IS NULL OR effective_until > effective_from
    ),
    CONSTRAINT ck_policy_definition_versions CHECK (
        version > 0 AND schema_version > 0 AND revision > 0
    ),
    CONSTRAINT ck_policy_definition_lifecycle CHECK (
        lifecycle IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'RETIRED')
    ),
    INDEX idx_policy_definition_effective (
        tenant_group_id, scope, clinic_scope_key, policy_kind, lifecycle, effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_policy_approvals (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    definition_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    actor_role VARCHAR(24) NOT NULL,
    assurance_level VARCHAR(64) NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_policy_approval_definition FOREIGN KEY (definition_id)
        REFERENCES scheduling_policy_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_policy_approval UNIQUE (definition_id, draft_revision, actor_id),
    CONSTRAINT ck_policy_approval_revision CHECK (draft_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_policy_scope_heads (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    clinic_scope_key BIGINT NOT NULL,
    revision BIGINT DEFAULT 0 NOT NULL,
    generation BIGINT DEFAULT 0 NOT NULL,
    updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_policy_scope_head UNIQUE (tenant_group_id, scope, clinic_scope_key),
    CONSTRAINT ck_policy_scope_head_scope CHECK (
        (scope = 'TENANT_DEFAULT' AND clinic_scope_key = 0)
        OR
        (scope = 'CLINIC_OVERRIDE' AND clinic_scope_key > 0)
    ),
    CONSTRAINT ck_policy_scope_head_counters CHECK (revision >= 0 AND generation >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE effective_scheduling_policy_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    decision_at DATETIME(6) NOT NULL,
    service_at DATETIME(6) NOT NULL,
    tenant_generation BIGINT NOT NULL,
    clinic_generation BIGINT NOT NULL,
    source_versions_json MEDIUMTEXT NOT NULL,
    source_by_path_json MEDIUMTEXT NOT NULL,
    disabled_features_json MEDIUMTEXT NOT NULL,
    warnings_json MEDIUMTEXT NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_effective_policy_hash UNIQUE (tenant_group_id, clinic_id, snapshot_hash),
    CONSTRAINT ck_effective_policy_generation CHECK (
        tenant_group_id > 0 AND clinic_id > 0
        AND tenant_generation > 0 AND clinic_generation >= 0
        AND service_at >= decision_at
    ),
    INDEX idx_effective_policy_generation (
        tenant_group_id, clinic_id, tenant_generation, clinic_generation
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_policy_activation_commands (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    clinic_id BIGINT,
    clinic_scope_key BIGINT NOT NULL,
    definition_id BIGINT NOT NULL,
    replay_of_command_id BIGINT,
    expected_draft_revision BIGINT NOT NULL,
    expected_active_revision BIGINT NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(160),
    lease_until DATETIME(6),
    attempt INTEGER DEFAULT 0 NOT NULL,
    result_tenant_generation BIGINT,
    result_clinic_generation BIGINT,
    event_id VARCHAR(160),
    last_error_code VARCHAR(96),
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_policy_activation_idempotency UNIQUE (
        tenant_group_id, scope, clinic_scope_key, idempotency_key_hash
    ),
    CONSTRAINT ck_policy_activation_scope CHECK (
        (scope = 'TENANT_DEFAULT' AND clinic_id IS NULL AND clinic_scope_key = 0)
        OR
        (scope = 'CLINIC_OVERRIDE' AND clinic_id IS NOT NULL
            AND clinic_id > 0 AND clinic_scope_key = clinic_id)
    ),
    CONSTRAINT ck_policy_activation_state CHECK (
        status IN ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'COMPLETED', 'MISSED')
        AND attempt >= 0
        AND expected_draft_revision > 0
        AND expected_active_revision >= 0
        AND next_attempt_at >= effective_from
        AND (
            (status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
            OR
            (status <> 'CLAIMED' AND lease_owner IS NULL AND lease_until IS NULL)
        )
        AND (
            status <> 'COMPLETED'
            OR
            (result_tenant_generation IS NOT NULL
                AND result_clinic_generation IS NOT NULL AND event_id IS NOT NULL)
        )
    ),
    INDEX idx_policy_activation_due (status, next_attempt_at, lease_until),
    INDEX idx_policy_activation_replay_source (replay_of_command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_policy_preview_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    definition_id BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    tenant_generation BIGINT NOT NULL,
    clinic_generation BIGINT NOT NULL,
    partition_count INTEGER NOT NULL,
    cursor_partition INTEGER DEFAULT 0 NOT NULL,
    cursor_last_appointment_id BIGINT,
    scanned_count BIGINT DEFAULT 0 NOT NULL,
    affected_count BIGINT DEFAULT 0 NOT NULL,
    status VARCHAR(24) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(160),
    lease_until DATETIME(6),
    last_error_code VARCHAR(96),
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_policy_preview_state CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'STALE', 'FAILED', 'CANCELLED')
        AND (
            (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
            OR
            (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
        )
        AND deadline_at > next_attempt_at
    ),
    CONSTRAINT ck_policy_preview_progress CHECK (
        tenant_group_id > 0 AND clinic_id > 0 AND definition_id > 0
        AND draft_revision > 0 AND tenant_generation > 0 AND clinic_generation >= 0
        AND partition_count > 0
        AND cursor_partition >= 0 AND cursor_partition < partition_count
        AND scanned_count >= 0 AND affected_count >= 0 AND affected_count <= scanned_count
    ),
    INDEX idx_policy_preview_due (status, next_attempt_at, lease_until),
    INDEX idx_policy_preview_scope (tenant_group_id, clinic_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL requires MODIFY COLUMN to relax legacy plan, clinic, and causation
-- nullability. Backfill occurs first so every legacy plan event remains fully
-- dual-addressable; command-driven tenant policy events may then omit all three.
ALTER TABLE scheduling_outbox_events ADD COLUMN aggregate_type VARCHAR(64);
ALTER TABLE scheduling_outbox_events ADD COLUMN aggregate_id VARCHAR(160);

UPDATE scheduling_outbox_events
   SET aggregate_type = 'APPOINTMENT_PLAN',
       aggregate_id = CAST(plan_id AS CHAR)
 WHERE plan_id IS NOT NULL
   AND (aggregate_type IS NULL OR aggregate_id IS NULL);

ALTER TABLE scheduling_outbox_events MODIFY COLUMN plan_id BIGINT NULL;
ALTER TABLE scheduling_outbox_events MODIFY COLUMN clinic_id BIGINT NULL;
ALTER TABLE scheduling_outbox_events MODIFY COLUMN causation_event_id VARCHAR(128) NULL;

CREATE INDEX idx_outbox_aggregate
    ON scheduling_outbox_events(aggregate_type, aggregate_id, created_at);
