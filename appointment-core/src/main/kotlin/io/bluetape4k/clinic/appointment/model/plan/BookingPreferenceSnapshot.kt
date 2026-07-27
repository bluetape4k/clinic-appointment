package io.bluetape4k.clinic.appointment.model.plan

import java.io.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 구매 서비스가 캡처한 불변 고객 예약 희망 정보입니다.
 *
 * 이 스냅샷은 예약 계획으로 그대로 복사됩니다. 가예약 제안을 만들기 위한 입력값일
 * 뿐이며, 확정 예약도 아니고 이후 확정 예약을 변경하는 데 대한 고객 동의도 아닙니다.
 * 어떤 variant도 환자 identity나 자유 입력 문구를 포함하지 않습니다.
 */
sealed interface BookingPreferenceSnapshot : Serializable {

    /**
     * 정확한 local date-time과 모호하지 않은 정규화 instant입니다.
     *
     * @property originalLocalDateTime 고객이 입력한 wall-clock 값입니다. 감사와 표시를
     * 위해 보존하지만, 시간대가 다른 값을 정렬하는 기준으로 단독 사용하면 안 됩니다.
     * @property originalOffset 입력 시 선택한 명시적 UTC offset입니다. daylight-saving
     * overlap 동안 발생하는 local time 모호성을 제거합니다.
     * @property zoneId 구매 이벤트를 수락할 때 local date-time과 offset을 검증한 IANA
     * time-zone 식별자입니다.
     * @property normalizedInstant 다른 세 속성에서 도출한 권위 있는 UTC instant입니다.
     * DST gap은 거부하고, overlap은 명시적 [originalOffset]을 요구합니다.
     */
    data class ExactDateTime(
        val originalLocalDateTime: LocalDateTime,
        val originalOffset: ZoneOffset,
        val zoneId: ZoneId,
        val normalizedInstant: Instant,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 포괄적인 선호 local-date 범위입니다.
     *
     * @property startDate 허용 가능한 첫 번째 병원 local calendar date입니다. 포괄
     * 경계입니다.
     * @property endDate 허용 가능한 마지막 병원 local calendar date입니다. 포괄 경계이며
     * [startDate]보다 앞설 수 없습니다.
     * @property zoneId 두 날짜를 해석할 때 사용하는 IANA 병원 time zone입니다.
     */
    data class DateRange(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val zoneId: ZoneId,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 선호 요일과 local time window 목록입니다.
     *
     * @property weekdays 허용 가능한 병원 local 요일의 중복 없는 목록입니다. 빈 목록은
     * 모든 요일이라는 뜻이 아니라 유효하지 않은 입력입니다.
     * @property localTimeWindows 끝이 배타적인, 서로 겹치지 않는 local wall-clock
     * window 목록입니다. 빈 목록은 제한 없음이 아니라 유효하지 않은 입력입니다.
     * @property zoneId 요일과 window를 실제 날짜에 투영할 때 사용하는 IANA 병원 time
     * zone입니다. DST 정규화는 날짜를 선택한 뒤에만 수행합니다.
     */
    data class PreferredWeekdaysAndWindows(
        val weekdays: List<DayOfWeek>,
        val localTimeWindows: List<LocalTimeWindow>,
        val zoneId: ZoneId,
    ) : BookingPreferenceSnapshot {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 구매에 고객 희망 일정이 없음을 나타내는 명시적 marker입니다.
     *
     * `null`과 같지 않습니다. 원본 서비스가 의도적으로 희망 일정을 제공하지 않았다는
     * 뜻이므로, 상품 fallback 규칙이 있으면 가예약 제안을 만들 수 있습니다.
     */
    data object NotProvided : BookingPreferenceSnapshot
}

/**
 * 끝 경계가 배타적인 local wall-clock window입니다.
 *
 * 같은 날짜 안의 window만 지원합니다. 자정을 넘는 가능 시간은 인접한 두 날짜의 두
 * window로 표현해 경계와 DST 처리를 명시적으로 유지합니다.
 *
 * @property start 포괄 local wall-clock 시작 시각입니다.
 * @property end 배타 local wall-clock 종료 시각입니다. [start]보다 뒤여야 합니다.
 */
data class LocalTimeWindow(
    val start: LocalTime,
    val end: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(start < end) { "start($start) must be before end($end)" }
    }
}
