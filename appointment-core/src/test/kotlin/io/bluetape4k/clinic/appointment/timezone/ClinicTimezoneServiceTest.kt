package io.bluetape4k.clinic.appointment.timezone

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

/**
 * [ClinicTimezoneService] 테스트.
 */
class ClinicTimezoneServiceTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val clinicRepository = ClinicRepository()
        private val service = ClinicTimezoneService(clinicRepository)

        private val allTables = arrayOf(Clinics)
    }

    private fun JdbcTransaction.setupClinics(): Triple<Long, Long, Long> {
        val seoulId = Clinics.insertAndGetId {
            it[name] = "서울 클리닉"
            it[slotDurationMinutes] = 30
            it[timezone] = "Asia/Seoul"
            it[locale] = "ko-KR"
        }.value

        val nyId = Clinics.insertAndGetId {
            it[name] = "NY Clinic"
            it[slotDurationMinutes] = 30
            it[timezone] = "America/New_York"
            it[locale] = "en-US"
        }.value

        val tokyoId = Clinics.insertAndGetId {
            it[name] = "東京クリニック"
            it[slotDurationMinutes] = 30
            it[timezone] = "Asia/Tokyo"
            it[locale] = "ja-JP"
        }.value

        return Triple(seoulId, nyId, tokyoId)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `toClinicTime - 서울 타임존 변환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, _, _) = setupClinics()

            val result = service.toClinicTime(seoulId, LocalDate.of(2026, 3, 21), LocalTime.of(9, 0))

            result.shouldNotBeNull()
            result.zone.shouldBeEqualTo(ZoneId.of("Asia/Seoul"))
            result.hour.shouldBeEqualTo(9)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `utcToClinicLocal - UTC를 서울 시간으로 변환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, _, _) = setupClinics()

            // UTC 00:00 → 서울 09:00 (KST = UTC+9)
            val utcTime = LocalDateTime.of(2026, 3, 21, 0, 0)
            val localTime = service.utcToClinicLocal(seoulId, utcTime)

            localTime.hour.shouldBeEqualTo(9)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `utcToClinicLocal - UTC를 뉴욕 시간으로 변환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (_, nyId, _) = setupClinics()

            // UTC 12:00 → NY 08:00 (EDT = UTC-4, 3월은 서머타임)
            val utcTime = LocalDateTime.of(2026, 3, 21, 12, 0)
            val localTime = service.utcToClinicLocal(nyId, utcTime)

            localTime.hour.shouldBeEqualTo(8)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `clinicLocalToUtc - 서울 시간을 UTC로 변환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, _, _) = setupClinics()

            // 서울 09:00 → UTC 00:00
            val seoulTime = LocalDateTime.of(2026, 3, 21, 9, 0)
            val utcTime = service.clinicLocalToUtc(seoulId, seoulTime)

            utcTime.hour.shouldBeEqualTo(0)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getZoneId - 클리닉별 ZoneId 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, nyId, tokyoId) = setupClinics()

            service.getZoneId(seoulId).shouldBeEqualTo(ZoneId.of("Asia/Seoul"))
            service.getZoneId(nyId).shouldBeEqualTo(ZoneId.of("America/New_York"))
            service.getZoneId(tokyoId).shouldBeEqualTo(ZoneId.of("Asia/Tokyo"))
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getLocale - 클리닉별 Locale 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, nyId, tokyoId) = setupClinics()

            service.getLocale(seoulId).shouldBeEqualTo(Locale.forLanguageTag("ko-KR"))
            service.getLocale(nyId).shouldBeEqualTo(Locale.forLanguageTag("en-US"))
            service.getLocale(tokyoId).shouldBeEqualTo(Locale.forLanguageTag("ja-JP"))
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `formatDate - locale별 날짜 포맷`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, nyId, tokyoId) = setupClinics()
            val date = LocalDate.of(2026, 3, 21)

            service.formatDate(seoulId, date).shouldNotBeNull()
            service.formatDate(nyId, date).shouldNotBeNull()
            service.formatDate(tokyoId, date).shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nowAtClinic - 클리닉 현재 시간`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, _, _) = setupClinics()

            val now = service.nowAtClinic(seoulId)

            now.zone.shouldBeEqualTo(ZoneId.of("Asia/Seoul"))
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getTimezoneAndLocale - timezone과 locale을 한 번의 DB 조회로 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (seoulId, _, _) = setupClinics()

            val (timezone, locale) = service.getTimezoneAndLocale(seoulId)

            timezone.shouldBeEqualTo("Asia/Seoul")
            locale.shouldBeEqualTo("ko-KR")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getTimezoneAndLocale - 클리닉별 각각 올바른 값 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (_, nyId, tokyoId) = setupClinics()

            val (nyTimezone, nyLocale) = service.getTimezoneAndLocale(nyId)
            nyTimezone.shouldBeEqualTo("America/New_York")
            nyLocale.shouldBeEqualTo("en-US")

            val (tokyoTimezone, tokyoLocale) = service.getTimezoneAndLocale(tokyoId)
            tokyoTimezone.shouldBeEqualTo("Asia/Tokyo")
            tokyoLocale.shouldBeEqualTo("ja-JP")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getTimezoneAndLocale - 교민 병원 - locale과 timezone이 독립적`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            setupClinics()

            // locale="ko-KR" 이지만 LA 소재 교민 병원
            val expatId = Clinics.insertAndGetId {
                it[name] = "LA 교민 클리닉"
                it[slotDurationMinutes] = 30
                it[timezone] = "America/Los_Angeles"
                it[locale] = "ko-KR"
            }.value

            val (timezone, locale) = service.getTimezoneAndLocale(expatId)

            timezone.shouldBeEqualTo("America/Los_Angeles")
            locale.shouldBeEqualTo("ko-KR")
        }
    }
}
