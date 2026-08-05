package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.UpdateStatusRequest
import io.bluetape4k.clinic.appointment.api.notification.LegacyAppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.service.AppointmentService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.LocalDate
import java.time.LocalTime

@ExtendWith(OutputCaptureExtension::class)
class AppointmentControllerPrivacyTest {

    @Test
    fun `GET logs redact tenant clinic and appointment identities`(output: CapturedOutput) {
        val tenantCode = "tenant-sensitive"
        val tenantId = 91_001L
        val clinicId = 92_002L
        val appointmentId = 93_003L
        val startDate = LocalDate.of(2026, 8, 6)
        val endDate = startDate.plusDays(1)
        val tenant = TenantInfo(tenantId, tenantCode, "Sensitive Tenant")
        val scope = TenantClinicScope(tenantId, clinicId)
        val record = AppointmentRecord(
            id = appointmentId,
            clinicId = clinicId,
            doctorId = 94_004L,
            treatmentTypeId = 95_005L,
            patientName = "Protected Patient",
            appointmentDate = startDate,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 30),
            status = AppointmentState.REQUESTED,
        )
        val appointmentService = mockk<AppointmentService>()
        every { appointmentService.getByDateRange(scope, startDate, endDate) } returns emptyList()
        every { appointmentService.getById(appointmentId, tenantId) } returns record
        every { appointmentService.getStateHistory(appointmentId, tenantId) } returns emptyList()

        val accessChecker = mockk<TenantClinicAccessChecker>()
        every { accessChecker.requireTenant(tenantCode) } returns tenant
        every { accessChecker.verifyClinic(tenantCode, clinicId) } returns tenant

        val timezoneService = mockk<ClinicTimezoneService>()
        every { timezoneService.getTimezoneAndLocale(scope) } returns ("Asia/Seoul" to "ko-KR")

        val controller = AppointmentController(
            appointmentService = appointmentService,
            timezoneService = timezoneService,
            tenantClinicAccessChecker = accessChecker,
            appointmentMemberResolver = mockk<LegacyAppointmentMemberResolver>(),
        )

        controller.getByDateRange(tenantCode, clinicId, startDate, endDate)
        controller.getById(tenantCode, appointmentId)
        controller.getHistory(tenantCode, appointmentId)

        output.out.shouldContain("GET appointments scope=<redacted>")
        output.out.shouldContain("GET appointment scope=<redacted>")
        output.out.shouldContain("GET appointment history scope=<redacted>")
        output.out.shouldNotContain(tenantCode)
        output.out.shouldNotContain(tenantId.toString())
        output.out.shouldNotContain(clinicId.toString())
        output.out.shouldNotContain(appointmentId.toString())
    }

    @Test
    fun `appointment mutation logs redact tenant identity`(output: CapturedOutput) {
        val tenantCode = "tenant-mutation-sensitive"
        val tenantId = 96_006L
        val clinicId = 97_007L
        val appointmentId = 98_008L
        val tenant = TenantInfo(tenantId, tenantCode, "Sensitive Mutation Tenant")
        val accessChecker = mockk<TenantClinicAccessChecker>()
        every {
            accessChecker.verifySchedulingResources(tenantCode, clinicId, 99_009L, 100_010L, null)
        } returns tenant
        every { accessChecker.requireTenant(tenantCode) } returns tenant

        val appointmentService = mockk<AppointmentService>()
        every { appointmentService.create(any(), any(), any(), any(), any()) } throws IllegalStateException("stop after create log")
        every { appointmentService.getScope(appointmentId, tenantId) } throws IllegalStateException("stop after mutation log")

        val resolver = mockk<LegacyAppointmentMemberResolver>()
        every { resolver.resolveLegacy(tenantId, clinicId, null) } returns MemberResolution.LegacyMissing
        val request = mockk<HttpServletRequest>()
        every { request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) } returns "privacy-mutation-correlation"

        val controller = AppointmentController(
            appointmentService = appointmentService,
            timezoneService = mockk<ClinicTimezoneService>(),
            tenantClinicAccessChecker = accessChecker,
            appointmentMemberResolver = resolver,
        )
        val createRequest = CreateAppointmentRequest(
            clinicId = clinicId,
            doctorId = 99_009L,
            treatmentTypeId = 100_010L,
            patientName = "Protected Patient",
            appointmentDate = LocalDate.of(2099, 8, 6),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 30),
        )

        kotlin.runCatching { controller.create(tenantCode, null, createRequest, request) }
        kotlin.runCatching { runBlocking { controller.updateStatus(tenantCode, appointmentId, UpdateStatusRequest("CONFIRMED"), request) } }
        kotlin.runCatching { runBlocking { controller.cancel(tenantCode, appointmentId, null, request) } }

        output.out.shouldContain("POST appointment scope=<redacted>")
        output.out.shouldContain("PATCH appointment status scope=<redacted>")
        output.out.shouldContain("DELETE appointment scope=<redacted>")
        output.out.shouldNotContain(tenantCode)
    }
}
