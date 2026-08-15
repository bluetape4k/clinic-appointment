package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryPageResponse
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryQuery
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.service.PatientCancellationHistoryReadResult
import io.bluetape4k.clinic.appointment.api.service.PatientCancellationHistoryService
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryTenantIdentityGenerationProvider
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiError
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiException
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant

class PatientCancellationHistoryControllerTest {
    private val service = mockk<PatientCancellationHistoryService>()
    private val actorResolver = mockk<ActorContextResolver>()
    private val tenantIdentityGenerationProvider = mockk<PatientHistoryTenantIdentityGenerationProvider>()
    private val serviceProvider = mockk<ObjectProvider<PatientCancellationHistoryService>>()
    private val generationProvider = mockk<ObjectProvider<PatientHistoryTenantIdentityGenerationProvider>>()
    private val controller = PatientCancellationHistoryController(
        serviceProvider = serviceProvider,
        actorContextResolver = actorResolver,
        tenantIdentityGenerationProvider = generationProvider,
    )
    private val actor = ActorContext(
        actorId = "patient-1",
        actorType = ActorType.PATIENT,
        roles = setOf(ActorRole.PATIENT),
        scopes = emptySet(),
        allowedTenantCodes = setOf("tenant-task6"),
        allowedClinicIds = emptySet(),
        patientSubjectId = "patient-subject-1",
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-patient-1",
        authenticatedAt = Instant.parse("2026-08-14T00:00:00Z"),
        correlationId = "history-correlation",
        selectedTenantCode = "tenant-task6",
    )

    @Test
    fun `matching etag returns 304 with no-store and cookie variance`() {
        val request = MockHttpServletRequest()
        every { serviceProvider.getIfAvailable() } returns service
        every { generationProvider.getIfAvailable() } returns tenantIdentityGenerationProvider
        every { actorResolver.resolve(any(), "tenant-task6", null, any()) } returns actor
        every { tenantIdentityGenerationProvider.current("tenant-task6", actor) } returns "v1.generation-a"
        every {
            service.read(actor, "tenant-task6", PatientCancellationHistoryQuery(null, 20), "\"history-etag\"")
        } returns PatientCancellationHistoryReadResult(
            body = null,
            etag = "\"history-etag\"",
            notModified = true,
        )

        val response = controller.read(
            tenantCode = "tenant-task6",
            cursor = null,
            limit = 20,
            ifNoneMatch = "\"history-etag\"",
            authentication = null,
            request = request,
        )

        response.statusCode shouldBeEqualTo HttpStatus.NOT_MODIFIED
        response.headers.getFirst(HttpHeaders.ETAG) shouldBeEqualTo "\"history-etag\""
        response.headers.getFirst(HttpHeaders.CACHE_CONTROL)!!.contains("no-store").shouldBeEqualTo(true)
        response.headers.getFirst(HttpHeaders.VARY) shouldBeEqualTo HttpHeaders.COOKIE
        response.headers.getFirst("X-Tenant-Identity-Generation") shouldBeEqualTo "v1.generation-a"
        response.body shouldBeEqualTo null
    }

    @Test
    fun `changed page returns 200 and forwards cursor and limit`() {
        val request = MockHttpServletRequest()
        val body = PatientCancellationHistoryPageResponse(limit = 10, entries = emptyList(), nextCursor = "opaque")
        every { serviceProvider.getIfAvailable() } returns service
        every { generationProvider.getIfAvailable() } returns tenantIdentityGenerationProvider
        every { actorResolver.resolve(any(), "tenant-task6", null, any()) } returns actor
        every { tenantIdentityGenerationProvider.current("tenant-task6", actor) } returns "v1.generation-a"
        every {
            service.read(actor, "tenant-task6", PatientCancellationHistoryQuery("cursor-1", 10), null)
        } returns PatientCancellationHistoryReadResult(
            body = body,
            etag = "\"page-etag\"",
            notModified = false,
        )

        val response = controller.read(
            tenantCode = "tenant-task6",
            cursor = "cursor-1",
            limit = 10,
            ifNoneMatch = null,
            authentication = null,
            request = request,
        )

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.body shouldBeEqualTo body
        response.headers.getFirst(HttpHeaders.ETAG) shouldBeEqualTo "\"page-etag\""
        response.headers.getFirst("X-Tenant-Identity-Generation") shouldBeEqualTo "v1.generation-a"
    }

    @Test
    fun `invalid tenant generation fails closed`() {
        val request = MockHttpServletRequest()
        every { serviceProvider.getIfAvailable() } returns service
        every { generationProvider.getIfAvailable() } returns tenantIdentityGenerationProvider
        every { actorResolver.resolve(any(), "tenant-task6", null, any()) } returns actor
        every { tenantIdentityGenerationProvider.current("tenant-task6", actor) } returns "tenant-7"

        val failure = runCatching {
            controller.read("tenant-task6", null, 20, null, null, request)
        }.exceptionOrNull() as PatientHistoryApiException

        failure.error shouldBeEqualTo PatientHistoryApiError.UNAVAILABLE
    }

    @Test
    fun `api disabled still keeps route and returns sanitized unavailable`() {
        val request = MockHttpServletRequest()
        every { serviceProvider.getIfAvailable() } returns null

        val failure = runCatching {
            controller.read("tenant-task6", null, 20, null, null, request)
        }.exceptionOrNull() as PatientHistoryApiException

        failure.error shouldBeEqualTo PatientHistoryApiError.UNAVAILABLE
    }
}
