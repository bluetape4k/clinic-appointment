package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.service.AppointmentService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.api.tenant.TenantInfo
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.LocalDate
import java.time.LocalTime

@ExtendWith(OutputCaptureExtension::class)
class RescheduleControllerPrivacyTest {

    @Test
    fun `reschedule logs redact tenant identity across all paths`(output: CapturedOutput) {
        val tenantCode = "tenant-reschedule-sensitive"
        val tenantId = 101_011L
        val clinicId = 102_012L
        val appointmentId = 103_013L
        val candidateId = 104_014L
        val tenant = TenantInfo(tenantId, tenantCode, "Sensitive Reschedule Tenant")
        val record = AppointmentRecord(
            id = appointmentId,
            clinicId = clinicId,
            doctorId = 105_015L,
            treatmentTypeId = 106_016L,
            patientName = "Protected Patient",
            appointmentDate = LocalDate.of(2099, 8, 6),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 30),
            status = AppointmentState.CONFIRMED,
        )
        val principal = SchedulingUserPrincipal(
            userId = "privacy-operator",
            clinicId = clinicId,
            roles = setOf(SchedulingRole.ADMIN),
            allowedTenants = setOf(tenantCode),
            allowedClinicIds = setOf(clinicId),
        )
        val accessChecker = mockk<TenantClinicAccessChecker>()
        every { accessChecker.verifyClinic(tenantCode, clinicId) } returns tenant
        every { accessChecker.verifyClinicForPrincipal(tenantCode, clinicId, any()) } returns tenant
        every { accessChecker.requireTenant(tenantCode) } returns tenant
        every { accessChecker.requirePrincipalClinicAccess(tenantCode, clinicId, principal) } just Runs
        val appointmentService = mockk<AppointmentService>()
        every { appointmentService.getById(appointmentId, tenantId) } returns record
        val closureService = mockk<ClosureRescheduleService>()
        every { closureService.processClosureReschedule(any(), any(), any()) } throws IllegalStateException("stop after closure log")
        every { closureService.confirmReschedule(any(), any(), any(), any()) } throws IllegalStateException("stop after confirm log")
        every { closureService.autoReschedule(any(), any(), any()) } throws IllegalStateException("stop after auto log")
        val candidates = mockk<RescheduleCandidateRepository>()
        val request = mockk<HttpServletRequest>()
        every { request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) } returns "privacy-reschedule-correlation"

        val controller = RescheduleController(
            closureRescheduleService = closureService,
            appointmentService = appointmentService,
            tenantClinicAccessChecker = accessChecker,
            rescheduleCandidateRepository = candidates,
        )

        kotlin.runCatching {
            controller.processClosureReschedule(
                tenantCode = tenantCode,
                id = appointmentId,
                clinicId = clinicId,
                closureDate = LocalDate.of(2099, 8, 6),
                searchDays = 1,
                servletRequest = request,
                principal = principal,
            )
        }
        kotlin.runCatching { controller.getCandidates(tenantCode, appointmentId, principal) }
        kotlin.runCatching {
            controller.confirmReschedule(tenantCode, appointmentId, candidateId, request, principal)
        }
        kotlin.runCatching { controller.autoReschedule(tenantCode, appointmentId, request, principal) }

        output.out.shouldContain("POST closure reschedule scope=<redacted>")
        output.out.shouldContain("GET reschedule candidates scope=<redacted>")
        output.out.shouldContain("POST confirm reschedule scope=<redacted>")
        output.out.shouldContain("POST auto reschedule scope=<redacted>")
        output.out.shouldNotContain(tenantCode)
    }
}
