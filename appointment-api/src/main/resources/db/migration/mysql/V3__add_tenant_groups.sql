-- ============================================================
-- V3: Add tenant groups and nullable tenant ownership (MySQL 8)
-- ============================================================

CREATE TABLE IF NOT EXISTS scheduling_tenant_groups (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_code  VARCHAR(64)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    active       BOOLEAN      DEFAULT TRUE,
    created_at   DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_tenant_groups_code UNIQUE (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE scheduling_clinics
    ADD COLUMN tenant_group_id BIGINT NULL;

ALTER TABLE scheduling_holidays
    ADD COLUMN tenant_group_id BIGINT NULL;

ALTER TABLE scheduling_holidays
    DROP INDEX holiday_date;
