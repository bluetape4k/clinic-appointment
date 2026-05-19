package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertFailsWith

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DashboardStatsServiceTest {

    companion object : KLogging() {

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @Autowired
    private lateinit var dashboardStatsService: DashboardStatsService

    private var clinicId: Long = 0L
    private var doctorId: Long = 0L
    private var treatmentTypeId: Long = 0L

    @BeforeEach
    fun setup() {
        transaction {
            SchemaUtils.create(Clinics, Doctors, Equipments, TreatmentTypes, ConsultationTopics, Appointments)
            Appointments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()

            // Extract locals to avoid implicit-receiver shadowing inside insertAndGetId lambdas.
            // Inside Clinics.insertAndGetId {}, the implicit receiver is Clinics, so local names
            // like clinicId/doctorId would resolve to columns rather than the class property.
            val cId = Clinics.insertAndGetId {
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value
            clinicId = cId

            val dId = Doctors.insertAndGetId {
                it[clinicId] = cId        // KEY: Doctors.clinicId column; VALUE: cId local
                it[name] = "Dr. Kim"
            }.value
            doctorId = dId

            val ttId = TreatmentTypes.insertAndGetId {
                it[clinicId] = cId        // KEY: TreatmentTypes.clinicId; VALUE: cId local
                it[name] = "General"
                it[defaultDurationMinutes] = 30
            }.value
            treatmentTypeId = ttId
        }
    }

    private fun insertAppointment(date: LocalDate, status: AppointmentState) {
        val cId = clinicId
        val dId = doctorId
        val ttId = treatmentTypeId
        transaction {
            Appointments.insertAndGetId {
                it[clinicId] = cId        // KEY: Appointments.clinicId; VALUE: cId local
                it[doctorId] = dId        // KEY: Appointments.doctorId; VALUE: dId local
                it[treatmentTypeId] = ttId
                it[patientName] = "Patient"
                it[appointmentDate] = date
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[Appointments.status] = status
            }
        }
    }

    // =================== getAppointmentStats ===================

    @Test
    fun `getAppointmentStats - 정상 bucket 조립 및 totals 계산`() {
        val date = LocalDate.of(2026, 5, 1)
        insertAppointment(date, AppointmentState.CONFIRMED)
        insertAppointment(date, AppointmentState.CONFIRMED)
        insertAppointment(date, AppointmentState.CANCELLED)

        val result = dashboardStatsService.getAppointmentStats(clinicId, date, date)

        result.clinicId shouldBeEqualTo clinicId
        result.from shouldBeEqualTo date
        result.to shouldBeEqualTo date
        result.totals["CONFIRMED"] shouldBeEqualTo 2L
        result.totals["CANCELLED"] shouldBeEqualTo 1L
        result.daily.shouldNotBeEmpty()
        result.daily[0].countsByStatus["CONFIRMED"] shouldBeEqualTo 2L
        result.daily[0].countsByStatus["CANCELLED"] shouldBeEqualTo 1L
        result.daily[0].total shouldBeEqualTo 3L
    }

    @Test
    fun `getAppointmentStats - 빈 clinic은 empty daily 및 empty totals 반환`() {
        val date = LocalDate.of(2026, 5, 1)
        val result = dashboardStatsService.getAppointmentStats(999L, date, date)

        result.daily.shouldBeEmpty()
        result.totals.values.all { it == 0L }.shouldBeTrue()
    }

    @Test
    fun `getAppointmentStats - from이 to 이후면 IAE`() {
        val from = LocalDate.of(2026, 5, 10)
        val to = LocalDate.of(2026, 5, 1)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getAppointmentStats(clinicId, from, to)
        }
    }

    @Test
    fun `getAppointmentStats - 367일 초과 범위는 IAE`() {
        val from = LocalDate.of(2026, 1, 1)
        val to = from.plusDays(367)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getAppointmentStats(clinicId, from, to)
        }
    }

    @Test
    fun `getAppointmentStats - clinicId 0 이하는 IAE`() {
        val date = LocalDate.of(2026, 5, 1)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getAppointmentStats(0L, date, date)
        }
    }

    @Test
    fun `getAppointmentStats - 알 수 없는 상태명은 IAE 전파`() {
        val date = LocalDate.of(2026, 5, 1)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getAppointmentStats(clinicId, date, date, statuses = listOf("UNKNOWN"))
        }
    }

    @Test
    fun `getAppointmentStats - from·to null이면 기본 30일 기간 적용`() {
        val result = dashboardStatsService.getAppointmentStats(clinicId, null, null)

        val today = LocalDate.now()
        result.to shouldBeEqualTo today
        result.from shouldBeEqualTo today.minusDays(29)
    }

    // =================== getDoctorStats ===================

    @Test
    fun `getDoctorStats - totalAppointments 기준 내림차순 정렬`() {
        val date = LocalDate.of(2026, 5, 1)
        val cId = clinicId
        val ttId = treatmentTypeId

        val doctor2Id = transaction {
            Doctors.insertAndGetId {
                it[clinicId] = cId        // KEY: Doctors.clinicId; VALUE: cId local
                it[name] = "Dr. Park"
            }.value
        }

        // doctorId: 2건, doctor2Id: 1건
        insertAppointment(date, AppointmentState.COMPLETED)
        insertAppointment(date, AppointmentState.CONFIRMED)

        transaction {
            Appointments.insertAndGetId {
                it[clinicId] = cId        // KEY: Appointments.clinicId; VALUE: cId local
                it[doctorId] = doctor2Id  // KEY: Appointments.doctorId; VALUE: doctor2Id local
                it[treatmentTypeId] = ttId
                it[patientName] = "Patient2"
                it[appointmentDate] = date
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[status] = AppointmentState.CONFIRMED
            }
        }

        val result = dashboardStatsService.getDoctorStats(clinicId, date, date, limit = 10)

        result.doctors.shouldNotBeEmpty()
        result.doctors[0].doctorId shouldBeEqualTo doctorId
        result.doctors[0].totalAppointments shouldBeEqualTo 2L
    }

    @Test
    fun `getDoctorStats - 터미널 상태 없을 때 completionRate 0점0 안전`() {
        val date = LocalDate.of(2026, 5, 1)
        insertAppointment(date, AppointmentState.CONFIRMED)

        val result = dashboardStatsService.getDoctorStats(clinicId, date, date, limit = 10)

        result.doctors[0].completionRate shouldBeEqualTo 0.0
    }

    @Test
    fun `getDoctorStats - limit 0 은 IAE`() {
        val date = LocalDate.of(2026, 5, 1)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getDoctorStats(clinicId, date, date, limit = 0)
        }
    }

    @Test
    fun `getDoctorStats - limit 101 은 IAE`() {
        val date = LocalDate.of(2026, 5, 1)

        assertFailsWith<IllegalArgumentException> {
            dashboardStatsService.getDoctorStats(clinicId, date, date, limit = 101)
        }
    }

    // =================== getCancellationStats ===================

    @Test
    fun `getCancellationStats - rate 계산 정확성 검증`() {
        val date = LocalDate.of(2026, 5, 1)
        insertAppointment(date, AppointmentState.CANCELLED)
        insertAppointment(date, AppointmentState.CANCELLED)
        insertAppointment(date, AppointmentState.COMPLETED)
        insertAppointment(date, AppointmentState.NO_SHOW)

        val result = dashboardStatsService.getCancellationStats(clinicId, date, date)

        result.totalCancelled shouldBeEqualTo 2L
        result.totalNoShow shouldBeEqualTo 1L
        result.totalCompleted shouldBeEqualTo 1L
        // denominator = 2+1+0+1 = 4; cancellationRate = 2/4 = 0.5
        result.cancellationRate shouldBeEqualTo 0.5
        result.noShowRate shouldBeEqualTo 0.25
    }

    @Test
    fun `getCancellationStats - 터미널 상태 없을 때 rate 0점0 안전`() {
        val date = LocalDate.of(2026, 5, 1)
        insertAppointment(date, AppointmentState.CONFIRMED)

        val result = dashboardStatsService.getCancellationStats(clinicId, date, date)

        result.cancellationRate shouldBeEqualTo 0.0
        result.noShowRate shouldBeEqualTo 0.0
    }
}
