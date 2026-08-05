package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventLogTest {

    companion object: KLogging()

    private lateinit var logger: AppointmentEventLogger

    private fun scope(clinicId: Long, tenantGroupId: Long = 1L) = TenantClinicScope(tenantGroupId, clinicId)

    @BeforeEach
    fun setUp() {
        Database.connect("jdbc:h2:mem:test_event_log;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            // SchemaUtils.createMissingTablesAndColumns(AppointmentEventLogs)
            SchemaUtils.create(AppointmentEventLogs)
            AppointmentEventLogs.deleteAll()
        }
        logger = AppointmentEventLogger()
    }

    @Test
    fun `Created 이벤트가 DB에 저장된다`() {
        val event = AppointmentDomainEvent.Created(appointmentId = 1L, scope = scope(10L))

        logger.onCreated(event)

        transaction {
            val rows = AppointmentEventLogs.selectAll().toList()
            rows shouldHaveSize 1
            val row = rows.first()
            row[AppointmentEventLogs.eventType] shouldBeEqualTo "Created"
            row[AppointmentEventLogs.entityType] shouldBeEqualTo "Appointment"
            row[AppointmentEventLogs.entityId] shouldBeEqualTo 1L
            row[AppointmentEventLogs.tenantGroupId] shouldBeEqualTo 1L
            row[AppointmentEventLogs.clinicId] shouldBeEqualTo 10L
            row[AppointmentEventLogs.payloadJson].contains("appointmentId").shouldBeFalse()
        }
    }

    @Test
    fun `StatusChanged 이벤트가 DB에 저장된다`() {
        val event = AppointmentDomainEvent.StatusChanged(
            appointmentId = 2L,
            scope = scope(20L),
            fromState = "REQUESTED",
            toState = "CONFIRMED",
            reason = "DOCTOR_APPROVED"
        )

        logger.onStatusChanged(event)

        transaction {
            val rows = AppointmentEventLogs.selectAll().toList()
            rows shouldHaveSize 1
            val row = rows.first()
            row[AppointmentEventLogs.eventType] shouldBeEqualTo "StatusChanged"
            row[AppointmentEventLogs.entityId] shouldBeEqualTo 2L
            row[AppointmentEventLogs.tenantGroupId] shouldBeEqualTo 1L
            row[AppointmentEventLogs.clinicId] shouldBeEqualTo 20L
            val payload = row[AppointmentEventLogs.payloadJson]
            payload.contains("\"fromState\":\"REQUESTED\"").shouldBeTrue()
            payload.contains("\"toState\":\"CONFIRMED\"").shouldBeTrue()
            payload.contains("\"reasonCode\":\"DOCTOR_APPROVED\"").shouldBeTrue()
        }
    }

    @Test
    fun `StatusChanged 이벤트 reason이 null이면 payload에 포함되지 않는다`() {
        val event = AppointmentDomainEvent.StatusChanged(
                appointmentId = 3L,
                scope = scope(30L),
                fromState = "CONFIRMED",
                toState = "CHECKED_IN",
                reason = null
            )

        logger.onStatusChanged(event)

        transaction {
            val rows = AppointmentEventLogs.selectAll().toList()
            rows shouldHaveSize 1
            val payload = rows.first()[AppointmentEventLogs.payloadJson]
            payload.contains("reason").shouldBeFalse()
        }
    }

    @Test
    fun `Cancelled 이벤트는 등록된 reason code만 DB에 저장한다`() {
        val event = AppointmentDomainEvent.Cancelled(
                appointmentId = 4L,
                scope = scope(40L),
                reason = "PATIENT_REQUEST"
            )

        logger.onCancelled(event)

        transaction {
            val rows = AppointmentEventLogs.selectAll().toList()
            rows shouldHaveSize 1
            val row = rows.first()
            row[AppointmentEventLogs.eventType] shouldBeEqualTo "Cancelled"
            row[AppointmentEventLogs.entityId] shouldBeEqualTo 4L
            row[AppointmentEventLogs.clinicId] shouldBeEqualTo 40L
            row[AppointmentEventLogs.payloadJson].contains("\"reasonCode\":\"PATIENT_REQUEST\"").shouldBeTrue()
        }
    }

    @Test
    fun `등록되지 않은 이벤트 reason 문자열은 감사 payload에 저장되지 않는다`() {
        val event = AppointmentDomainEvent.Cancelled(
                appointmentId = 5L,
                scope = scope(50L),
                reason = """환자 "직접" 요청
다음주 재예약"""
            )

        logger.onCancelled(event)

        transaction {
            val payload = AppointmentEventLogs.selectAll().single()[AppointmentEventLogs.payloadJson]
            payload.contains("직접").shouldBeFalse()
            payload.contains("다음주").shouldBeFalse()
        }
    }

    @Test
    fun `Rescheduled 이벤트가 DB에 저장된다`() {
        val event = AppointmentDomainEvent.Rescheduled(originalId = 6L, newId = 7L, scope = scope(60L))

        logger.onRescheduled(event)

        transaction {
            val row = AppointmentEventLogs.selectAll().single()
            row[AppointmentEventLogs.eventType] shouldBeEqualTo "Rescheduled"
            row[AppointmentEventLogs.entityId] shouldBeEqualTo 6L
            row[AppointmentEventLogs.clinicId] shouldBeEqualTo 60L
            row[AppointmentEventLogs.payloadJson].contains("newId").shouldBeFalse()
        }
    }

    @Test
    fun `여러 이벤트가 순차적으로 저장된다`() {
        logger.onCreated(AppointmentDomainEvent.Created(appointmentId = 100L, scope = scope(1L)))
        logger.onStatusChanged(
            AppointmentDomainEvent.StatusChanged(
                appointmentId = 100L,
                scope = scope(1L),
                fromState = "REQUESTED",
                toState = "CONFIRMED"
            )
        )
        logger.onCancelled(
            AppointmentDomainEvent.Cancelled(
                appointmentId = 100L,
                scope = scope(1L),
                reason = "취소"
            )
        )
        logger.onRescheduled(AppointmentDomainEvent.Rescheduled(originalId = 100L, newId = 101L, scope = scope(1L)))

        transaction {
            val rows = AppointmentEventLogs.selectAll().toList()
            rows shouldHaveSize 4
            rows[0][AppointmentEventLogs.eventType] shouldBeEqualTo "Created"
            rows[1][AppointmentEventLogs.eventType] shouldBeEqualTo "StatusChanged"
            rows[2][AppointmentEventLogs.eventType] shouldBeEqualTo "Cancelled"
            rows[3][AppointmentEventLogs.eventType] shouldBeEqualTo "Rescheduled"
        }
    }

    @Test
    fun `감사 로그 저장 실패는 이미 commit된 event 호출자에게 전파하지 않는다`() {
        transaction { SchemaUtils.drop(AppointmentEventLogs) }

        val metrics = RecordingAuditMetrics()
        logger = AppointmentEventLogger(metrics)
        logger.onCreated(AppointmentDomainEvent.Created(appointmentId = 200L, scope = scope(2L)))

        metrics.failures shouldBeEqualTo 1
        metrics.reasonCodes shouldBeEqualTo listOf("EVENT_LOG_WRITE_FAILED")
    }

    private class RecordingAuditMetrics : AppointmentEventAuditMetrics {
        var failures: Int = 0
        val reasonCodes = mutableListOf<String>()

        override fun recordEventLogWriteFailure(reasonCode: String) {
            failures += 1
            reasonCodes += reasonCode
        }
    }
}
