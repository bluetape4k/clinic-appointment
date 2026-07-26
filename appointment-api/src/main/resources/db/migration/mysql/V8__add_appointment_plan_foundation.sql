-- Appointment plan foundation (MySQL 8)

CREATE TABLE scheduling_product_catalog_projections (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    source_authority VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    catalog_version BIGINT NOT NULL,
    catalog_status VARCHAR(32) NOT NULL,
    product_name VARCHAR(256) NOT NULL,
    schema_version INTEGER NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    initial_booking_rule_type VARCHAR(64),
    initial_booking_maximum_days INTEGER,
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_catalog_projection_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_catalog_projection_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE RESTRICT,
    CONSTRAINT uq_catalog_scope_version UNIQUE (
        tenant_group_id, clinic_id, source_authority, product_id, catalog_version
    ),
    INDEX idx_catalog_scope_product (
        tenant_group_id, clinic_id, source_authority, product_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_product_catalog_bom_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    catalog_projection_id BIGINT NOT NULL,
    bom_item_id VARCHAR(128) NOT NULL,
    bom_order INTEGER NOT NULL,
    representative_treatment_name VARCHAR(256) NOT NULL,
    detailed_treatment_codes_json TEXT NOT NULL,
    repeat_count INTEGER NOT NULL,
    duration_minutes INTEGER NOT NULL,
    minimum_interval_days INTEGER,
    preferred_interval_days INTEGER,
    maximum_interval_days INTEGER,
    practitioner_qualifications_json TEXT NOT NULL,
    equipment_types_json TEXT NOT NULL,
    room_types_json TEXT NOT NULL,
    CONSTRAINT fk_catalog_bom_item_projection FOREIGN KEY (catalog_projection_id)
        REFERENCES scheduling_product_catalog_projections(id) ON DELETE CASCADE,
    CONSTRAINT uq_catalog_bom_item UNIQUE (catalog_projection_id, bom_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_product_catalog_bom_dependencies (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    catalog_projection_id BIGINT NOT NULL,
    predecessor_bom_item_id VARCHAR(128) NOT NULL,
    predecessor_sequence_no INTEGER DEFAULT 0 NOT NULL,
    successor_bom_item_id VARCHAR(128) NOT NULL,
    successor_sequence_no INTEGER DEFAULT 0 NOT NULL,
    minimum_interval_days INTEGER NOT NULL,
    preferred_interval_days INTEGER NOT NULL,
    maximum_interval_days INTEGER NOT NULL,
    CONSTRAINT fk_catalog_bom_dependency_projection FOREIGN KEY (catalog_projection_id)
        REFERENCES scheduling_product_catalog_projections(id) ON DELETE CASCADE,
    CONSTRAINT uq_catalog_bom_dependency UNIQUE (
        catalog_projection_id, predecessor_bom_item_id, predecessor_sequence_no,
        successor_bom_item_id, successor_sequence_no
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_appointment_plans (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    catalog_projection_id BIGINT NOT NULL,
    source_purchase_authority VARCHAR(128) NOT NULL,
    source_purchase_id VARCHAR(128) NOT NULL,
    patient_reference_ciphertext TEXT NOT NULL,
    patient_reference_key_id VARCHAR(128) NOT NULL,
    patient_reference_fingerprint VARCHAR(128) NOT NULL,
    catalog_source_authority VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    catalog_version BIGINT NOT NULL,
    catalog_payload_hash VARCHAR(64) NOT NULL,
    product_name VARCHAR(256) NOT NULL,
    booking_preference_type VARCHAR(64) NOT NULL,
    booking_preference_payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_appointment_plan_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_plan_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_plan_catalog FOREIGN KEY (catalog_projection_id)
        REFERENCES scheduling_product_catalog_projections(id) ON DELETE RESTRICT,
    CONSTRAINT uq_plan_source_purchase UNIQUE (
        tenant_group_id, clinic_id, source_purchase_authority, source_purchase_id
    ),
    INDEX idx_plan_tenant_clinic_status (tenant_group_id, clinic_id, status),
    INDEX idx_plan_scope_purchase (
        tenant_group_id, clinic_id, source_purchase_authority, source_purchase_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_planned_treatments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    bom_item_id VARCHAR(128) NOT NULL,
    sequence_no INTEGER NOT NULL,
    bom_order INTEGER NOT NULL,
    representative_treatment_name VARCHAR(256) NOT NULL,
    detailed_treatment_codes_json TEXT NOT NULL,
    duration_minutes INTEGER NOT NULL,
    minimum_interval_days INTEGER,
    preferred_interval_days INTEGER,
    maximum_interval_days INTEGER,
    practitioner_qualifications_json TEXT NOT NULL,
    equipment_types_json TEXT NOT NULL,
    room_types_json TEXT NOT NULL,
    earliest_start_at DATETIME(6),
    latest_start_at DATETIME(6),
    status VARCHAR(32) NOT NULL,
    CONSTRAINT fk_planned_treatment_plan FOREIGN KEY (plan_id)
        REFERENCES scheduling_appointment_plans(id) ON DELETE CASCADE,
    CONSTRAINT uq_planned_treatment_sequence UNIQUE (plan_id, bom_item_id, sequence_no),
    INDEX idx_treatment_plan_status_window (plan_id, status, earliest_start_at, latest_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_treatment_dependencies (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    predecessor_treatment_id BIGINT NOT NULL,
    successor_treatment_id BIGINT NOT NULL,
    minimum_interval_days INTEGER NOT NULL,
    preferred_interval_days INTEGER NOT NULL,
    maximum_interval_days INTEGER NOT NULL,
    CONSTRAINT fk_treatment_dependency_plan FOREIGN KEY (plan_id)
        REFERENCES scheduling_appointment_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_treatment_dependency_predecessor FOREIGN KEY (predecessor_treatment_id)
        REFERENCES scheduling_planned_treatments(id) ON DELETE CASCADE,
    CONSTRAINT fk_treatment_dependency_successor FOREIGN KEY (successor_treatment_id)
        REFERENCES scheduling_planned_treatments(id) ON DELETE CASCADE,
    CONSTRAINT uq_treatment_dependency UNIQUE (predecessor_treatment_id, successor_treatment_id),
    INDEX idx_treatment_dependency_plan (
        plan_id, predecessor_treatment_id, successor_treatment_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_inbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    producer VARCHAR(128) NOT NULL,
    source_aggregate_id VARCHAR(128) NOT NULL,
    source_aggregate_version BIGINT NOT NULL,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    replay_after DATETIME(6),
    failure_code VARCHAR(128),
    attempt_count INTEGER DEFAULT 0 NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6),
    CONSTRAINT fk_inbox_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inbox_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE RESTRICT,
    CONSTRAINT ck_inbox_status CHECK (
        status IN ('RECEIVED', 'WAITING_GAP', 'PROCESSED', 'QUARANTINED')
    ),
    CONSTRAINT uq_inbox_event_id UNIQUE (event_id),
    INDEX idx_inbox_status_replay_after_received (status, replay_after, received_at),
    INDEX idx_inbox_source_version (producer, source_aggregate_id, source_aggregate_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER DEFAULT 0 NOT NULL,
    next_attempt_at DATETIME(6),
    created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    published_at DATETIME(6),
    CONSTRAINT fk_outbox_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbox_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbox_plan FOREIGN KEY (plan_id)
        REFERENCES scheduling_appointment_plans(id) ON DELETE RESTRICT,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    INDEX idx_outbox_status_created_at (status, created_at),
    INDEX idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_quarantine_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    envelope_hash VARCHAR(64) NOT NULL,
    encrypted_original_envelope TEXT,
    encryption_key_id VARCHAR(128) NOT NULL,
    producer VARCHAR(128) NOT NULL,
    source_authority VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    source_aggregate_id VARCHAR(128) NOT NULL,
    source_aggregate_version BIGINT NOT NULL,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    detected_at DATETIME(6) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    retention_class VARCHAR(32) NOT NULL,
    payload_expires_at DATETIME(6) NOT NULL,
    legal_hold BOOLEAN DEFAULT FALSE NOT NULL,
    status VARCHAR(32) NOT NULL,
    CONSTRAINT fk_quarantine_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_quarantine_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE RESTRICT,
    CONSTRAINT uq_quarantine_event_id UNIQUE (event_id),
    INDEX idx_quarantine_status_expiry (status, legal_hold, payload_expires_at),
    INDEX idx_quarantine_scope_reason (tenant_group_id, clinic_id, reason_code, detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_quarantine_audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quarantine_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    dry_run_diff_hash VARCHAR(128),
    before_status VARCHAR(32),
    after_status VARCHAR(32),
    approval_references VARCHAR(512),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_quarantine_audit_quarantine FOREIGN KEY (quarantine_id)
        REFERENCES scheduling_quarantine_events(id) ON DELETE RESTRICT,
    INDEX idx_quarantine_audit_quarantine_created (quarantine_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
