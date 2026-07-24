package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * [AppointmentReminderScheduler] 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentReminderSchedulerTest {

    private val notificationChannel = mockk<NotificationChannel>(relaxed = true)
    private val appointmentRepository = AppointmentRepository()
    private val historyRepository = NotificationHistoryRepository()
    private val properties = NotificationProperties()

    private lateinit var scheduler: AppointmentReminderScheduler

    @BeforeAll
    fun setup() {
        NotificationTestSupport.connectH2()
        NotificationTestSupport.createSchema()
    }

    @BeforeEach
    fun cleanup() {
        scheduler = createScheduler()
        clearMocks(notificationChannel)
        transaction {
            NotificationHistoryTable.deleteAll()
            Appointments.deleteAll()
            TreatmentTypes.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
        }
    }

    @Test
    fun `다음날 확정 예약은 clinic id 와 무관하게 리마인더를 발송한다`() {
        val appointment = insertConfirmedAppointment(date = LocalDate.now().plusDays(1))

        scheduler.checkReminders()

        verify(exactly = 1) {
            notificationChannel.sendReminder(appointment, ReminderType.DAY_BEFORE)
        }
    }

    @Test
    fun `성공 이력이 있으면 중복 리마인더를 발송하지 않는다`() {
        val appointment = insertConfirmedAppointment(date = LocalDate.now().plusDays(1))
        transaction {
            historyRepository.save(
                NotificationHistoryRecord(
                    appointmentId = appointment.id!!,
                    channelType = "DUMMY",
                    eventType = NotificationEventType.REMINDER_DAY_BEFORE,
                    payloadJson = "{}",
                ),
            )
        }

        scheduler.checkReminders()

        verify(exactly = 0) {
            notificationChannel.sendReminder(any(), ReminderType.DAY_BEFORE)
        }
    }

    @Test
    fun `당일 리마인더는 병원 현지 시간의 설정된 발송 시점보다 이른 예약에 발송하지 않는다`() {
        val clinicNow = LocalDateTime.of(2026, 7, 24, 9, 0)
        val appointment = insertConfirmedAppointment(
            date = clinicNow.toLocalDate(),
            startTime = clinicNow.toLocalTime().plusHours(4),
            timezone = "UTC",
        )
        scheduler = createScheduler(clock = fixedUtcClock(clinicNow))

        scheduler.checkReminders()

        verify(exactly = 0) {
            notificationChannel.sendReminder(appointment, ReminderType.SAME_DAY)
        }
    }

    @Test
    fun `당일 리마인더는 설정된 시간 경계의 예약에 발송한다`() {
        val clinicNow = LocalDateTime.of(2026, 7, 24, 9, 0)
        val appointment = insertConfirmedAppointment(
            date = clinicNow.toLocalDate(),
            startTime = LocalTime.of(11, 0),
            timezone = "UTC",
        )
        scheduler = createScheduler(clock = fixedUtcClock(clinicNow))

        scheduler.checkReminders()

        verify(exactly = 1) {
            notificationChannel.sendReminder(appointment, ReminderType.SAME_DAY)
        }
    }

    @Test
    fun `당일 리마인더는 서버 날짜와 다른 병원 현지 날짜의 예약을 발송한다`() {
        val instant = LocalDateTime.of(2026, 7, 24, 23, 30).toInstant(ZoneOffset.UTC)
        val appointment = insertConfirmedAppointment(
            date = LocalDate.of(2026, 7, 25),
            startTime = LocalTime.of(10, 0),
            timezone = "Asia/Seoul",
        )
        scheduler = createScheduler(clock = Clock.fixed(instant, ZoneOffset.UTC))

        scheduler.checkReminders()

        verify(exactly = 1) {
            notificationChannel.sendReminder(appointment, ReminderType.SAME_DAY)
        }
    }

    private fun createScheduler(clock: Clock = Clock.systemUTC()) = AppointmentReminderScheduler(
        notificationChannel = notificationChannel,
        appointmentRepository = appointmentRepository,
        historyRepository = historyRepository,
        properties = properties,
        leaderElection = null,
        clock = clock,
    )

    private fun fixedUtcClock(localDateTime: LocalDateTime): Clock =
        Clock.fixed(localDateTime.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private fun insertConfirmedAppointment(
        date: LocalDate,
        startTime: LocalTime = LocalTime.of(9, 0),
        timezone: String = "UTC",
    ): AppointmentRecord =
        transaction {
            NotificationTestSupport.seedDefaultTenantIfMissing()
            val clinicId = Clinics.insertAndGetId {
                it[name] = "테스트 클리닉"
                it[slotDurationMinutes] = 30
                it[Clinics.timezone] = timezone
            }.value

            val doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "김의사"
            }.value

            val treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = clinicId
                it[name] = "일반진료"
                it[defaultDurationMinutes] = 30
            }.value

            val appointmentId = Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "홍길동"
                it[patientPhone] = "010-1234-5678"
                it[appointmentDate] = date
                it[Appointments.startTime] = startTime
                it[endTime] = startTime.plusMinutes(30)
                it[status] = AppointmentState.CONFIRMED
            }.value

            appointmentRepository.findByIdOrNull(appointmentId)!!
        }
}
