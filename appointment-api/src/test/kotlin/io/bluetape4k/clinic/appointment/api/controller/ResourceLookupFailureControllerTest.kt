package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.dto.toResponse
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 리소스 부재와 저장소/DB 장애가 서로 다른 HTTP 경계로 전달되는지 검증합니다.
 */
class ResourceLookupFailureControllerTest {

    private val tenant = TenantInfo(
        id = 1L,
        tenantCode = "tenant-a",
        displayName = "Tenant A",
    )

    @BeforeEach
    fun setUpDatabase() {
        Database.connect(
            "jdbc:h2:mem:issue248_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
    }

    @Test
    fun `equipment database failure is not translated to not found`() {
        val repository = mockk<EquipmentRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } throws SQLException("database unavailable")

        val exception = assertFailsWith<SQLException> {
            EquipmentController(repository, accessChecker()).getById("tenant-a", 7L)
        }

        exception.message shouldBeEqualTo "database unavailable"
    }

    @Test
    fun `missing equipment is still returned as not found`() {
        val repository = mockk<EquipmentRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } returns null

        val response = EquipmentController(repository, accessChecker()).getById("tenant-a", 7L)

        response.statusCode.value() shouldBeEqualTo 404
    }

    @Test
    fun `treatment type database failure is not translated to not found`() {
        val repository = mockk<TreatmentTypeRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } throws SQLException("database unavailable")

        val exception = assertFailsWith<SQLException> {
            TreatmentTypeController(repository, accessChecker()).getById("tenant-a", 7L)
        }

        exception.message shouldBeEqualTo "database unavailable"
    }

    @Test
    fun `missing treatment type is still returned as not found`() {
        val repository = mockk<TreatmentTypeRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } returns null

        val response = TreatmentTypeController(repository, accessChecker()).getById("tenant-a", 7L)

        response.statusCode.value() shouldBeEqualTo 404
    }

    @Test
    fun `doctor database failure is not translated to not found`() {
        val repository = mockk<DoctorRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } throws SQLException("database unavailable")

        val exception = assertFailsWith<SQLException> {
            DoctorController(repository, accessChecker()).getById("tenant-a", 7L)
        }

        exception.message shouldBeEqualTo "database unavailable"
    }

    @Test
    fun `missing doctor is still returned as not found`() {
        val repository = mockk<DoctorRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } returns null

        val response = DoctorController(repository, accessChecker()).getById("tenant-a", 7L)

        response.statusCode.value() shouldBeEqualTo 404
    }

    @Test
    fun `clinic database failure is not translated to not found`() {
        val repository = mockk<ClinicRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } throws SQLException("database unavailable")

        val exception = assertFailsWith<SQLException> {
            ClinicController(repository, accessChecker()).getById("tenant-a", 7L)
        }

        exception.message shouldBeEqualTo "database unavailable"
    }

    @Test
    fun `missing clinic is still returned as not found`() {
        val repository = mockk<ClinicRepository>()
        every { repository.findByIdAndTenant(7L, tenant.id) } returns null

        val response = ClinicController(repository, accessChecker()).getById("tenant-a", 7L)

        response.statusCode.value() shouldBeEqualTo 404
    }

    @Test
    fun `appointment response rejects a record without a persisted id`() {
        val exception = assertFailsWith<IllegalStateException> {
            AppointmentRecord(
                clinicId = 11L,
                doctorId = 12L,
                treatmentTypeId = 13L,
                patientName = "patient",
                appointmentDate = LocalDate.of(2026, 8, 10),
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(9, 30),
            ).toResponse()
        }

        exception.message shouldBeEqualTo "AppointmentRecord.id must not be null"
    }

    @Test
    fun `reschedule candidate response rejects a record without a persisted id`() {
        val exception = assertFailsWith<IllegalStateException> {
            RescheduleCandidateRecord(
                originalAppointmentId = 21L,
                candidateDate = LocalDate.of(2026, 8, 10),
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(10, 30),
                doctorId = 22L,
            ).toResponse()
        }

        exception.message shouldBeEqualTo "RescheduleCandidateRecord.id must not be null"
    }

    private fun accessChecker(): TenantClinicAccessChecker = mockk<TenantClinicAccessChecker> {
        every { requireTenant("tenant-a") } returns tenant
    }
}
