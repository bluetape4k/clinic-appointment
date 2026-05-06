package io.bluetape4k.clinic.appointment.timezone

import io.bluetape4k.logging.KLogging
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 클리닉별 타임존 변환 서비스.
 *
 * ## 타임존 설계 원칙
 *
 * `appointment_date` / `start_time` / `end_time` 은 **클리닉 현지 시간** 그대로 DB에 저장됩니다.
 * UTC로 변환하지 않는 이유:
 * - 예약 시간은 본질적으로 "현지 이벤트" — "서울 클리닉 오전 9시" 는 항상 서울 시간
 * - UTC 변환 시 날짜 경계(예: 23:00 KST → 14:00 UTC 다음 날)로 인해 날짜 기반 쿼리가 깨짐
 * - 슬롯 계산/영업시간 비교가 동일 timezone 내에서 단순하게 유지됨
 *
 * `created_at` / `updated_at` 은 UTC [java.time.Instant] 로 저장됩니다 (시스템 감사 목적).
 *
 * ## 다국가 SaaS 지원
 *
 * [Clinics.timezone] 컬럼이 각 클리닉의 ZoneId를 보유합니다.
 * API 응답에 `timezone` 필드를 포함시켜 프론트엔드가 `LocalDate + LocalTime + timezone` 으로
 * `ZonedDateTime` 을 복원할 수 있게 합니다.
 *
 * ```
 * Frontend (LocalDate + LocalTime)
 *     ↓  변환 없이 저장
 * DB  (LocalDate + LocalTime — 클리닉 현지 기준)
 *     ↓  응답 시 Clinics.timezone 포함
 * Frontend (ZonedDateTime 복원 가능)
 * ```
 *
 * @param clinicRepository 병원 Repository
 */
class ClinicTimezoneService(
    private val clinicRepository: ClinicRepository,
) {
    companion object : KLogging()

    /**
     * 클리닉 timezone으로 변환된 [ZonedDateTime]을 반환합니다.
     */
    fun toClinicTime(clinicId: Long, date: LocalDate, time: LocalTime): ZonedDateTime {
        val clinic = getClinic(clinicId)
        val zoneId = ZoneId.of(clinic.timezone)
        return ZonedDateTime.of(date, time, zoneId)
    }

    /**
     * UTC 기준 LocalDateTime을 클리닉 timezone으로 변환합니다.
     */
    fun utcToClinicLocal(clinicId: Long, utcDateTime: LocalDateTime): LocalDateTime {
        val clinic = getClinic(clinicId)
        val utcZoned = utcDateTime.atZone(ZoneId.of("UTC"))
        val clinicZoned = utcZoned.withZoneSameInstant(ZoneId.of(clinic.timezone))
        return clinicZoned.toLocalDateTime()
    }

    /**
     * 클리닉 로컬 시간을 UTC로 변환합니다.
     */
    fun clinicLocalToUtc(clinicId: Long, localDateTime: LocalDateTime): LocalDateTime {
        val clinic = getClinic(clinicId)
        val clinicZoned = localDateTime.atZone(ZoneId.of(clinic.timezone))
        val utcZoned = clinicZoned.withZoneSameInstant(ZoneId.of("UTC"))
        return utcZoned.toLocalDateTime()
    }

    /**
     * 클리닉 locale에 맞는 날짜 포맷 문자열을 반환합니다.
     */
    fun formatDate(clinicId: Long, date: LocalDate): String {
        val clinic = getClinic(clinicId)
        val locale = Locale.forLanguageTag(clinic.locale)
        val formatter = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
        return date.format(formatter)
    }

    /**
     * 클리닉 locale에 맞는 시간 포맷 문자열을 반환합니다.
     */
    fun formatTime(clinicId: Long, time: LocalTime): String {
        val clinic = getClinic(clinicId)
        val locale = Locale.forLanguageTag(clinic.locale)
        val formatter = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
        return time.format(formatter)
    }

    /**
     * 클리닉의 현재 로컬 시간을 반환합니다.
     */
    fun nowAtClinic(clinicId: Long): ZonedDateTime {
        val clinic = getClinic(clinicId)
        return ZonedDateTime.now(ZoneId.of(clinic.timezone))
    }

    /**
     * 클리닉의 `timezone` 과 `locale` 을 한 번의 DB 조회로 반환합니다.
     *
     * API 응답에 두 값을 모두 포함할 때 N+1 조회를 방지합니다.
     *
     * @return `timezone` (예: "Asia/Seoul") to `locale` (예: "ko-KR") Pair
     */
    fun getTimezoneAndLocale(clinicId: Long): Pair<String, String> {
        val clinic = getClinic(clinicId)
        return clinic.timezone to clinic.locale
    }

    /**
     * 클리닉의 [ZoneId]를 반환합니다.
     */
    fun getZoneId(clinicId: Long): ZoneId {
        val clinic = getClinic(clinicId)
        return ZoneId.of(clinic.timezone)
    }

    /**
     * 클리닉의 [Locale]을 반환합니다.
     */
    fun getLocale(clinicId: Long): Locale {
        val clinic = getClinic(clinicId)
        return Locale.forLanguageTag(clinic.locale)
    }

    private fun getClinic(clinicId: Long): ClinicRecord =
        transaction { clinicRepository.findByIdOrNull(clinicId) }
            ?: throw NoSuchElementException("Clinic not found: $clinicId")
}
