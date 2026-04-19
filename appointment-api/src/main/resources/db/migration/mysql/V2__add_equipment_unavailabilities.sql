-- ============================================================
-- V2: 장비 사용불가 스케줄 추가 (MySQL 8)
-- ============================================================

CREATE TABLE scheduling_equipment_unavailabilities (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id          BIGINT       NOT NULL,
    clinic_id             BIGINT       NOT NULL,
    unavailable_date      DATE,
    is_recurring          BOOLEAN      NOT NULL DEFAULT FALSE,
    recurring_day_of_week VARCHAR(10),
    effective_from        DATE         NOT NULL,
    effective_until       DATE,
    start_time            TIME         NOT NULL,
    end_time              TIME         NOT NULL,
    reason                VARCHAR(500),
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_equ_unavail_equipment FOREIGN KEY (equipment_id) REFERENCES scheduling_equipments(id) ON DELETE CASCADE,
    CONSTRAINT fk_equ_unavail_clinic    FOREIGN KEY (clinic_id)    REFERENCES scheduling_clinics(id)    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_equ_unavail_equipment_from ON scheduling_equipment_unavailabilities (equipment_id, effective_from);
CREATE INDEX idx_equ_unavail_clinic_from    ON scheduling_equipment_unavailabilities (clinic_id, effective_from);
CREATE INDEX idx_equ_unavail_equipment_dow  ON scheduling_equipment_unavailabilities (equipment_id, recurring_day_of_week);

CREATE TABLE scheduling_equipment_unavailability_exceptions (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    unavailability_id       BIGINT       NOT NULL,
    original_date           DATE         NOT NULL,
    exception_type          VARCHAR(20)  NOT NULL,
    rescheduled_date        DATE,
    rescheduled_start_time  TIME,
    rescheduled_end_time    TIME,
    reason                  VARCHAR(500),
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_equ_unavail_exc_parent FOREIGN KEY (unavailability_id)
        REFERENCES scheduling_equipment_unavailabilities(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_equ_unavail_exc_parent_date
    ON scheduling_equipment_unavailability_exceptions (unavailability_id, original_date);
