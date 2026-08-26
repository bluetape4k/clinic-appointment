package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentCancelledParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentRescheduledParameters
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.event.notification.DefaultNotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationHmacKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepository
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxRowKind
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.StaticNotificationOutboxKeyRing
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

internal class AppointmentNotificationWriterTest {

    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val repository = JdbcNotificationOutboxRepository(
        codec = NotificationOutboxCodec(),
        leaseDuration = Duration.ofMinutes(5),
    )
    private val writer = DefaultAppointmentNotificationWriter(
        writer = repository,
        hasher = DefaultNotificationOutboxHasher(
            StaticNotificationOutboxKeyRing(
                active = NotificationHmacKey("active", ByteArray(32) { 1 }),
                previous = null,
            )
        ),
        clinicRepository = ClinicRepository(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        sameDayReminderLeadTime = Duration.ofHours(2),
    )

    private var tenantGroupId = 0L
    private var clinicId = 0L

    @BeforeEach
    fun setUp() {
        transaction {
            NotificationOutboxEvents.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "writer-test"
                it[displayName] = "Writer Test"
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = this@AppointmentNotificationWriterTest.tenantGroupId
                it[name] = "강남 진료소"
                it[timezone] = "Asia/Seoul"
            }.value
        }
    }

    @Test
    fun `회원이 확인된 생성 알림은 프로필 원문 없이 sendable row를 기록한다`() {
        transaction {
            writer.appointmentCreated(
                tenantGroupId = tenantGroupId,
                record = appointment(),
                version = 0L,
                resolution = MemberResolution.Resolved(MemberId("member-100")),
            )
        }

        transaction {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.rowKind] shouldBeEqualTo NotificationOutboxRowKind.SENDABLE
            row[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.PENDING
            row[NotificationOutboxEvents.notificationSlot] shouldBeEqualTo NotificationSlot.CREATED
            row[NotificationOutboxEvents.memberId] shouldBeEqualTo "member-100"
            requireNotNull(row[NotificationOutboxEvents.parametersJson]).contains("환자 이름") shouldBeEqualTo false
            requireNotNull(row[NotificationOutboxEvents.parametersJson]).contains("010-0000-0000") shouldBeEqualTo false
        }
    }

    @Test
    fun `OBSERVE 회원 누락은 식별자를 남기지 않는 legacy suppression으로 기록한다`() {
        transaction {
            writer.appointmentCreated(
                tenantGroupId = tenantGroupId,
                record = appointment(memberId = null),
                version = 0L,
                resolution = MemberResolution.LegacyMissing,
            )
        }

        transaction {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.rowKind] shouldBeEqualTo NotificationOutboxRowKind.LEGACY_SUPPRESSION
            row[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
            row[NotificationOutboxEvents.appointmentId] shouldBeEqualTo null
            row[NotificationOutboxEvents.memberId] shouldBeEqualTo null
            row[NotificationOutboxEvents.parametersJson] shouldBeEqualTo null
        }
    }

    @Test
    fun `회원 ID가 없는 기존 예약의 확정 알림과 리마인더는 모두 legacy suppression으로 끝낸다`() {
        val record = appointment(
            memberId = null,
            status = AppointmentState.CONFIRMED,
            version = 1L,
        )

        transaction {
            writer.statusChanged(
                tenantGroupId = tenantGroupId,
                record = record,
                version = record.version,
                from = AppointmentState.REQUESTED,
                to = AppointmentState.CONFIRMED,
            )
        }

        transaction {
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.size shouldBeEqualTo 3
            rows.all {
                it[NotificationOutboxEvents.rowKind] == NotificationOutboxRowKind.LEGACY_SUPPRESSION &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.SUPPRESSED &&
                    it[NotificationOutboxEvents.appointmentId] == null &&
                    it[NotificationOutboxEvents.memberId] == null &&
                    it[NotificationOutboxEvents.parametersJson] == null
            } shouldBeEqualTo true
        }
    }

    @Test
    fun `HMAC key가 없어도 알림을 만들지 않는 상태 전이는 통과하고 확정 전이는 거절한다`() {
        UnavailableAppointmentNotificationWriter.statusChanged(
            tenantGroupId = tenantGroupId,
            record = appointment(status = AppointmentState.PENDING_RESCHEDULE),
            version = 1L,
            from = AppointmentState.REQUESTED,
            to = AppointmentState.PENDING_RESCHEDULE,
        )

        assertFailsWith<NotificationContractException> {
            UnavailableAppointmentNotificationWriter.statusChanged(
                tenantGroupId = tenantGroupId,
                record = appointment(status = AppointmentState.CONFIRMED),
                version = 1L,
                from = AppointmentState.REQUESTED,
                to = AppointmentState.CONFIRMED,
            )
        }
    }

    @Test
    fun `확정 전이는 확정 알림과 두 리마인더를 미리 기록한다`() {
        val record = appointment(
            appointmentDate = LocalDate.of(2026, 8, 3),
            startTime = LocalTime.of(10, 0),
            status = AppointmentState.CONFIRMED,
            version = 1L,
        )

        transaction {
            writer.statusChanged(
                tenantGroupId = tenantGroupId,
                record = record,
                version = record.version,
                from = AppointmentState.REQUESTED,
                to = AppointmentState.CONFIRMED,
            )
        }

        transaction {
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.map { it[NotificationOutboxEvents.notificationSlot] }.toSet() shouldBeEqualTo
                setOf(NotificationSlot.CONFIRMED, NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
            rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.REMINDER_24H }
                .get(NotificationOutboxEvents.availableAt) shouldBeEqualTo
                Instant.parse("2026-08-02T01:00:00Z")
            rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.REMINDER_SAME_DAY }
                .get(NotificationOutboxEvents.availableAt) shouldBeEqualTo
                Instant.parse("2026-08-02T23:00:00Z")
        }
    }

    @Test
    fun `취소와 재예약 parameter는 등록 code와 전후 일정만 기록한다`() {
        val original = appointment(status = AppointmentState.CONFIRMED, version = 1L)
        val replacement = original.copy(
            id = 200L,
            appointmentDate = original.appointmentDate.plusDays(1),
            startTime = original.startTime.plusHours(1),
            endTime = original.endTime.plusHours(1),
            version = 0L,
        )

        transaction {
            writer.cancelled(
                tenantGroupId = tenantGroupId,
                record = original.copy(status = AppointmentState.CANCELLED, version = 2L),
                version = 2L,
                reasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
            )
            writer.rescheduled(
                tenantGroupId = tenantGroupId,
                original = original.copy(status = AppointmentState.RESCHEDULED, version = 2L),
                replacement = replacement,
                version = 2L,
            )
        }

        transaction {
            val codec = NotificationOutboxCodec()
            val rows = NotificationOutboxEvents.selectAll().toList()
            val cancelled = rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.CANCELLED }
            val cancelledEnvelope = codec.decode(requireNotNull(cancelled[NotificationOutboxEvents.parametersJson]))
            (cancelledEnvelope.parameters as AppointmentCancelledParameters).cancellationReasonCode?.value shouldBeEqualTo
                "CUSTOMER_REQUEST"
            val rescheduled = rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.RESCHEDULED }
            val parameters = codec.decode(requireNotNull(rescheduled[NotificationOutboxEvents.parametersJson])).parameters as
                AppointmentRescheduledParameters
            parameters.previousAppointmentDate shouldBeEqualTo original.appointmentDate
            parameters.replacementAppointmentDate shouldBeEqualTo replacement.appointmentDate
        }
    }

    @Test
    fun `v2 producer는 취소 detail과 template version 2를 함께 기록한다`() {
        val v2Writer = DefaultAppointmentNotificationWriter(
            writer = repository,
            hasher = DefaultNotificationOutboxHasher(
                StaticNotificationOutboxKeyRing(
                    active = NotificationHmacKey("active", ByteArray(32) { 1 }),
                    previous = null,
                )
            ),
            clinicRepository = ClinicRepository(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            sameDayReminderLeadTime = Duration.ofHours(2),
            cancellationSchemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
        )

        transaction {
            v2Writer.cancelled(
                tenantGroupId = tenantGroupId,
                record = appointment(status = AppointmentState.CANCELLED, version = 2L),
                version = 2L,
                reasonCode = CancellationReasonCode("CLINIC_REQUEST"),
                reasonDetail = "진료 일정이 변경되었습니다.",
            )
        }

        transaction {
            val row = NotificationOutboxEvents.selectAll().single()
            val envelope = NotificationOutboxCodec().decode(requireNotNull(row[NotificationOutboxEvents.parametersJson]))
            envelope.schemaVersion shouldBeEqualTo NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION
            envelope.templateVersion.value shouldBeEqualTo 2
            (envelope.parameters as AppointmentCancelledParameters).cancellationReasonDetail shouldBeEqualTo
                "진료 일정이 변경되었습니다."
        }
    }

    @Test
    fun `v1 producer는 detail을 조용히 버리지 않고 재시도 가능한 계약 오류로 닫는다`() {
        assertFailsWith<NotificationContractException> {
            transaction {
                writer.cancelled(
                    tenantGroupId = tenantGroupId,
                    record = appointment(status = AppointmentState.CANCELLED, version = 2L),
                    version = 2L,
                    reasonCode = CancellationReasonCode("CLINIC_REQUEST"),
                    reasonDetail = "진료 일정이 변경되었습니다.",
                )
            }
        }

        transaction { NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 0L }
    }

    @Test
    fun `legacy writer의 detail overload도 안내 문구를 조용히 폐기하지 않는다`() {
        assertFailsWith<NotificationContractException> {
            legacyWriter.cancelled(
                tenantGroupId = tenantGroupId,
                record = appointment(status = AppointmentState.CANCELLED, version = 2L),
                version = 2L,
                reasonCode = CancellationReasonCode("CLINIC_REQUEST"),
                reasonDetail = "진료 일정이 변경되었습니다.",
            )
        }
    }

    @Test
    fun `v2 고객 요청은 proposal 일정과 회원 ID로 생성 알림만 기록한다`() {
        transaction {
            writer.commitmentRequested(commitmentNotification())
        }

        transaction {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.eventType] shouldBeEqualTo NotificationEventType.CREATED
            row[NotificationOutboxEvents.notificationSlot] shouldBeEqualTo NotificationSlot.CREATED
            row[NotificationOutboxEvents.memberId] shouldBeEqualTo "member-v2"
            requireNotNull(row[NotificationOutboxEvents.parametersJson]).contains("환자 이름") shouldBeEqualTo false
            requireNotNull(row[NotificationOutboxEvents.parametersJson]).contains("010-0000-0000") shouldBeEqualTo false
        }
    }

    @Test
    fun `v2 확정은 병원 시간대로 확정 알림과 두 리마인더를 기록한다`() {
        transaction {
            writer.commitmentConfirmed(commitmentNotification())
        }

        transaction {
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.map { it[NotificationOutboxEvents.notificationSlot] }.toSet() shouldBeEqualTo
                setOf(NotificationSlot.CONFIRMED, NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
            val confirmed = rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.CONFIRMED }
            val parameters = NotificationOutboxCodec()
                .decode(requireNotNull(confirmed[NotificationOutboxEvents.parametersJson]))
                .parameters as AppointmentConfirmedParameters
            parameters.appointmentDate shouldBeEqualTo LocalDate.of(2026, 8, 3)
            parameters.startTime shouldBeEqualTo LocalTime.of(10, 0)
        }
    }

    @Test
    fun `v2 재배정은 이전 리마인더를 억제하고 새 일정 리마인더를 기록한다`() {
        val previous = commitmentNotification()
        val replacement = commitmentNotification(
            commitmentVersion = 3L,
            proposalRevision = 2L,
            startsAt = Instant.parse("2026-08-04T02:00:00Z"),
        )
        transaction {
            writer.commitmentConfirmed(previous)
            writer.commitmentRescheduled(previous, replacement)
        }

        transaction {
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.filter {
                it[NotificationOutboxEvents.eventType] == NotificationEventType.REMINDER &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.SUPPRESSED
            }.size shouldBeEqualTo 2
            rows.filter {
                it[NotificationOutboxEvents.eventType] == NotificationEventType.REMINDER &&
                    it[NotificationOutboxEvents.status] == NotificationOutboxStatus.PENDING
            }.size shouldBeEqualTo 2
            val rescheduled = rows.single {
                it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.RESCHEDULED
            }
            val parameters = NotificationOutboxCodec()
                .decode(requireNotNull(rescheduled[NotificationOutboxEvents.parametersJson]))
                .parameters as AppointmentRescheduledParameters
            parameters.previousAppointmentDate shouldBeEqualTo LocalDate.of(2026, 8, 3)
            parameters.replacementAppointmentDate shouldBeEqualTo LocalDate.of(2026, 8, 4)
        }
    }

    private fun appointment(
        memberId: MemberId? = MemberId("member-100"),
        appointmentDate: LocalDate = LocalDate.of(2026, 8, 2),
        startTime: LocalTime = LocalTime.of(10, 0),
        status: AppointmentState = AppointmentState.REQUESTED,
        version: Long = 0L,
    ): AppointmentRecord =
        AppointmentRecord(
            id = 100L,
            clinicId = clinicId,
            doctorId = 10L,
            treatmentTypeId = 20L,
            memberId = memberId,
            patientName = "환자 이름",
            patientPhone = "010-0000-0000",
            appointmentDate = appointmentDate,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = status,
            version = version,
        )

    private fun commitmentNotification(
        commitmentVersion: Long = 1L,
        proposalRevision: Long = 1L,
        startsAt: Instant = Instant.parse("2026-08-03T01:00:00Z"),
    ) = CommitmentAppointmentNotification(
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        appointmentId = 300L,
        memberId = MemberId("member-v2"),
        commitmentVersion = commitmentVersion,
        proposalRevision = proposalRevision,
        startsAt = startsAt,
        endsAt = startsAt.plus(Duration.ofMinutes(30)),
    )

    private val legacyWriter = object : AppointmentNotificationWriter {
        override fun appointmentCreated(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            resolution: MemberResolution,
        ) = Unit

        override fun statusChanged(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            from: AppointmentState,
            to: AppointmentState,
        ) = Unit

        override fun cancelled(
            tenantGroupId: Long,
            record: AppointmentRecord,
            version: Long,
            reasonCode: CancellationReasonCode?,
        ) = Unit

        override fun rescheduled(
            tenantGroupId: Long,
            original: AppointmentRecord,
            replacement: AppointmentRecord,
            version: Long,
        ) = Unit

        override fun commitmentRequested(notification: CommitmentAppointmentNotification) = Unit

        override fun commitmentConfirmed(notification: CommitmentAppointmentNotification) = Unit

        override fun commitmentCancelled(
            notification: CommitmentAppointmentNotification,
            reasonCode: CancellationReasonCode?,
        ) = Unit

        override fun commitmentRescheduled(
            previous: CommitmentAppointmentNotification,
            replacement: CommitmentAppointmentNotification,
        ) = Unit
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            Database.connect(
                url = "jdbc:h2:mem:notification-writer-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver",
            )
            transaction {
                SchemaUtils.createMissingTablesAndColumns(
                    TenantGroups,
                    Clinics,
                    NotificationOutboxEvents,
                )
            }
        }
    }
}
