package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepository
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Duration
import kotlin.math.ceil

/** H2의 20,000행 backlog에서 claim·recovery·retention page 상한을 검증합니다. */
class NotificationOutboxLoadIntegrationTest {

    @Test
    fun `대규모 활성 종료 backlog도 bounded page와 direct single claim을 유지한다`() {
        val dataSource = SimpleDriverDataSource(
            Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as java.sql.Driver,
            "jdbc:h2:mem:notification_load_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        NotificationOutboxPerformanceTestSupport.migrate(dataSource, "classpath:db/migration/h2")
        dataSource.connection.use(NotificationOutboxPerformanceTestSupport::seedBacklog)
        val database = Database.connect(dataSource)
        val repository = JdbcNotificationOutboxRepository(NotificationOutboxCodec(), Duration.ofMinutes(1))

        transaction(database) {
            val durations = buildList {
                repeat(30) {
                    val started = System.nanoTime()
                    repository.findReadyClinicKeys(cursor = null, limit = PAGE_SIZE).size
                        .let { it <= PAGE_SIZE }
                        .shouldBeTrue()
                    add((System.nanoTime() - started) / 1_000_000)
                }
            }
            (percentile(durations, 0.95) <= CLAIM_P95_MILLIS) shouldBeEqualTo true

            val clinics = repository.findReadyClinicKeys(cursor = null, limit = PAGE_SIZE)
            clinics.isNotEmpty().shouldBeTrue()
            (clinics.size <= PAGE_SIZE).shouldBeTrue()
            repository.findReadyCandidates(clinics.first(), cursorId = null, limit = PAGE_SIZE).size
                .let { it <= PAGE_SIZE }
                .shouldBeTrue()

            val suppressionDurations = buildList {
                repeat(30) {
                    val started = System.nanoTime()
                    repository.suppressOutstandingReminders(
                        tenantGroupId = TenantGroupId(1L),
                        clinicId = ClinicId(NotificationOutboxPerformanceTestSupport.TARGET_CLINIC_ID),
                        appointmentId = AppointmentId(NotificationOutboxPerformanceTestSupport.TARGET_APPOINTMENT_ID),
                        suppressionReason = NotificationSuppressionReasonCode.APPOINTMENT_CHANGED,
                    ) shouldBeEqualTo 0
                    add((System.nanoTime() - started) / 1_000_000)
                }
            }
            (percentile(suppressionDurations, 0.95) <= CLAIM_P95_MILLIS) shouldBeEqualTo true

            val claimed = repository.claimReadyForDirect(
                scope = TenantClinicScope(1L, NotificationOutboxPerformanceTestSupport.TARGET_CLINIC_ID),
                appointmentId = AppointmentId(NotificationOutboxPerformanceTestSupport.TARGET_APPOINTMENT_ID),
                eventType = NotificationEventType.CONFIRMED,
                owner = "load-direct",
                token = "load-direct-token-1",
            )
            claimed?.id shouldBeEqualTo NotificationOutboxPerformanceTestSupport.TARGET_ACTIVE_ID
            repository.claimReadyForDirect(
                scope = TenantClinicScope(1L, NotificationOutboxPerformanceTestSupport.TARGET_CLINIC_ID),
                appointmentId = AppointmentId(NotificationOutboxPerformanceTestSupport.TARGET_APPOINTMENT_ID),
                eventType = NotificationEventType.CONFIRMED,
                owner = "load-direct",
                token = "load-direct-token-2",
            ).shouldBeNull()

            repository.findExpiredProcessingIds(PAGE_SIZE).size shouldBeEqualTo PAGE_SIZE
            repository.deleteTerminalBatch(
                status = NotificationOutboxStatus.SENT,
                retention = Duration.ofDays(1),
                limit = PAGE_SIZE,
            ) shouldBeEqualTo PAGE_SIZE
        }
    }

    private fun percentile(values: List<Long>, percentile: Double): Long {
        val sorted = values.sorted()
        return sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val CLAIM_P95_MILLIS = 250L
    }
}
