-- V18: waitlist core tables for additive migration-only rollout.
-- Only opaque member identifiers and bounded reason/audit codes are persisted.

CREATE TABLE scheduling_waitlist_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    treatment_type_id BIGINT NOT NULL,
    doctor_id BIGINT,
    preferred_date_from DATE NOT NULL,
    preferred_date_to DATE NOT NULL,
    preferred_start_time TIME NOT NULL,
    preferred_end_time TIME NOT NULL,
    priority_rank INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    waiting_since TIMESTAMP NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_waitlist_entries_tenant_group FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_entries_clinic FOREIGN KEY (clinic_id)
        REFERENCES scheduling_clinics(id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_entries_treatment_type FOREIGN KEY (treatment_type_id)
        REFERENCES scheduling_treatment_types(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_entries_doctor FOREIGN KEY (doctor_id)
        REFERENCES scheduling_doctors(id) ON DELETE SET NULL,
    CONSTRAINT ck_waitlist_entry_status CHECK (
        REGEXP_LIKE(status, '^(WAITING|OFFERED|ACCEPTED|DECLINED|EXPIRED|WITHDRAWN)$')
    ),
    CONSTRAINT ck_waitlist_entry_date_range CHECK (preferred_date_from <= preferred_date_to),
    CONSTRAINT ck_waitlist_entry_time_range CHECK (preferred_start_time < preferred_end_time),
    CONSTRAINT ck_waitlist_entry_member_opaque CHECK (
        LENGTH(TRIM(member_id)) BETWEEN 1 AND 255
        AND member_id NOT LIKE '%@%'
        AND member_id NOT LIKE '% %'
    )
);
CREATE INDEX idx_waitlist_entry_candidate
    ON scheduling_waitlist_entries (
        tenant_group_id, clinic_id, treatment_type_id, status, preferred_date_from, preferred_date_to,
        priority_rank DESC, waiting_since ASC, id ASC
    );
CREATE INDEX idx_waitlist_entry_doctor_candidate
    ON scheduling_waitlist_entries (
        tenant_group_id, clinic_id, doctor_id, treatment_type_id, status, preferred_date_from, preferred_date_to,
        priority_rank DESC, waiting_since ASC, id ASC
    );

CREATE TABLE scheduling_waitlist_offers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    waitlist_entry_id BIGINT NOT NULL,
    vacancy_key VARCHAR(128) NOT NULL,
    active_entry_key VARCHAR(128),
    active_vacancy_key VARCHAR(128),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    capacity_units INTEGER NOT NULL,
    maximum_capacity INTEGER NOT NULL,
    doctor_id BIGINT,
    treatment_type_id BIGINT NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    booking_reliability_decision_id BIGINT NOT NULL,
    booking_reliability_policy_version_id BIGINT NOT NULL,
    booking_reliability_policy_hash VARCHAR(64) NOT NULL,
    booking_reliability_evaluation_digest VARCHAR(64) NOT NULL,
    booking_reliability_expires_at TIMESTAMP,
    candidate_rank INTEGER NOT NULL,
    selection_reason_code VARCHAR(64) NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_waitlist_offers_entry FOREIGN KEY (waitlist_entry_id)
        REFERENCES scheduling_waitlist_entries(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_offer_status CHECK (
        REGEXP_LIKE(status, '^(OFFERED|ACCEPTED|DECLINED|EXPIRED|WITHDRAWN)$')
    ),
    CONSTRAINT ck_waitlist_offer_time_range CHECK (starts_at < ends_at),
    CONSTRAINT ck_waitlist_offer_expiry CHECK (expires_at <= ends_at),
    CONSTRAINT ck_waitlist_offer_units CHECK (
        capacity_units > 0 AND maximum_capacity > 0 AND capacity_units <= maximum_capacity
    ),
    CONSTRAINT ck_waitlist_offer_policy_hash CHECK (
        booking_reliability_policy_hash IS NULL
        OR REGEXP_LIKE(booking_reliability_policy_hash, '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_waitlist_offer_evaluation_digest CHECK (
        booking_reliability_evaluation_digest IS NULL
        OR REGEXP_LIKE(booking_reliability_evaluation_digest, '^[0-9a-f]{64}$')
    )
);
CREATE UNIQUE INDEX uq_waitlist_offer_active_entry
    ON scheduling_waitlist_offers (tenant_group_id, clinic_id, active_entry_key);
CREATE UNIQUE INDEX uq_waitlist_offer_active_vacancy
    ON scheduling_waitlist_offers (tenant_group_id, clinic_id, active_vacancy_key);
CREATE INDEX idx_waitlist_offer_entry_status
    ON scheduling_waitlist_offers (tenant_group_id, clinic_id, waitlist_entry_id, status, id);
CREATE INDEX idx_waitlist_offer_expiry
    ON scheduling_waitlist_offers (tenant_group_id, clinic_id, status, expires_at, id);

CREATE TABLE scheduling_waitlist_capacity_holds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    offer_id BIGINT NOT NULL,
    vacancy_key VARCHAR(128) NOT NULL,
    active_vacancy_key VARCHAR(128),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    capacity_units INTEGER NOT NULL,
    maximum_capacity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    hold_expires_at TIMESTAMP NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    released_at TIMESTAMP,
    consumed_at TIMESTAMP,
    CONSTRAINT fk_waitlist_capacity_holds_offer FOREIGN KEY (offer_id)
        REFERENCES scheduling_waitlist_offers(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_capacity_hold_status CHECK (
        REGEXP_LIKE(status, '^(OFFERED|ACCEPTED|CONSUMED|RELEASED|EXPIRED)$')
    ),
    CONSTRAINT ck_waitlist_capacity_hold_time_range CHECK (starts_at < ends_at),
    CONSTRAINT ck_waitlist_capacity_hold_units CHECK (
        capacity_units > 0 AND maximum_capacity > 0 AND capacity_units <= maximum_capacity
    )
);
CREATE UNIQUE INDEX uq_waitlist_capacity_hold_offer
    ON scheduling_waitlist_capacity_holds (offer_id);
CREATE UNIQUE INDEX uq_waitlist_capacity_hold_active_vacancy
    ON scheduling_waitlist_capacity_holds (tenant_group_id, clinic_id, active_vacancy_key);
CREATE INDEX idx_waitlist_capacity_hold_overlap
    ON scheduling_waitlist_capacity_holds (
        tenant_group_id, clinic_id, resource_type, resource_id, status, starts_at, ends_at, id
    );
CREATE INDEX idx_waitlist_capacity_hold_expiry
    ON scheduling_waitlist_capacity_holds (tenant_group_id, clinic_id, status, hold_expires_at, id);

CREATE TABLE scheduling_waitlist_offer_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    waitlist_entry_id BIGINT NOT NULL,
    offer_id BIGINT,
    hold_id BIGINT,
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    event_version BIGINT NOT NULL,
    CONSTRAINT fk_waitlist_offer_events_entry FOREIGN KEY (waitlist_entry_id)
        REFERENCES scheduling_waitlist_entries(id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_offer_events_offer FOREIGN KEY (offer_id)
        REFERENCES scheduling_waitlist_offers(id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_offer_events_hold FOREIGN KEY (hold_id)
        REFERENCES scheduling_waitlist_capacity_holds(id) ON DELETE CASCADE,
    CONSTRAINT ck_waitlist_offer_event_version CHECK (event_version > 0),
    CONSTRAINT ck_waitlist_offer_event_actor_ref CHECK (LENGTH(TRIM(actor_ref)) BETWEEN 1 AND 128),
    CONSTRAINT ck_waitlist_offer_event_correlation_id CHECK (
        REGEXP_LIKE(correlation_id, '^[A-Za-z0-9._:-]{1,160}$')
    )
);
CREATE INDEX idx_waitlist_offer_event_entry_time
    ON scheduling_waitlist_offer_events (waitlist_entry_id, occurred_at, id);
CREATE INDEX idx_waitlist_offer_event_offer_version
    ON scheduling_waitlist_offer_events (offer_id, event_version, id);
