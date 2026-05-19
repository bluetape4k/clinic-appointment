-- ============================================================
-- V5: Enforce tenant ownership (PostgreSQL)
-- ============================================================

ALTER TABLE scheduling_clinics
    ALTER COLUMN tenant_group_id SET NOT NULL;

ALTER TABLE scheduling_holidays
    ALTER COLUMN tenant_group_id SET NOT NULL;
