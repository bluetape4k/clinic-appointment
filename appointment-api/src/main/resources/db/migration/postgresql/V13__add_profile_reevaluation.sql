-- Profile-change reservation reevaluation queue (PostgreSQL).
-- Only opaque references, hashes, bounded counters, and operational state are persisted.

CREATE TABLE scheduling_profile_reevaluation_heads (
    id BIGSERIAL PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    patient_reference_fingerprint VARCHAR(64) NOT NULL,
    latest_revision BIGINT NOT NULL,
    latest_event_id VARCHAR(160) NOT NULL,
    assessment_ref VARCHAR(512) NOT NULL,
    assessment_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_profile_reevaluation_head_scope UNIQUE (
        tenant_group_id, clinic_id, patient_reference_fingerprint
    )
);

CREATE TABLE scheduling_profile_reevaluation_jobs (
    id BIGSERIAL PRIMARY KEY,
    head_id BIGINT NOT NULL,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    patient_reference_fingerprint VARCHAR(64) NOT NULL,
    target_revision BIGINT NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    assessment_ref VARCHAR(512) NOT NULL,
    assessment_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    due_at TIMESTAMP NOT NULL,
    target_duration_seconds BIGINT NOT NULL,
    held_target_seconds BIGINT NOT NULL,
    proposed_target_seconds BIGINT NOT NULL,
    target_policy_ref VARCHAR(256) NOT NULL,
    target_policy_generation BIGINT NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMP,
    attempt_count INTEGER DEFAULT 0 NOT NULL,
    first_attempt_at TIMESTAMP,
    redrive_count INTEGER DEFAULT 0 NOT NULL,
    root_job_id BIGINT,
    redrive_of_job_id BIGINT,
    redrive_generation INTEGER DEFAULT 0 NOT NULL,
    priority_class VARCHAR(24) NOT NULL,
    held_cursor_appointment_id BIGINT,
    proposed_cursor_appointment_id BIGINT,
    scanned_count BIGINT DEFAULT 0 NOT NULL,
    proposal_superseded_count BIGINT DEFAULT 0 NOT NULL,
    hold_kept_count BIGINT DEFAULT 0 NOT NULL,
    hold_replaced_count BIGINT DEFAULT 0 NOT NULL,
    fallback_to_proposed_count BIGINT DEFAULT 0 NOT NULL,
    skipped_ineligible_count BIGINT DEFAULT 0 NOT NULL,
    skipped_unchanged_count BIGINT DEFAULT 0 NOT NULL,
    last_failure_code VARCHAR(96),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_profile_reevaluation_job_head FOREIGN KEY (head_id)
        REFERENCES scheduling_profile_reevaluation_heads(id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_reevaluation_job_lineage UNIQUE (
        root_job_id, target_revision, redrive_generation
    ),
    CONSTRAINT ck_profile_reevaluation_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'STALE', 'FAILED')
    ),
    CONSTRAINT ck_profile_reevaluation_priority_class CHECK (
        priority_class IN ('UNCLASSIFIED', 'HELD_PRESENT', 'PROPOSED_ONLY')
    )
);

CREATE INDEX idx_profile_reevaluation_due
    ON scheduling_profile_reevaluation_jobs(status, next_attempt_at, clinic_id, id);
CREATE INDEX idx_profile_reevaluation_lease
    ON scheduling_profile_reevaluation_jobs(status, lease_expires_at, clinic_id, id);
CREATE INDEX idx_profile_reevaluation_clinic_ready
    ON scheduling_profile_reevaluation_jobs(
        tenant_group_id, clinic_id, status, next_attempt_at, due_at, id
    );
CREATE INDEX idx_profile_reevaluation_clinic_lease
    ON scheduling_profile_reevaluation_jobs(
        tenant_group_id, clinic_id, status, lease_expires_at, due_at, id
    );

CREATE TABLE scheduling_profile_reevaluation_outcomes (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    outcome_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_profile_reevaluation_outcome_job FOREIGN KEY (job_id)
        REFERENCES scheduling_profile_reevaluation_jobs(id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_reevaluation_outcome UNIQUE (job_id, appointment_id),
    CONSTRAINT ck_profile_reevaluation_outcome_type CHECK (
        outcome_type IN (
            'PROPOSAL_SUPERSEDED',
            'HOLD_KEPT',
            'HOLD_REPLACED',
            'FALLBACK_TO_PROPOSED',
            'SKIPPED_INELIGIBLE',
            'SKIPPED_UNCHANGED'
        )
    )
);

CREATE INDEX idx_appointment_profile_reevaluation
    ON scheduling_appointments(clinic_id, patient_reference_fingerprint, id);
CREATE INDEX idx_commitment_profile_reevaluation
    ON scheduling_appointment_commitments(commitment_status, appointment_id);
