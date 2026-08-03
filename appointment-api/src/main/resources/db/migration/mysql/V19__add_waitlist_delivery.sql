-- V19: waitlist delivery policy, adjustment, vacancy job, and command idempotency schema.
-- This migration is additive and keeps every V18 scheduling_* table name intact.

CREATE TABLE scheduling_waitlist_policy_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    policy_digest VARCHAR(64) NOT NULL,
    urgency_weight INT NOT NULL DEFAULT 0,
    recovery_weight INT NOT NULL DEFAULT 0,
    benefit_weight INT NOT NULL DEFAULT 0,
    reliability_weight INT NOT NULL DEFAULT 0,
    waiting_age_weight INT NOT NULL DEFAULT 0,
    slot_fit_weight INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    effective_from TIMESTAMP(6) NOT NULL,
    effective_until TIMESTAMP(6),
    canonical_policy_json TEXT NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    retired_by VARCHAR(160),
    retired_at TIMESTAMP(6),
    CONSTRAINT fk_waitlist_policy_versions_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_policy_versions_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_policy_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_waitlist_policy_weights_bounded CHECK (
        urgency_weight BETWEEN 0 AND 10000
        AND recovery_weight BETWEEN 0 AND 10000
        AND benefit_weight BETWEEN 0 AND 10000
        AND reliability_weight BETWEEN 0 AND 10000
        AND waiting_age_weight BETWEEN 0 AND 10000
        AND slot_fit_weight BETWEEN 0 AND 10000
    ),
    CONSTRAINT ck_waitlist_policy_digest CHECK (REGEXP_LIKE(policy_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_waitlist_policy_effective_range CHECK (
        effective_until IS NULL OR effective_from < effective_until
    ),
    UNIQUE INDEX uq_waitlist_policy_generation (tenant_group_id, clinic_id, generation),
    UNIQUE INDEX uq_waitlist_policy_version (tenant_group_id, clinic_id, policy_version),
    INDEX idx_waitlist_policy_active (tenant_group_id, clinic_id, status, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_waitlist_policy_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(160) NOT NULL,
    correlation_id VARCHAR(160),
    from_generation BIGINT,
    to_generation BIGINT,
    reason_code VARCHAR(96) NOT NULL,
    event_digest VARCHAR(64) NOT NULL,
    payload_json TEXT,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_waitlist_policy_events_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_policy_events_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_policy_event_digest CHECK (REGEXP_LIKE(event_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_waitlist_policy_event_actor_ref CHECK (CHAR_LENGTH(TRIM(actor_ref)) BETWEEN 1 AND 160),
    UNIQUE INDEX uq_waitlist_policy_event_digest (tenant_group_id, clinic_id, event_digest),
    INDEX idx_waitlist_policy_event_scope (tenant_group_id, clinic_id, policy_version, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_booking_restrictions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    restriction_mode VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(160) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6),
    released_by VARCHAR(160),
    released_at TIMESTAMP(6),
    reversal_version BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_booking_restrictions_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_restrictions_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_booking_restriction_digest CHECK (REGEXP_LIKE(evidence_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_booking_restriction_range CHECK (expires_at IS NULL OR starts_at < expires_at),
    CONSTRAINT ck_booking_restriction_mode CHECK (
        restriction_mode IN ('REQUIRES_STAFF_APPROVAL', 'EXCLUDE_AUTOMATIC_OFFER')
    ),
    INDEX idx_booking_restriction_active (tenant_group_id, clinic_id, member_id, starts_at, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_disruption_recovery_credits (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    source_appointment_id BIGINT,
    credit_digest VARCHAR(64) NOT NULL,
    priority_boost INT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    granted_by VARCHAR(160) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6),
    reversed_by VARCHAR(160),
    reversed_at TIMESTAMP(6),
    reversal_version BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_disruption_recovery_credits_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_disruption_recovery_credits_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_disruption_recovery_credit_digest CHECK (REGEXP_LIKE(credit_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_disruption_recovery_credit_boost CHECK (priority_boost BETWEEN 0 AND 10000),
    UNIQUE INDEX uq_disruption_recovery_credit (tenant_group_id, clinic_id, credit_digest),
    INDEX idx_disruption_recovery_credit_active (tenant_group_id, clinic_id, member_id, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_booking_benefit_grants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    approval_reference VARCHAR(160) NOT NULL,
    benefit_type VARCHAR(64) NOT NULL,
    benefit_cap INT NOT NULL,
    grant_digest VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6),
    consumed_at TIMESTAMP(6),
    revoked_by VARCHAR(160),
    revoked_at TIMESTAMP(6),
    revoke_version BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_booking_benefit_grants_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_benefit_grants_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_booking_benefit_grant_digest CHECK (REGEXP_LIKE(grant_digest, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_booking_benefit_grant_cap CHECK (benefit_cap BETWEEN 0 AND 10000),
    CONSTRAINT ck_booking_benefit_grant_range CHECK (expires_at IS NULL OR starts_at < expires_at),
    UNIQUE INDEX uq_booking_benefit_grant (tenant_group_id, clinic_id, grant_digest),
    INDEX idx_booking_benefit_grant_active (tenant_group_id, clinic_id, member_id, starts_at, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_waitlist_vacancy_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    vacancy_key VARCHAR(128) NOT NULL,
    vacancy_generation BIGINT NOT NULL,
    active_vacancy_key VARCHAR(128),
    source_appointment_id BIGINT NOT NULL,
    source_transition_id VARCHAR(160) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    capacity_units INT NOT NULL,
    maximum_capacity INT NOT NULL,
    treatment_type_id BIGINT NOT NULL,
    doctor_id BIGINT,
    policy_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(160),
    lease_version BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMP(6),
    next_attempt_at TIMESTAMP(6) NOT NULL,
    vacancy_starts_at TIMESTAMP(6) NOT NULL,
    vacancy_ends_at TIMESTAMP(6) NOT NULL,
    offered_waitlist_entry_id BIGINT,
    last_error_code VARCHAR(96),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_waitlist_vacancy_jobs_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_vacancy_jobs_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_vacancy_job_status CHECK (
        status IN ('READY', 'PROCESSING', 'OFFERED', 'NO_CANDIDATE', 'EXPIRED', 'FAILED')
    ),
    CONSTRAINT ck_waitlist_vacancy_job_time_range CHECK (vacancy_starts_at < vacancy_ends_at),
    CONSTRAINT ck_waitlist_vacancy_job_units CHECK (
        capacity_units > 0 AND maximum_capacity > 0 AND capacity_units <= maximum_capacity
    ),
    UNIQUE INDEX uq_waitlist_vacancy_generation (tenant_group_id, clinic_id, vacancy_key, vacancy_generation),
    UNIQUE INDEX uq_waitlist_vacancy_source_transition (tenant_group_id, clinic_id, source_appointment_id, source_transition_id),
    UNIQUE INDEX uq_waitlist_vacancy_active (tenant_group_id, clinic_id, active_vacancy_key),
    INDEX idx_waitlist_vacancy_due (status, next_attempt_at, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduling_waitlist_command_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    key_digest VARCHAR(76) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_type VARCHAR(64),
    result_id BIGINT,
    response_digest VARCHAR(64),
    failure_code VARCHAR(96),
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_waitlist_command_records_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_command_records_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_command_record_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_waitlist_command_key_digest CHECK (REGEXP_LIKE(key_digest, '^wl-v1:[0-9a-f]{64}$')),
    CONSTRAINT ck_waitlist_command_request_digest CHECK (REGEXP_LIKE(request_digest, '^[0-9a-f]{64}$')),
    UNIQUE INDEX uq_waitlist_command_idempotency (tenant_group_id, clinic_id, command_type, key_digest),
    INDEX idx_waitlist_command_retention (tenant_group_id, clinic_id, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_waitlist_delivery_candidate_scope_order
    ON scheduling_waitlist_entries (tenant_group_id, clinic_id, status, updated_at, id);
CREATE INDEX idx_waitlist_delivery_offer_active_entry
    ON scheduling_waitlist_offers (tenant_group_id, clinic_id, active_entry_key, status, id);
