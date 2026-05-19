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

SET @holiday_date_index := (
    SELECT s.INDEX_NAME
    FROM INFORMATION_SCHEMA.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'scheduling_holidays'
      AND s.COLUMN_NAME = 'holiday_date'
      AND s.NON_UNIQUE = 0
      AND NOT EXISTS (
          SELECT 1
          FROM INFORMATION_SCHEMA.STATISTICS s2
          WHERE s2.TABLE_SCHEMA = s.TABLE_SCHEMA
            AND s2.TABLE_NAME = s.TABLE_NAME
            AND s2.INDEX_NAME = s.INDEX_NAME
            AND s2.SEQ_IN_INDEX > 1
      )
    ORDER BY IF(s.INDEX_NAME = 'holiday_date', 0, 1), s.INDEX_NAME
    LIMIT 1
);

SET @drop_holiday_date_index := IF(
    @holiday_date_index IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE scheduling_holidays DROP INDEX `', REPLACE(@holiday_date_index, '`', '``'), '`')
);

PREPARE drop_holiday_date_index_stmt FROM @drop_holiday_date_index;
EXECUTE drop_holiday_date_index_stmt;
DEALLOCATE PREPARE drop_holiday_date_index_stmt;
