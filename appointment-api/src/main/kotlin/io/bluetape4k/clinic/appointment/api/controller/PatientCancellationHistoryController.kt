package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryPageResponse
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryQuery
import io.bluetape4k.clinic.appointment.api.service.PatientCancellationHistoryService
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryTenantIdentityGenerationProvider
import io.bluetape4k.clinic.appointment.api.service.requirePatientHistoryTenantIdentityGeneration
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiError
import io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiException
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** tenant-only PATIENT 취소 이력 조회 endpoint입니다. */
@RestController
@RequestMapping("/api/{tenantCode}/patient/appointments/cancellation-history")
internal class PatientCancellationHistoryController(
    private val serviceProvider: ObjectProvider<PatientCancellationHistoryService>,
    private val actorContextResolver: ActorContextResolver,
    private val tenantIdentityGenerationProvider: ObjectProvider<PatientHistoryTenantIdentityGenerationProvider>,
) {
    @GetMapping
    fun read(
        @PathVariable tenantCode: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<PatientCancellationHistoryPageResponse> {
        val service = serviceProvider.getIfAvailable()
            ?: throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE)
        val generationProvider = tenantIdentityGenerationProvider.getIfAvailable()
            ?: throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE)
        val actor = actorContextResolver.resolvePatientHistoryActor(authentication, tenantCode, request)
        val tenantIdentityGeneration = try {
            generationProvider
                .current(tenantCode, actor)
                .requirePatientHistoryTenantIdentityGeneration()
        } catch (failure: PatientHistoryApiException) {
            throw failure
        } catch (failure: Exception) {
            throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE, failure)
        }
        val result = service.read(
            actor = actor,
            tenantCode = tenantCode,
            query = PatientCancellationHistoryQuery(cursor = cursor, limit = limit),
            ifNoneMatch = ifNoneMatch,
        )
        val builder = ResponseEntity.status(if (result.notModified) 304 else 200)
            .eTag(result.etag)
            .cacheControl(CacheControl.noStore().mustRevalidate())
            .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
            .header(TENANT_IDENTITY_GENERATION_HEADER, tenantIdentityGeneration)
        return if (result.notModified) builder.build() else builder.body(result.body)
    }

    private companion object {
        const val TENANT_IDENTITY_GENERATION_HEADER = "X-Tenant-Identity-Generation"
    }
}
