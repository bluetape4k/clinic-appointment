-- ============================================================
-- V4: Seed default tenant and backfill existing rows (PostgreSQL)
-- ============================================================

INSERT INTO scheduling_tenant_groups (id, tenant_code, display_name, active, created_at)
VALUES (1, 'tenant-default', 'Default Tenant', TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_code) DO NOTHING;

UPDATE scheduling_clinics
SET tenant_group_id = 1
WHERE tenant_group_id IS NULL;

UPDATE scheduling_holidays
SET tenant_group_id = 1
WHERE tenant_group_id IS NULL;
