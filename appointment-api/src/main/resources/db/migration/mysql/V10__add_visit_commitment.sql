-- Versioned visit commitment schema (MySQL 8).
-- Additive expand migration: existing appointment identity and legacy values are preserved.

ALTER TABLE scheduling_appointments
    ADD COLUMN model_version VARCHAR(24) DEFAULT 'LEGACY' NOT NULL;
ALTER TABLE scheduling_appointments
    ADD COLUMN patient_reference_fingerprint VARCHAR(128);
ALTER TABLE scheduling_appointments MODIFY COLUMN doctor_id BIGINT NULL;
ALTER TABLE scheduling_appointments MODIFY COLUMN treatment_type_id BIGINT NULL;
ALTER TABLE scheduling_appointments MODIFY COLUMN appointment_date DATE NULL;
ALTER TABLE scheduling_appointments MODIFY COLUMN start_time TIME NULL;
ALTER TABLE scheduling_appointments MODIFY COLUMN end_time TIME NULL;

CREATE TABLE scheduling_appointment_commitments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id BIGINT NOT NULL,
    commitment_status VARCHAR(32) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    confirmed_proposal_id BIGINT,
    effective_policy_snapshot_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_commitment_appointment FOREIGN KEY (appointment_id)
        REFERENCES scheduling_appointments(id) ON DELETE CASCADE,
    CONSTRAINT uq_commitment_appointment UNIQUE (appointment_id)
);
CREATE INDEX idx_commitment_confirmed_proposal
    ON scheduling_appointment_commitments(confirmed_proposal_id);

CREATE TABLE scheduling_appointment_proposals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    commitment_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    proposed_start_at TIMESTAMP NOT NULL,
    proposed_end_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    expired_at TIMESTAMP,
    representative_treatment_name VARCHAR(256) NOT NULL,
    proposal_hash VARCHAR(64) NOT NULL,
    policy_snapshot_id BIGINT NOT NULL,
    supersedes_proposal_id BIGINT,
    created_by_actor VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_proposal_commitment FOREIGN KEY (commitment_id)
        REFERENCES scheduling_appointment_commitments(id) ON DELETE CASCADE,
    CONSTRAINT uq_proposal_commitment_revision UNIQUE (commitment_id, revision)
);
CREATE INDEX idx_proposal_current
    ON scheduling_appointment_proposals(commitment_id, revision DESC);
CREATE INDEX idx_proposal_hash
    ON scheduling_appointment_proposals(commitment_id, proposal_hash);

CREATE TABLE scheduling_consent_decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    commitment_id BIGINT NOT NULL,
    subject_type VARCHAR(48) NOT NULL,
    subject_payload TEXT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    evidence_authority VARCHAR(128) NOT NULL,
    evidence_id VARCHAR(128) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    evidence_type VARCHAR(64),
    terms_hash VARCHAR(64),
    decided_at TIMESTAMP NOT NULL,
    actor_ref VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_consent_commitment FOREIGN KEY (commitment_id)
        REFERENCES scheduling_appointment_commitments(id) ON DELETE CASCADE,
    CONSTRAINT uq_consent_evidence UNIQUE (evidence_authority, evidence_id)
);
CREATE INDEX idx_consent_commitment_subject
    ON scheduling_consent_decisions(commitment_id, subject_type);

CREATE TABLE scheduling_appointment_plan_revisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    product_version_id VARCHAR(128) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_plan_revision_plan FOREIGN KEY (plan_id)
        REFERENCES scheduling_appointment_plans(id) ON DELETE CASCADE,
    CONSTRAINT uq_plan_revision UNIQUE (plan_id, revision)
);
CREATE INDEX idx_plan_revision_active
    ON scheduling_appointment_plan_revisions(plan_id, active);

CREATE TABLE scheduling_plan_revision_treatments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_revision_id BIGINT NOT NULL,
    treatment_key VARCHAR(128) NOT NULL,
    component_product_id VARCHAR(128) NOT NULL,
    component_product_version_id VARCHAR(128) NOT NULL,
    product_version_id VARCHAR(128) NOT NULL,
    treatment_status VARCHAR(16) NOT NULL,
    source_bom_item_id VARCHAR(128) NOT NULL,
    sequence_no INTEGER NOT NULL,
    representative_treatment_name VARCHAR(256) NOT NULL,
    detailed_treatment_codes_payload TEXT NOT NULL,
    preparation_minutes INTEGER NOT NULL,
    treatment_minutes INTEGER NOT NULL,
    recovery_minutes INTEGER NOT NULL,
    practitioner_qualifications_payload TEXT NOT NULL,
    equipment_types_payload TEXT NOT NULL,
    space_capabilities_payload TEXT NOT NULL,
    CONSTRAINT fk_revision_treatment_revision FOREIGN KEY (plan_revision_id)
        REFERENCES scheduling_appointment_plan_revisions(id) ON DELETE CASCADE,
    CONSTRAINT uq_plan_revision_treatment UNIQUE (plan_revision_id, treatment_key)
);

CREATE TABLE scheduling_plan_revision_dependencies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_revision_id BIGINT NOT NULL,
    predecessor_treatment_key VARCHAR(128) NOT NULL,
    successor_treatment_key VARCHAR(128) NOT NULL,
    dependency_type VARCHAR(16) NOT NULL,
    minimum_interval_days INTEGER NOT NULL,
    preferred_interval_days INTEGER,
    maximum_interval_days INTEGER,
    CONSTRAINT fk_revision_dependency_revision FOREIGN KEY (plan_revision_id)
        REFERENCES scheduling_appointment_plan_revisions(id) ON DELETE CASCADE,
    CONSTRAINT uq_plan_revision_dependency UNIQUE (
        plan_revision_id, predecessor_treatment_key, successor_treatment_key
    )
);

CREATE TABLE scheduling_plan_revision_grouping_constraints (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_revision_id BIGINT NOT NULL,
    first_treatment_key VARCHAR(128) NOT NULL,
    second_treatment_key VARCHAR(128) NOT NULL,
    grouping_type VARCHAR(32) NOT NULL,
    CONSTRAINT fk_revision_grouping_revision FOREIGN KEY (plan_revision_id)
        REFERENCES scheduling_appointment_plan_revisions(id) ON DELETE CASCADE,
    CONSTRAINT uq_plan_revision_grouping UNIQUE (
        plan_revision_id, first_treatment_key, second_treatment_key
    )
);

CREATE TABLE scheduling_appointment_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    plan_revision_id BIGINT NOT NULL,
    treatment_key VARCHAR(128) NOT NULL,
    representative_treatment_name VARCHAR(256) NOT NULL,
    detailed_treatment_codes_payload TEXT NOT NULL,
    preparation_minutes INTEGER NOT NULL,
    treatment_minutes INTEGER NOT NULL,
    recovery_minutes INTEGER NOT NULL,
    attempt_number INTEGER NOT NULL,
    CONSTRAINT fk_appointment_item_appointment FOREIGN KEY (appointment_id)
        REFERENCES scheduling_appointments(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointment_item_proposal FOREIGN KEY (proposal_id)
        REFERENCES scheduling_appointment_proposals(id) ON DELETE CASCADE,
    CONSTRAINT fk_appointment_item_revision FOREIGN KEY (plan_revision_id)
        REFERENCES scheduling_appointment_plan_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_appointment_item_attempt UNIQUE (
        proposal_id, plan_revision_id, treatment_key, attempt_number
    )
);
CREATE INDEX idx_appointment_item_proposal
    ON scheduling_appointment_items(proposal_id);
CREATE INDEX idx_appointment_item_treatment
    ON scheduling_appointment_items(plan_revision_id, treatment_key);

CREATE TABLE scheduling_treatment_spaces (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    space_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    capabilities_payload TEXT NOT NULL,
    nominal_capacity INTEGER NOT NULL,
    bucket_minutes INTEGER NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_treatment_space_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_treatment_space_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT uq_treatment_space_code UNIQUE (tenant_group_id, clinic_id, space_code)
);
CREATE INDEX idx_treatment_space_active
    ON scheduling_treatment_spaces(tenant_group_id, clinic_id, active);

CREATE TABLE scheduling_resource_capacity_buckets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    bucket_start_at TIMESTAMP NOT NULL,
    maximum_capacity INTEGER NOT NULL,
    CONSTRAINT fk_capacity_bucket_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_capacity_bucket_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT uq_resource_capacity_bucket UNIQUE (
        tenant_group_id, clinic_id, resource_type, resource_id, bucket_start_at
    )
);

CREATE TABLE scheduling_resource_allocations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    appointment_item_key VARCHAR(128),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    capacity_units INTEGER NOT NULL,
    maximum_capacity INTEGER NOT NULL,
    allocation_mode VARCHAR(32) NOT NULL,
    allocation_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    released_at TIMESTAMP,
    CONSTRAINT fk_resource_allocation_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_resource_allocation_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_allocation_proposal FOREIGN KEY (proposal_id)
        REFERENCES scheduling_appointment_proposals(id) ON DELETE CASCADE
);
CREATE INDEX idx_resource_allocation_overlap
    ON scheduling_resource_allocations(
        tenant_group_id, clinic_id, resource_type, resource_id,
        allocation_status, starts_at, ends_at
    );
CREATE INDEX idx_resource_allocation_proposal
    ON scheduling_resource_allocations(proposal_id, allocation_status);

CREATE TABLE scheduling_appointment_operational_exceptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_plan_id BIGINT NOT NULL,
    appointment_id BIGINT,
    exception_type VARCHAR(48) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    exception_status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    CONSTRAINT fk_operational_exception_plan FOREIGN KEY (appointment_plan_id)
        REFERENCES scheduling_appointment_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_operational_exception_appointment FOREIGN KEY (appointment_id)
        REFERENCES scheduling_appointments(id) ON DELETE SET NULL
);
CREATE INDEX idx_operational_exception_open
    ON scheduling_appointment_operational_exceptions(
        appointment_plan_id, exception_status, opened_at
    );

CREATE TABLE scheduling_appointment_command_idempotencies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    actor_scope_hash VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    result_type VARCHAR(64),
    result_id BIGINT,
    result_commitment_id BIGINT,
    result_appointment_id BIGINT,
    result_commitment_status VARCHAR(32),
    result_origin VARCHAR(16),
    result_confirmed_proposal_id BIGINT,
    result_effective_policy_snapshot_id BIGINT,
    result_commitment_version BIGINT,
    result_proposal_revision BIGINT,
    result_proposed_start_at TIMESTAMP,
    result_proposed_end_at TIMESTAMP,
    result_proposal_expires_at TIMESTAMP,
    result_proposal_expired_at TIMESTAMP,
    result_representative_treatment_name VARCHAR(256),
    result_proposal_hash VARCHAR(64),
    result_policy_snapshot_id BIGINT,
    result_supersedes_proposal_id BIGINT,
    result_created_by_actor VARCHAR(128),
    response_hash VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_command_idempotency_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_command_idempotency_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT uq_appointment_command_idempotency UNIQUE (
        tenant_group_id, clinic_id, actor_scope_hash, idempotency_key
    )
);

CREATE TABLE scheduling_appointment_audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    actor_scope_hash VARCHAR(128),
    payload_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_appointment_audit_tenant FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_audit_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE
);
CREATE INDEX idx_appointment_audit_aggregate
    ON scheduling_appointment_audit_events(
        tenant_group_id, clinic_id, aggregate_id, occurred_at DESC
    );
