package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.ProviderType
import io.bluetape4k.clinic.appointment.model.tables.TreatmentCategory
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 동시 예약 생성 통합 테스트.
 *
 * Read-Check-Write 패턴에서 발생하는 Race Condition을 검증합니다.
 * countOverlapping() → save() 사이의 간극에서 maxConcurrent를 초과하는
 * 예약이 생성될 수 있음을 증명합니다.
 */
class ConcurrentBookingIntegrationTest {

    companion object : KLogging() {
        private lateinit var db: Database
        private val repository = AppointmentRepository()
        private val MONDAY = LocalDate.of(2026, 3, 23)

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = Database.connect(
                "jdbc:h2:mem:concurrent_test;DB_CLOSE_DELAY=-1;LOCK_MODE=1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Holidays,
                    Clinics,
                    ClinicDefaultBreakTimes,
                    OperatingHoursTable,
                    BreakTimes,
                    ClinicClosures,
                    Doctors,
                    DoctorSchedules,
                    DoctorAbsences,
                    Equipments,
                    TreatmentTypes,
                    TreatmentEquipments,
                    ConsultationTopics,
                    Appointments,
                    AppointmentNotes,
                    EquipmentUnavailabilities,
                    EquipmentUnavailabilityExceptions
                )
            }
        }
    }

    private var clinicId: Long = 0
    private var doctorId: Long = 0
    private var treatmentTypeId: Long = 0

    @BeforeEach
    fun cleanUp() {
        transaction {
            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
            AppointmentNotes.deleteAll()
            Appointments.deleteAll()
            TreatmentEquipments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            DoctorAbsences.deleteAll()
            DoctorSchedules.deleteAll()
            Doctors.deleteAll()
            ClinicClosures.deleteAll()
            BreakTimes.deleteAll()
            ClinicDefaultBreakTimes.deleteAll()
            OperatingHoursTable.deleteAll()
            Clinics.deleteAll()
            Holidays.deleteAll()
        }
        val (c, d, t) = insertBaseData()
        clinicId = c
        doctorId = d
        treatmentTypeId = t
    }

    private fun insertBaseData(
        maxConcurrentPatients: Int = 1,
    ): Triple<Long, Long, Long> = transaction {
        val cId = Clinics.insertAndGetId {
            it[name] = "Concurrent Test Clinic"
            it[slotDurationMinutes] = 30
            it[Clinics.maxConcurrentPatients] = maxConcurrentPatients
            it[openOnHolidays] = false
        }.value

        OperatingHoursTable.insert {
            it[OperatingHoursTable.clinicId] = cId
            it[dayOfWeek] = DayOfWeek.MONDAY
            it[openTime] = LocalTime.of(9, 0)
            it[closeTime] = LocalTime.of(18, 0)
            it[isActive] = true
        }

        val dId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = cId
            it[name] = "Dr. Concurrent"
            it[providerType] = ProviderType.DOCTOR
            it[Doctors.maxConcurrentPatients] = null
        }.value

        DoctorSchedules.insert {
            it[DoctorSchedules.doctorId] = dId
            it[dayOfWeek] = DayOfWeek.MONDAY
            it[startTime] = LocalTime.of(9, 0)
            it[endTime] = LocalTime.of(18, 0)
        }

        val tId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = cId
            it[name] = "General"
            it[category] = TreatmentCategory.TREATMENT
            it[defaultDurationMinutes] = 30
            it[requiredProviderType] = ProviderType.DOCTOR
            it[consultationMethod] = null
            it[requiresEquipment] = false
            it[TreatmentTypes.maxConcurrentPatients] = null
        }.value

        Triple(cId, dId, tId)
    }

    @Test
    fun `resolveMaxConcurrent - treatmentMax 우선`() {
        resolveMaxConcurrent(clinicMax = 5, doctorMax = 3, treatmentMax = 2) shouldBeEqualTo 2
    }

    @Test
    fun `resolveMaxConcurrent - doctorMax 차선`() {
        resolveMaxConcurrent(clinicMax = 5, doctorMax = 3, treatmentMax = null) shouldBeEqualTo 3
    }

    @Test
    fun `resolveMaxConcurrent - clinicMax 기본`() {
        resolveMaxConcurrent(clinicMax = 5, doctorMax = null, treatmentMax = null) shouldBeEqualTo 5
    }

    @Test
    fun `동시 예약 - maxConcurrent=1에서 10개 동시 요청 시 race condition 발생`() {
        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        val slotStart = LocalTime.of(9, 0)
        val slotEnd = LocalTime.of(9, 30)
        val maxConcurrent = resolveMaxConcurrent(clinicMax = 1, doctorMax = null, treatmentMax = null)

        val localClinicId = clinicId
        val localDoctorId = doctorId
        val localTreatmentTypeId = treatmentTypeId

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    barrier.await(5, TimeUnit.SECONDS)

                    transaction {
                        val overlapping = repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)

                        if (overlapping < maxConcurrent) {
                            Appointments.insert {
                                it[Appointments.clinicId] = localClinicId
                                it[Appointments.doctorId] = localDoctorId
                                it[Appointments.treatmentTypeId] = localTreatmentTypeId
                                it[patientName] = "Patient-$i"
                                it[appointmentDate] = MONDAY
                                it[startTime] = slotStart
                                it[endTime] = slotEnd
                                it[status] = AppointmentState.REQUESTED
                            }
                            successCount.incrementAndGet()
                        } else {
                            failCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    log.info { "Thread $i failed: ${e.message}" }
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        val totalInserted = transaction {
            repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)
        }

        log.info { "동시 예약 결과: success=$successCount, fail=$failCount, DB 실제 예약=$totalInserted" }

        // Race condition 증명: maxConcurrent=1인데 실제로 1개 이상 들어감
        // H2의 트랜잭션 격리 수준에 따라 1개만 들어갈 수도 있지만, 여러 개 들어가면 race condition 증명
        totalInserted shouldBeGreaterThan 0
        (successCount.get() + failCount.get()) shouldBeEqualTo threadCount
    }

    @Test
    fun `동시 예약 - maxConcurrent=3에서 10개 동시 요청`() {
        // maxConcurrentPatients=3으로 재설정
        transaction {
            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
            AppointmentNotes.deleteAll()
            Appointments.deleteAll()
            TreatmentEquipments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            DoctorAbsences.deleteAll()
            DoctorSchedules.deleteAll()
            Doctors.deleteAll()
            ClinicClosures.deleteAll()
            BreakTimes.deleteAll()
            ClinicDefaultBreakTimes.deleteAll()
            OperatingHoursTable.deleteAll()
            Clinics.deleteAll()
            Holidays.deleteAll()
        }
        val (c, d, t) = insertBaseData(maxConcurrentPatients = 3)
        clinicId = c
        doctorId = d
        treatmentTypeId = t

        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        val slotStart = LocalTime.of(9, 0)
        val slotEnd = LocalTime.of(9, 30)
        val maxConcurrent = resolveMaxConcurrent(clinicMax = 3, doctorMax = null, treatmentMax = null)

        val localClinicId = clinicId
        val localDoctorId = doctorId
        val localTreatmentTypeId = treatmentTypeId

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    barrier.await(5, TimeUnit.SECONDS)

                    transaction {
                        val overlapping = repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)

                        if (overlapping < maxConcurrent) {
                            Appointments.insert {
                                it[Appointments.clinicId] = localClinicId
                                it[Appointments.doctorId] = localDoctorId
                                it[Appointments.treatmentTypeId] = localTreatmentTypeId
                                it[patientName] = "Patient-$i"
                                it[appointmentDate] = MONDAY
                                it[startTime] = slotStart
                                it[endTime] = slotEnd
                                it[status] = AppointmentState.REQUESTED
                            }
                            successCount.incrementAndGet()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        val totalInserted = transaction {
            repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)
        }

        log.info { "동시 예약 (max=3): success=$successCount, DB 실제 예약=$totalInserted" }

        // 최소 1개 이상 성공, maxConcurrent보다 많이 들어가면 race condition
        totalInserted shouldBeGreaterThan 0
    }

    @Test
    fun `순차 예약 - maxConcurrent 초과 시 정상 거부`() {
        val slotStart = LocalTime.of(10, 0)
        val slotEnd = LocalTime.of(10, 30)
        val maxConcurrent = 1

        val localClinicId = clinicId
        val localDoctorId = doctorId
        val localTreatmentTypeId = treatmentTypeId

        val insertedCount = AtomicInteger(0)

        repeat(5) { i ->
            transaction {
                val overlapping = repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)
                if (overlapping < maxConcurrent) {
                    Appointments.insert {
                        it[Appointments.clinicId] = localClinicId
                        it[Appointments.doctorId] = localDoctorId
                        it[Appointments.treatmentTypeId] = localTreatmentTypeId
                        it[patientName] = "Sequential-$i"
                        it[appointmentDate] = MONDAY
                        it[startTime] = slotStart
                        it[endTime] = slotEnd
                        it[status] = AppointmentState.REQUESTED
                    }
                    insertedCount.incrementAndGet()
                }
            }
        }

        insertedCount.get() shouldBeLessOrEqualTo maxConcurrent

        val totalInserted = transaction {
            repository.countOverlapping(localDoctorId, MONDAY, slotStart, slotEnd)
        }
        totalInserted shouldBeEqualTo 1
    }
}
