-- ============================================================
-- V5: Enforce tenant ownership (MySQL 8)
-- ============================================================

ALTER TABLE scheduling_clinics
    MODIFY COLUMN tenant_group_id BIGINT NOT NULL;

ALTER TABLE scheduling_holidays
    MODIFY COLUMN tenant_group_id BIGINT NOT NULL;
