package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityRestrictionMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 예약 신뢰성 기능의 배포 단계를 정의합니다.
 *
 * `OFF`는 새 예약 흐름에 결정을 적용하지 않고, `SHADOW`는 결정을 저장·관찰하되
 * 예약을 막지 않으며, `ENFORCE`만 새 `PROPOSED`·`HELD`·신규 `CONFIRMED` 경로에
 * 제한을 적용합니다. 이미 확정된 예약을 변경하는 옵션은 존재하지 않습니다.
 */
@ConfigurationProperties(prefix = "booking.reliability")
data class BookingReliabilityProperties(
    val mode: Mode = Mode.OFF,
    val restrictionMode: RestrictionMode = RestrictionMode.EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS,
    val decisionTtl: Duration = Duration.ofMinutes(5),
    val maxHistoryRows: Int = 100,
    val maxTriggerIds: Int = 32,
    val maxAuditPageSize: Int = 100,
    val workerEnabled: Boolean = false,
    val clinicAllowList: Set<Long> = emptySet(),
) {
    init {
        require(!decisionTtl.isZero && !decisionTtl.isNegative) {
            "decisionTtl must be positive"
        }
        require(decisionTtl <= MAX_DECISION_TTL) {
            "decisionTtl must be no greater than $MAX_DECISION_TTL"
        }
        require(maxHistoryRows in 1..MAX_HISTORY_ROWS) {
            "maxHistoryRows must be in 1..$MAX_HISTORY_ROWS"
        }
        require(maxTriggerIds in 1..MAX_TRIGGER_IDS) {
            "maxTriggerIds must be in 1..$MAX_TRIGGER_IDS"
        }
        require(maxAuditPageSize in 1..MAX_AUDIT_PAGE_SIZE) {
            "maxAuditPageSize must be in 1..$MAX_AUDIT_PAGE_SIZE"
        }
        require(clinicAllowList.all { it > 0 }) { "clinicAllowList must contain positive ids" }
    }

    enum class Mode {
        OFF,
        SHADOW,
        ENFORCE,
    }

    /** threshold 충족 시 신규 자동 제안에 적용할 병원별 제한 방식입니다. */
    enum class RestrictionMode {
        EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS,
        REQUIRE_STAFF_APPROVAL,
        ;

        fun toDomain(): BookingReliabilityRestrictionMode =
            when (this) {
                EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS ->
                    BookingReliabilityRestrictionMode.EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS
                REQUIRE_STAFF_APPROVAL ->
                    BookingReliabilityRestrictionMode.REQUIRE_STAFF_APPROVAL
            }
    }

    private companion object {
        val MAX_DECISION_TTL: Duration = Duration.ofHours(24)
        const val MAX_HISTORY_ROWS = 100
        const val MAX_TRIGGER_IDS = 32
        const val MAX_AUDIT_PAGE_SIZE = 100
    }
}
