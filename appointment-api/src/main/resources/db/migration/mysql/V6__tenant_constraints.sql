-- ============================================================
-- V6: Add tenant constraints and indexes (MySQL 8)
-- ============================================================

ALTER TABLE scheduling_clinics
    ADD CONSTRAINT fk_clinics_tenant_group
        FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id)
        ON DELETE RESTRICT;

ALTER TABLE scheduling_holidays
    ADD CONSTRAINT fk_holidays_tenant_group
        FOREIGN KEY (tenant_group_id)
        REFERENCES scheduling_tenant_groups(id)
        ON DELETE RESTRICT;

ALTER TABLE scheduling_holidays
    ADD CONSTRAINT uq_holidays_tenant_date
        UNIQUE (tenant_group_id, holiday_date);

CREATE INDEX idx_clinics_tenant
    ON scheduling_clinics(tenant_group_id);
