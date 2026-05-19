-- ============================================================
-- V3: Add tenant groups and nullable tenant ownership
-- ============================================================

CREATE TABLE IF NOT EXISTS scheduling_tenant_groups (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_code  VARCHAR(64)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    active       BOOLEAN      DEFAULT TRUE,
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_groups_code UNIQUE (tenant_code)
);

ALTER TABLE scheduling_clinics
    ADD COLUMN IF NOT EXISTS tenant_group_id BIGINT NULL;

-- H2 gives inline UNIQUE constraints generated names. Recreate the table to
-- remove the global holiday_date unique constraint while preserving data.
CREATE TABLE IF NOT EXISTS scheduling_holidays_v3 (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_group_id BIGINT NULL,
    holiday_date    DATE         NOT NULL,
    name            VARCHAR(255) NOT NULL,
    recurring       BOOLEAN      DEFAULT FALSE
);

INSERT INTO scheduling_holidays_v3 (id, tenant_group_id, holiday_date, name, recurring)
SELECT id, NULL, holiday_date, name, recurring
FROM scheduling_holidays;

DROP TABLE scheduling_holidays;

ALTER TABLE scheduling_holidays_v3 RENAME TO scheduling_holidays;
