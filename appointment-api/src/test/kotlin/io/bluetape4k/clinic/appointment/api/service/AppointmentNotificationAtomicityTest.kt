package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.notification.AppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.CommitmentAppointmentNotification
import io.bluetape4k.clinic.appointment.api.notification.DefaultAppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.event.notification.DefaultNotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationHmacKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRowKind
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.StaticNotificationOutboxKeyRing
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.service.AppointmentRescheduleNotificationWriter
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.clinic.appointment.service.SlotCalculationService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

internal class AppointmentNotificationAtomicityTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
    private val appointmentRepository = AppointmentRepository()
    private val idempotencyRepository = AppointmentIdempotencyRepository()
    private val stateHistoryRepository = AppointmentStateHistoryRepository()
    private val clinicRepository = ClinicRepository()
    private val rescheduleCandidateRepository = RescheduleCandidateRepository()
    private val outboxRepository = NotificationOutboxRepository(
        codec = NotificationOutboxCodec(),
        leaseDuration = Duration.ofMinutes(5),
    )
    private val actualWriter = DefaultAppointmentNotificationWriter(
        repository = outboxRepository,
        hasher = DefaultNotificationOutboxHasher(
            StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("atomicity-test", ByteArray(32) { 9 }),
                previous = null,
            )
        ),
        clinicRepository = clinicRepository,
        clock = clock,
        sameDayReminderLeadTime = Duration.ofHours(2),
    )
    private val publisher = object : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private var tenantGroupId = 0L
    private var clinicId = 0L
    private var doctorId = 0L
    private var treatmentTypeId = 0L

    @BeforeEach
    fun setUp() {
        transaction {
            NotificationDeliveryAttempts.deleteAll()
            NotificationOutboxEvents.deleteAll()
            AppointmentStateHistory.deleteAll()
            RescheduleCandidates.deleteAll()
            AppointmentIdempotencies.deleteAll()
            Appointments.deleteAll()
            TreatmentTypes.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "atomicity-test"
                it[displayName] = "Atomicity Test"
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = this@AppointmentNotificationAtomicityTest.tenantGroupId
                it[name] = "원자성 진료소"
                it[timezone] = "Asia/Seoul"
            }.value
            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@AppointmentNotificationAtomicityTest.clinicId
                it[name] = "담당 의사"
            }.value
            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@AppointmentNotificationAtomicityTest.clinicId
                it[name] = "진료"
                it[defaultDurationMinutes] = 30
            }.value
        }
    }

    @Test
    fun `생성과 CREATED outbox 및 idempotency는 함께 commit된다`() {
        val result = service(actualWriter).create(
            tenantGroupId = tenantGroupId,
            request = request(),
            idempotencyKey = "create-1",
            resolution = MemberResolution.Resolved(MemberId("member-1")),
        )

        result.replayed shouldBeEqualTo false
        transaction {
            Appointments.selectAll().count() shouldBeEqualTo 1L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 1L
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
            NotificationOutboxEvents.selectAll().single()[NotificationOutboxEvents.notificationSlot] shouldBeEqualTo
                NotificationSlot.CREATED
        }
    }

    @Test
    fun `outbox 실패는 appointment와 idempotency를 함께 rollback한다`() {
        assertFailsWith<IllegalStateException> {
            service(throwingWriter()).create(
                tenantGroupId = tenantGroupId,
                request = request(),
                idempotencyKey = "create-rollback",
                resolution = MemberResolution.Resolved(MemberId("member-1")),
            )
        }

        transaction {
            Appointments.selectAll().count() shouldBeEqualTo 0L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 0L
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `OBSERVE 회원 누락은 appointment와 legacy suppression을 함께 commit한다`() {
        service(actualWriter).create(
            tenantGroupId = tenantGroupId,
            request = request(memberId = null),
            idempotencyKey = "create-observe",
            resolution = MemberResolution.LegacyMissing,
        )

        transaction {
            Appointments.selectAll().count() shouldBeEqualTo 1L
            val outbox = NotificationOutboxEvents.selectAll().single()
            outbox[NotificationOutboxEvents.rowKind] shouldBeEqualTo NotificationOutboxRowKind.LEGACY_SUPPRESSION
            outbox[NotificationOutboxEvents.appointmentId] shouldBeEqualTo null
            outbox[NotificationOutboxEvents.memberId] shouldBeEqualTo null
        }
    }

    @Test
    fun `확정 outbox 실패는 상태 version과 이력을 rollback한다`() {
        runBlocking {
            val appointmentId = saveRequestedAppointment()

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    service(throwingWriter()).updateStatus(
                        id = appointmentId,
                        tenantGroupId = tenantGroupId,
                        targetStatus = "CONFIRMED",
                        reason = null,
                    )
                }
            }

            transaction {
                val appointment = appointmentRepository.findByIdOrNull(appointmentId)!!
                appointment.status shouldBeEqualTo AppointmentState.REQUESTED
                appointment.version shouldBeEqualTo 0L
                AppointmentStateHistory.selectAll().count() shouldBeEqualTo 0L
            }
        }
    }

    @Test
    fun `확정은 상태 이력과 확정 및 두 리마인더를 같은 transaction에 기록한다`() {
        runBlocking {
            val appointmentId = saveRequestedAppointment()

            val updated = service(actualWriter).updateStatus(
                id = appointmentId,
                tenantGroupId = tenantGroupId,
                targetStatus = "CONFIRMED",
                reason = null,
            )

            updated.status shouldBeEqualTo AppointmentState.CONFIRMED
            updated.version shouldBeEqualTo 1L
            transaction {
                AppointmentStateHistory.selectAll().count() shouldBeEqualTo 1L
                NotificationOutboxEvents.selectAll()
                    .map { it[NotificationOutboxEvents.notificationSlot] }
                    .toSet() shouldBeEqualTo
                    setOf(
                        NotificationSlot.CONFIRMED,
                        NotificationSlot.REMINDER_24H,
                        NotificationSlot.REMINDER_SAME_DAY,
                    )
            }
        }
    }

    @Test
    fun `취소는 상태 변경과 이전 리마인더 억제 및 취소 알림을 함께 기록한다`() {
        runBlocking {
            val appointmentId = saveRequestedAppointment()
            val service = service(actualWriter)
            service.updateStatus(appointmentId, tenantGroupId, "CONFIRMED", null)

            val cancelled = service.cancel(
                id = appointmentId,
                tenantGroupId = tenantGroupId,
                reason = "CUSTOMER_REQUEST",
            )

            cancelled.status shouldBeEqualTo AppointmentState.CANCELLED
            cancelled.version shouldBeEqualTo 2L
            transaction {
                val rows = NotificationOutboxEvents.selectAll().toList()
                rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.CANCELLED }
                    .get(NotificationOutboxEvents.status) shouldBeEqualTo NotificationOutboxStatus.PENDING
                val reminders = rows.filter {
                    it[NotificationOutboxEvents.notificationSlot] in
                        setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
                }
                reminders.size shouldBeEqualTo 2
                reminders.forEach {
                    it[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
                    it[NotificationOutboxEvents.suppressionReason] shouldBeEqualTo
                        NotificationSuppressionReasonCode.APPOINTMENT_CHANGED
                    it[NotificationOutboxEvents.appointmentId] shouldBeEqualTo null
                    it[NotificationOutboxEvents.memberId] shouldBeEqualTo null
                }
            }
        }
    }

    @Test
    fun `취소 outbox 실패는 상태와 이전 리마인더를 rollback한다`() {
        val appointmentId = runBlocking {
            saveRequestedAppointment().also {
                service(actualWriter).updateStatus(it, tenantGroupId, "CONFIRMED", null)
            }
        }
        val failing = object : AppointmentNotificationWriter by actualWriter {
            override fun cancelled(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                reasonCode: CancellationReasonCode?,
            ) = error("forced cancellation outbox failure")
        }

        assertFailsWith<IllegalStateException> {
            runBlocking {
                service(failing).cancel(appointmentId, tenantGroupId, "CUSTOMER_REQUEST")
            }
        }

        transaction {
            val record = appointmentRepository.findByIdOrNull(appointmentId)!!
            record.status shouldBeEqualTo AppointmentState.CONFIRMED
            record.version shouldBeEqualTo 1L
            NotificationOutboxEvents.selectAll()
                .filter {
                    it[NotificationOutboxEvents.notificationSlot] in
                        setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
                }
                .forEach {
                    it[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.PENDING
                    it[NotificationOutboxEvents.appointmentId] shouldBeEqualTo appointmentId
                }
        }
    }

    @Test
    fun `재예약은 원본 상태와 이전 리마인더 및 새 일정 알림을 함께 기록한다`() {
        val originalId = confirmedPendingRescheduleAppointment()
        val candidateId = saveRescheduleCandidate(originalId)

        val replacementId = closureService(actualWriter).confirmReschedule(
            candidateId = candidateId,
            originalAppointmentId = originalId,
            tenantGroupId = tenantGroupId,
        )

        transaction {
            val original = appointmentRepository.findByIdOrNull(originalId)!!
            val replacement = appointmentRepository.findByIdOrNull(replacementId)!!
            original.status shouldBeEqualTo AppointmentState.RESCHEDULED
            original.version shouldBeEqualTo 3L
            replacement.status shouldBeEqualTo AppointmentState.CONFIRMED
            replacement.version shouldBeEqualTo 0L
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.count {
                it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.RESCHEDULED &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.PENDING
            } shouldBeEqualTo 1
            rows.count {
                it[NotificationOutboxEvents.notificationSlot] in
                    setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY) &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.SUPPRESSED
            } shouldBeEqualTo 2
            rows.count {
                it[NotificationOutboxEvents.notificationSlot] in
                    setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY) &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.PENDING
            } shouldBeEqualTo 2
        }
    }

    @Test
    fun `재예약 outbox 실패는 원본과 대체 예약 및 candidate 선택을 rollback한다`() {
        val originalId = confirmedPendingRescheduleAppointment()
        val candidateId = saveRescheduleCandidate(originalId)
        val failing = object : AppointmentNotificationWriter by actualWriter {
            override fun rescheduled(
                tenantGroupId: Long,
                original: AppointmentRecord,
                replacement: AppointmentRecord,
                version: Long,
            ) = error("forced reschedule outbox failure")
        }

        assertFailsWith<IllegalStateException> {
            closureService(failing).confirmReschedule(
                candidateId = candidateId,
                originalAppointmentId = originalId,
                tenantGroupId = tenantGroupId,
            )
        }

        transaction {
            val original = appointmentRepository.findByIdOrNull(originalId)!!
            original.status shouldBeEqualTo AppointmentState.PENDING_RESCHEDULE
            original.version shouldBeEqualTo 2L
            Appointments.selectAll().count() shouldBeEqualTo 1L
            RescheduleCandidates.selectAll().single()[RescheduleCandidates.selected] shouldBeEqualTo false
            NotificationOutboxEvents.selectAll()
                .filter {
                    it[NotificationOutboxEvents.notificationSlot] in
                        setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
                }
                .forEach {
                    it[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.PENDING
                }
        }
    }

    private fun service(writer: AppointmentNotificationWriter): AppointmentService =
        AppointmentService(
            appointmentRepository = appointmentRepository,
            stateMachine = AppointmentStateMachine(),
            eventPublisher = publisher,
            stateHistoryRepository = stateHistoryRepository,
            idempotencyRepository = idempotencyRepository,
            idempotencyProperties = AppointmentIdempotencyProperties(),
            idempotencyClock = clock,
            clinicRepository = clinicRepository,
            notificationWriter = writer,
        )

    private fun throwingWriter(): AppointmentNotificationWriter =
        object : AppointmentNotificationWriter {
            override fun appointmentCreated(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                resolution: MemberResolution,
            ) = error("forced outbox failure")

            override fun statusChanged(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                from: AppointmentState,
                to: AppointmentState,
            ) = error("forced outbox failure")

            override fun cancelled(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                reasonCode: CancellationReasonCode?,
            ) = error("forced outbox failure")

            override fun rescheduled(
                tenantGroupId: Long,
                original: AppointmentRecord,
                replacement: AppointmentRecord,
                version: Long,
            ) = error("forced outbox failure")

            override fun commitmentRequested(notification: CommitmentAppointmentNotification) =
                error("forced outbox failure")

            override fun commitmentConfirmed(notification: CommitmentAppointmentNotification) =
                error("forced outbox failure")

            override fun commitmentCancelled(
                notification: CommitmentAppointmentNotification,
                reasonCode: CancellationReasonCode?,
            ) = error("forced outbox failure")

            override fun commitmentRescheduled(
                previous: CommitmentAppointmentNotification,
                replacement: CommitmentAppointmentNotification,
            ) = error("forced outbox failure")
        }

    private fun closureService(writer: AppointmentNotificationWriter): ClosureRescheduleService =
        ClosureRescheduleService(
            slotCalculationService = SlotCalculationService(),
            appointmentRepository = appointmentRepository,
            rescheduleCandidateRepository = rescheduleCandidateRepository,
            stateHistoryRepository = stateHistoryRepository,
            doctorRepository = DoctorRepository(),
            notificationWriter = AppointmentRescheduleNotificationWriter { tenant, original, replacement, version ->
                writer.rescheduled(tenant, original, replacement, version)
            },
        )

    private fun confirmedPendingRescheduleAppointment(): Long {
        val appointmentId = runBlocking {
            saveRequestedAppointment().also {
                service(actualWriter).updateStatus(it, tenantGroupId, "CONFIRMED", null)
            }
        }
        transaction {
            check(
                appointmentRepository.updateLegacyStatus(
                    appointmentId = appointmentId,
                    expectedVersion = 1L,
                    newStatus = AppointmentState.PENDING_RESCHEDULE,
                )
            )
        }
        return appointmentId
    }

    private fun saveRescheduleCandidate(originalAppointmentId: Long): Long =
        transaction {
            rescheduleCandidateRepository.save(
                RescheduleCandidateRecord(
                    originalAppointmentId = originalAppointmentId,
                    candidateDate = LocalDate.of(2026, 8, 4),
                    startTime = LocalTime.of(11, 0),
                    endTime = LocalTime.of(11, 30),
                    doctorId = doctorId,
                    priority = 1,
                )
            ).id!!
        }

    private fun saveRequestedAppointment(): Long =
        transaction {
            appointmentRepository.save(
                AppointmentRecord(
                    clinicId = clinicId,
                    doctorId = doctorId,
                    treatmentTypeId = treatmentTypeId,
                    memberId = MemberId("member-1"),
                    patientName = "환자",
                    patientPhone = "010-0000-0000",
                    appointmentDate = LocalDate.of(2026, 8, 3),
                    startTime = LocalTime.of(10, 0),
                    endTime = LocalTime.of(10, 30),
                    status = AppointmentState.REQUESTED,
                )
            ).id!!
        }

    private fun request(memberId: String? = "member-1"): CreateAppointmentRequest =
        CreateAppointmentRequest(
            clinicId = clinicId,
            doctorId = doctorId,
            treatmentTypeId = treatmentTypeId,
            memberId = memberId,
            patientName = "환자",
            patientPhone = "010-0000-0000",
            appointmentDate = LocalDate.of(2026, 8, 3),
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(10, 30),
        )

    companion object {
        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            Database.connect(
                url = "jdbc:h2:mem:notification-atomicity-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver",
            )
            transaction {
                SchemaUtils.createMissingTablesAndColumns(
                    TenantGroups,
                    Clinics,
                    Doctors,
                    TreatmentTypes,
                    Equipments,
                    ConsultationTopics,
                    Appointments,
                    AppointmentIdempotencies,
                    AppointmentStateHistory,
                    RescheduleCandidates,
                    NotificationOutboxEvents,
                    NotificationDeliveryAttempts,
                )
            }
        }
    }
}
