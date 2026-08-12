-- V26: tenant-scoped patient authentication accounts and structured login identities.

CREATE TABLE IF NOT EXISTS scheduling_patient_accounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT       NOT NULL,
    patient_subject VARCHAR(160) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    active          BOOLEAN      DEFAULT TRUE NOT NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_patient_accounts_tenant
        FOREIGN KEY (tenant_group_id) REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT ck_patient_accounts_subject_length
        CHECK (CHAR_LENGTH(patient_subject) BETWEEN 1 AND 160),
    CONSTRAINT ck_patient_accounts_display_name_length
        CHECK (CHAR_LENGTH(display_name) BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_accounts_tenant_subject
    ON scheduling_patient_accounts (tenant_group_id, patient_subject);
CREATE INDEX IF NOT EXISTS idx_patient_accounts_tenant_active
    ON scheduling_patient_accounts (tenant_group_id, active);

CREATE TABLE IF NOT EXISTS scheduling_patient_login_identities (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_account_id BIGINT       NOT NULL,
    tenant_group_id    BIGINT       NOT NULL,
    identifier_key     VARCHAR(16)  NOT NULL,
    normalized_value   VARCHAR(254) NOT NULL,
    created_at         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_patient_login_identities_account
        FOREIGN KEY (patient_account_id) REFERENCES scheduling_patient_accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_patient_login_identities_tenant
        FOREIGN KEY (tenant_group_id) REFERENCES scheduling_tenant_groups(id) ON DELETE RESTRICT,
    CONSTRAINT ck_patient_login_identities_key
        CHECK (identifier_key IN ('PHONE', 'EMAIL', 'LOGIN_ID')),
    CONSTRAINT ck_patient_login_identities_value_length
        CHECK (CHAR_LENGTH(normalized_value) BETWEEN 1 AND 254)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_login_identities_tenant_key_value
    ON scheduling_patient_login_identities (tenant_group_id, identifier_key, normalized_value);
CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_login_identities_account_key
    ON scheduling_patient_login_identities (patient_account_id, identifier_key);
CREATE INDEX IF NOT EXISTS idx_patient_login_identities_account
    ON scheduling_patient_login_identities (patient_account_id);
