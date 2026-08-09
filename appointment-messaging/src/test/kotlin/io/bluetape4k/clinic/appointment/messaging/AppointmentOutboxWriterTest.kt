package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentOutboxWriterTest {
    private val scope = TenantClinicScope(1, 31)
    private val repository = AppointmentRepository()
    private val appointmentId = 924L

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:appointment_outbox_writer_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Equipments,
                ConsultationTopics,
                Appointments,
                ProductCatalogProjections,
                AppointmentPlans,
                AppointmentStateHistory,
                SchedulingOutboxEvents,
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
            }
            Clinics.insert {
                it[id] = EntityID(31L, Clinics)
                it[tenantGroupId] = 1L
                it[name] = "Clinic One"
            }
            Doctors.insert {
                it[id] = EntityID(41L, Doctors)
                it[clinicId] = 31L
                it[name] = "Doctor One"
            }
            TreatmentTypes.insert {
                it[id] = EntityID(51L, TreatmentTypes)
                it[clinicId] = 31L
                it[name] = "General"
                it[defaultDurationMinutes] = 30
            }
            Appointments.insert {
                it[id] = EntityID(appointmentId, Appointments)
                it[clinicId] = 31L
                it[doctorId] = 41L
                it[treatmentTypeId] = 51L
                it[patientName] = "Patient"
                it[appointmentDate] = java.time.LocalDate.of(2026, 8, 5)
                it[startTime] = java.time.LocalTime.of(10, 0)
                it[endTime] = java.time.LocalTime.of(10, 30)
                it[status] = AppointmentState.CONFIRMED
            }
        }
    }

    @Test
    fun `writer inserts created envelope in caller transaction`() {
        val writer = writer()
        transaction {
            val appointment = repository.findByIdAndScope(appointmentId, scope)!!
            writer.created(scope, appointment, AppointmentMessagingContext.from(AppointmentCommandContext.root("writer-1")))
        }

        transaction {
            val row = SchedulingOutboxEvents.selectAll().single()
            row[SchedulingOutboxEvents.eventType] shouldBeEqualTo "AppointmentCreated"
            row[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.PENDING
            row[SchedulingOutboxEvents.topic] shouldBeEqualTo DefaultAppointmentOutboxWriter.DEFAULT_TOPIC
            row[SchedulingOutboxEvents.partitionKey] shouldBeEqualTo
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"
        }
    }

    @Test
    fun `scope mismatch rolls back caller transaction`() {
        val writer = writer()
        assertFailsWith<IllegalArgumentException> {
            transaction {
                val appointment = repository.findByIdAndScope(appointmentId, scope)!!
                writer.created(
                    TenantClinicScope(2, 31),
                    appointment,
                    AppointmentMessagingContext.from(AppointmentCommandContext.root("writer-2")),
                )
            }
        }

        transaction { SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L }
    }

    @Test
    fun `status writer uses canonical row and latest history`() {
        val writer = writer()
        transaction {
            Appointments.update({ Appointments.id eq appointmentId }) {
                it[status] = AppointmentState.PENDING_RESCHEDULE
                it[version] = 1L
            }
            AppointmentStateHistory.insert {
                it[appointmentId] = this@AppointmentOutboxWriterTest.appointmentId
                it[fromState] = AppointmentState.CONFIRMED
                it[toState] = AppointmentState.PENDING_RESCHEDULE
                it[reason] = "closure"
            }
            val canonical = requireNotNull(repository.findByIdAndScope(appointmentId, scope))
            val capture = SqlStatementCapture()
            registerInterceptor(capture)
            writer.statusChanged(
                scope = scope,
                appointment = canonical.copy(version = 1L),
                fromState = AppointmentState.CONFIRMED,
                context = AppointmentMessagingContext.from(AppointmentCommandContext.httpRoot("client-41")),
            )
            capture.statements.size shouldBeLessOrEqualTo MAX_STATUS_WRITER_STATEMENTS
        }

        transaction {
            val row = SchedulingOutboxEvents.selectAll().single()
            row[SchedulingOutboxEvents.eventType] shouldBeEqualTo "AppointmentStatusChanged"
            row[SchedulingOutboxEvents.payloadJson].orEmpty().contains("\"version\":1").shouldBeEqualTo(true)
            row[SchedulingOutboxEvents.payloadJson].orEmpty().contains("\"fromState\":\"CONFIRMED\"").shouldBeEqualTo(true)
            row[SchedulingOutboxEvents.payloadJson].orEmpty().contains("\"toState\":\"PENDING_RESCHEDULE\"").shouldBeEqualTo(true)
            row[SchedulingOutboxEvents.correlationId] shouldBeEqualTo "client-41"
            row[SchedulingOutboxEvents.causationEventId].orEmpty().startsWith("http-command-").shouldBeEqualTo(true)
        }
    }

    private fun writer(): DefaultAppointmentOutboxWriter = DefaultAppointmentOutboxWriter(
        databaseClock = AppointmentDatabaseClock { Instant.parse("2026-08-05T08:30:00Z") },
        eventIdFactory = { AppointmentEventId("writer-event-1") },
    )

    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }

    private companion object {
        private const val MAX_STATUS_WRITER_STATEMENTS = 3
    }
}
