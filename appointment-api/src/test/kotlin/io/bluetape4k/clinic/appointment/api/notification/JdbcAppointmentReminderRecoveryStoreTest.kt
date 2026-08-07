package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.clinic.appointment.event.notification.DefaultNotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.AppointmentReminderParameters
import io.bluetape4k.clinic.appointment.event.notification.NotificationHmacKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.StaticNotificationOutboxKeyRing
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.notification.NotificationReminderRecoveryScanner
import io.bluetape4k.clinic.appointment.notification.AppointmentReminderScheduler
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private class RecordingDispatcher : CoroutineDispatcher(), AutoCloseable {

    private val delegate = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reminder-recovery-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val dispatchCount = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount.incrementAndGet()
        delegate.dispatch(context, block)
    }

    override fun close() {
        delegate.close()
    }
}

internal class JdbcAppointmentReminderRecoveryStoreTest {

    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val repository = NotificationOutboxRepository(NotificationOutboxCodec(), Duration.ofMinutes(5))
    private val hasher = DefaultNotificationOutboxHasher(
        StaticNotificationOutboxKeyRing(
            active = NotificationHmacKey("recovery-test", ByteArray(32) { 7 }),
            previous = null,
        )
    )
    private lateinit var store: JdbcAppointmentReminderRecoveryStore

    private var tenantGroupId = 0L
    private var clinicId = 0L
    private var doctorId = 0L
    private var treatmentTypeId = 0L

    @BeforeEach
    fun setUp() {
        store = recoveryStore(dayBeforeEnabled = true, sameDayEnabled = false)
        transaction(database) {
            ReminderRecoveryCheckpoints.deleteAll()
            NotificationOutboxEvents.deleteAll()
            AppointmentProposals.deleteAll()
            AppointmentCommitments.deleteAll()
            Appointments.deleteAll()
            TreatmentTypes.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "recovery-test"
                it[displayName] = "Recovery Test"
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = this@JdbcAppointmentReminderRecoveryStoreTest.tenantGroupId
                it[name] = "강남 진료소"
                it[timezone] = "Asia/Seoul"
            }.value
            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@JdbcAppointmentReminderRecoveryStoreTest.clinicId
                it[name] = "담당 의사"
            }.value
            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@JdbcAppointmentReminderRecoveryStoreTest.clinicId
                it[name] = "일반 진료"
                it[defaultDurationMinutes] = 30
            }.value
        }
    }

    @Test
    fun `중단 시간에 누락된 리마인더는 같은 key로 한 번만 복구한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), "member-1")
        val scanner = scanner(Duration.ofMinutes(30))

        scanner.scanOnce(10).enqueued shouldBeEqualTo 1
        scanner.scanOnce(10).alreadyExists shouldBeEqualTo 1

        transaction(database) {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.PENDING
            row[NotificationOutboxEvents.notificationSlot] shouldBeEqualTo NotificationSlot.REMINDER_24H
            row[NotificationOutboxEvents.memberId] shouldBeEqualTo "member-1"
            row[NotificationOutboxEvents.parametersJson]!!.contains("010-") shouldBeEqualTo false
        }
    }

    @Test
    fun `보정 시간창이 지난 리마인더는 늦게 보내지 않고 억제한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 20), "member-2")

        scanner(Duration.ofMinutes(30)).scanOnce(10).suppressed shouldBeEqualTo 1

        transaction(database) {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
            row[NotificationOutboxEvents.suppressionReason] shouldBeEqualTo
                NotificationSuppressionReasonCode.REMINDER_WINDOW_MISSED
            row[NotificationOutboxEvents.appointmentId] shouldBeEqualTo null
            row[NotificationOutboxEvents.memberId] shouldBeEqualTo null
        }
    }

    @Test
    fun `회원 ID가 없는 기존 예약은 연락처를 복사하지 않고 억제한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), null)

        scanner(Duration.ofMinutes(30)).scanOnce(10).suppressed shouldBeEqualTo 1

        transaction(database) {
            val row = NotificationOutboxEvents.selectAll().single()
            row[NotificationOutboxEvents.suppressionReason] shouldBeEqualTo
                NotificationSuppressionReasonCode.MEMBER_ID_MISSING_LEGACY
            row[NotificationOutboxEvents.parametersJson] shouldBeEqualTo null
        }
    }

    @Test
    fun `큰 병원 조회는 limit keyset page로 순차 진행한다`(): Unit = runBlocking {
        repeat(3) { offset ->
            insertConfirmedAppointment(
                LocalDate.of(2026, 8, 2),
                LocalTime.of(8, 40).plusMinutes(offset.toLong()),
                "member-$offset",
            )
        }

        val first = store.findCandidates(now, 2)
        first.forEach { store.enqueue(it) }
        val second = store.findCandidates(now, 2)

        first.size shouldBeEqualTo 2
        second.size shouldBeEqualTo 1
        first.map { it.appointmentId }.intersect(second.map { it.appointmentId }.toSet()).isEmpty() shouldBeEqualTo true
    }

    @Test
    fun `실행 한도보다 큰 backlog는 store 재생성 후 durable checkpoint에서 계속한다`(): Unit = runBlocking {
        repeat(3) { offset ->
            insertConfirmedAppointment(
                LocalDate.of(2026, 8, 2),
                LocalTime.of(8, 40).plusMinutes(offset.toLong()),
                "member-restart-$offset",
            )
        }

        val firstRun = AppointmentReminderScheduler(
            scanner = scanner(Duration.ofMinutes(30)),
            batchSize = 1,
            maxCandidatesPerRun = 2,
        ).triggerOnce()!!
        store = recoveryStore(dayBeforeEnabled = true, sameDayEnabled = false)
        val resumedRun = AppointmentReminderScheduler(
            scanner = scanner(Duration.ofMinutes(30)),
            batchSize = 1,
            maxCandidatesPerRun = 2,
        ).triggerOnce()!!

        firstRun.scanned shouldBeEqualTo 2
        resumedRun.scanned shouldBeEqualTo 1
        transaction(database) {
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 3L
        }
    }

    @Test
    fun `commitment v2는 확정 proposal revision과 UTC 일정을 사용한다`(): Unit = runBlocking {
        insertConfirmedCommitment(
            startsAt = Instant.parse("2026-08-01T23:50:00Z"),
            revision = 7L,
        )

        val candidate = store.findCandidates(now, 10).single()
        val parameters = candidate.payload!!.sendableDraft!!.envelope.parameters as AppointmentReminderParameters

        candidate.dueAt shouldBeEqualTo Instant.parse("2026-07-31T23:50:00Z")
        parameters.appointmentDate shouldBeEqualTo LocalDate.of(2026, 8, 2)
        parameters.startTime shouldBeEqualTo LocalTime.of(8, 50)
    }

    @Test
    fun `홀수 limit에서도 같은 예약의 두 reminder slot을 다음 page에 보존한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), "member-odd")
        val bothSlots = recoveryStore(dayBeforeEnabled = true, sameDayEnabled = true)

        val first = bothSlots.findCandidates(now, 1).single()
        val second = bothSlots.findCandidates(now, 1).single()

        first.appointmentId shouldBeEqualTo second.appointmentId
        setOf(first.slot, second.slot) shouldBeEqualTo
            setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
    }

    @Test
    fun `전일 reminder가 due이고 당일 reminder가 미래여도 둘 다 durable outbox에 기록한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), "member-two-slots")
        store = recoveryStore(dayBeforeEnabled = true, sameDayEnabled = true)

        val result = scanner(Duration.ofMinutes(30)).scanOnce(10)

        result.enqueued shouldBeEqualTo 2
        result.notYetDue shouldBeEqualTo 0
        transaction(database) {
            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.size shouldBeEqualTo 2
            rows.map { it[NotificationOutboxEvents.notificationSlot] }.toSet() shouldBeEqualTo
                setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
            val sameDay = rows.single {
                it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.REMINDER_SAME_DAY
            }
            sameDay[NotificationOutboxEvents.availableAt] shouldBeEqualTo Instant.parse("2026-08-01T21:50:00Z")
        }
    }

    @Test
    fun `동시 recovery materializer는 한 건만 새 enqueue로 보고한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), "member-concurrent")
        val candidate = store.findCandidates(now, 1).single()
        val competingStore = recoveryStore(dayBeforeEnabled = true, sameDayEnabled = false)

        val results = coroutineScope {
            listOf(store, competingStore).map { materializer ->
                async(Dispatchers.IO) { materializer.enqueue(candidate) }
            }.awaitAll()
        }

        results.toSet() shouldBeEqualTo setOf(
            io.bluetape4k.clinic.appointment.notification.ReminderRecoveryMaterializationResult.ENQUEUED,
            io.bluetape4k.clinic.appointment.notification.ReminderRecoveryMaterializationResult.ALREADY_EXISTS,
        )
        transaction(database) {
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `세 materializer 경로는 blocking transaction을 주입된 IO dispatcher에서 실행한다`(): Unit = runBlocking {
        insertConfirmedAppointment(LocalDate.of(2026, 8, 2), LocalTime.of(8, 50), "member-dispatcher")
        val ioDispatcher = RecordingDispatcher()
        try {
            store = recoveryStore(
                dayBeforeEnabled = true,
                sameDayEnabled = false,
                ioDispatcher = ioDispatcher,
            )
            val candidate = store.findCandidates(now, 1).single()
            val afterFind = ioDispatcher.dispatchCount.get()

            store.enqueue(candidate)
            val afterEnqueue = ioDispatcher.dispatchCount.get()
            store.suppressMissed(candidate)
            val afterSuppressMissed = ioDispatcher.dispatchCount.get()
            store.scheduleFuture(candidate)
            val afterScheduleFuture = ioDispatcher.dispatchCount.get()

            afterEnqueue shouldBeGreaterThan afterFind
            afterSuppressMissed shouldBeGreaterThan afterEnqueue
            afterScheduleFuture shouldBeGreaterThan afterSuppressMissed
        } finally {
            ioDispatcher.close()
        }
    }

    private fun scanner(catchUpWindow: Duration): NotificationReminderRecoveryScanner =
        NotificationReminderRecoveryScanner(
            source = store,
            materializer = store,
            catchUpWindow = catchUpWindow,
            clock = { now },
        )

    private fun recoveryStore(
        dayBeforeEnabled: Boolean,
        sameDayEnabled: Boolean,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): JdbcAppointmentReminderRecoveryStore =
        JdbcAppointmentReminderRecoveryStore(
            database = database,
            repository = repository,
            hasher = hasher,
            sameDayReminderLeadTime = Duration.ofHours(2),
            dayBeforeEnabled = dayBeforeEnabled,
            sameDayEnabled = sameDayEnabled,
            ioDispatcher = ioDispatcher,
        )

    private fun insertConfirmedAppointment(date: LocalDate, time: LocalTime, memberId: String?) {
        transaction(database) {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@JdbcAppointmentReminderRecoveryStoreTest.clinicId
                it[Appointments.doctorId] = this@JdbcAppointmentReminderRecoveryStoreTest.doctorId
                it[Appointments.treatmentTypeId] = this@JdbcAppointmentReminderRecoveryStoreTest.treatmentTypeId
                it[patientName] = "복구 대상"
                it[patientPhone] = "010-0000-0000"
                it[patientExternalId] = memberId
                it[appointmentDate] = date
                it[startTime] = time
                it[endTime] = time.plusMinutes(30)
                it[status] = AppointmentState.CONFIRMED
                it[version] = 1L
            }
        }
    }

    private fun insertConfirmedCommitment(startsAt: Instant, revision: Long) {
        transaction(database) {
            val localStart = startsAt.atZone(java.time.ZoneId.of("Asia/Seoul"))
            val appointmentId = Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@JdbcAppointmentReminderRecoveryStoreTest.clinicId
                it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                it[patientName] = "v2 복구 대상"
                it[patientExternalId] = "member-v2"
                it[appointmentDate] = localStart.toLocalDate()
                it[startTime] = localStart.toLocalTime()
                it[endTime] = localStart.toLocalTime().plusMinutes(30)
                it[status] = AppointmentState.CONFIRMED
            }
            val commitmentId = AppointmentCommitments.insertAndGetId {
                it[AppointmentCommitments.appointmentId] = appointmentId
                it[status] = AppointmentCommitmentStatus.CONFIRMED
                it[origin] = AppointmentOrigin.PATIENT
                it[effectivePolicySnapshotId] = 1L
                it[version] = 2L
            }
            val proposalId = AppointmentProposals.insertAndGetId {
                it[AppointmentProposals.commitmentId] = commitmentId
                it[AppointmentProposals.revision] = revision
                it[proposedStartAt] = startsAt
                it[proposedEndAt] = startsAt.plus(Duration.ofMinutes(30))
                it[expiresAt] = startsAt.minus(Duration.ofDays(1))
                it[representativeTreatmentName] = "일반 진료"
                it[proposalHash] = "a".repeat(64)
                it[policySnapshotId] = 1L
                it[createdByActor] = "test"
            }
            AppointmentCommitments.update({ AppointmentCommitments.id eq commitmentId }) {
                it[confirmedProposalId] = proposalId.value
            }
        }
    }

    companion object {
        private lateinit var database: Database

        @JvmStatic
        @BeforeAll
        fun connectDatabase() {
            database = Database.connect(
                url = "jdbc:h2:mem:reminder-recovery-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver",
            )
            transaction(database) {
                SchemaUtils.createMissingTablesAndColumns(
                    TenantGroups,
                    Clinics,
                    Doctors,
                    TreatmentTypes,
                    Equipments,
                    ConsultationTopics,
                    Appointments,
                    AppointmentCommitments,
                    AppointmentProposals,
                    ReminderRecoveryCheckpoints,
                    NotificationOutboxEvents,
                )
            }
        }
    }
}
