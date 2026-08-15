package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import java.util.UUID

/**
 * Gateway가 선택한 단일 tenant·clinic을 commitment용 [ActorContext]로 변환한다.
 *
 * [SchedulingUserPrincipal.allowedClinicIds]는 접근 가능한 병원 집합이고
 * [SchedulingUserPrincipal.clinicId]는 이번 요청에서 Gateway가 선택한 병원이다.
 * 여러 병원을 관리하는 actor라도 선택 병원이 허용 집합에 있으면 사용할 수 있으며,
 * 선택 병원이 없거나 허용 집합 밖이면 fail-closed로 거절한다.
 */
internal fun ActorContextResolver.resolveAppointmentActor(
    authentication: Authentication?,
    tenantCode: String,
    request: HttpServletRequest,
): ActorContext {
    if (!TenantCodeRules.isCanonical(tenantCode)) {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_MISMATCH)
    }
    val principal = authentication
        ?.takeIf(Authentication::isAuthenticated)
        ?.principal as? SchedulingUserPrincipal
        ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
    val clinicId = principal.clinicId
        ?.takeIf(principal.allowedClinicIds::contains)
        ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_MISMATCH)
    val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
        ?: UUID.randomUUID().toString()
    return resolve(authentication, tenantCode, clinicId, correlationId)
}

/** clinic selector가 없는 환자 취소 이력 route의 tenant-only actor를 해석합니다. */
internal fun ActorContextResolver.resolvePatientHistoryActor(
    authentication: Authentication?,
    tenantCode: String,
    request: HttpServletRequest,
): ActorContext {
    if (!TenantCodeRules.isCanonical(tenantCode)) {
        throw io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiException(
            io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiError.PAYLOAD_INVALID,
        )
    }
    val correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
        ?: UUID.randomUUID().toString()
    return resolve(authentication, tenantCode, clinicId = null, correlationId = correlationId)
        .also {
            if (it.actorType != ActorType.PATIENT || it.patientSubjectId.isNullOrBlank()) {
                throw io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiException(
                    io.bluetape4k.clinic.appointment.api.service.PatientHistoryApiError.SCOPE_FORBIDDEN,
                )
            }
        }
}

/** 고객 전용 command가 비어 있지 않은 Gateway patient subject를 가졌는지 검증한다. */
internal fun ActorContext.requirePatientActor(): ActorContext {
    if (actorType != ActorType.PATIENT || patientSubjectId.isNullOrBlank()) {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
    }
    return this
}

/** 병원 관리자 전용 command가 정확한 관리자 identity인지 검증한다. */
internal fun ActorContext.requireAdminActor(): ActorContext {
    if (actorType != ActorType.ADMIN) {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
    }
    return this
}

/** 운영자 commitment 조회는 ADMIN과 STAFF 모두 선택 clinic 범위에서 허용합니다. */
internal fun ActorContext.requireCommitmentReadActor(): ActorContext {
    if (actorType != ActorType.ADMIN && actorType != ActorType.STAFF) {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
    }
    return this
}

/** 취소만 허용되는 patient/operator role matrix를 HTTP 경계에서 재검증한다. */
internal fun ActorContext.requireCancellationActor(reasonDetail: String?): ActorContext {
    when (actorType) {
        ActorType.PATIENT -> {
            requirePatientActor()
            if (reasonDetail != null) {
                throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PAYLOAD_INVALID)
            }
        }

        ActorType.ADMIN,
        ActorType.STAFF,
        -> Unit

        else -> throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
    }
    return this
}

/**
 * rollback 중 신규 Plan ingress만 닫고 기존 commitment 예약의 조회·변경 경로는 유지한다.
 *
 * [enabled]는 `appointment.commitment.ingress-enabled`에서 오며 운영자가 이미 생성된
 * commitment에 접근하려고 `api-enabled`까지 끄는 잘못된 rollback을 피하게 한다.
 */
internal fun requireAppointmentIngress(enabled: Boolean) {
    if (!enabled) {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.INGRESS_DISABLED)
    }
}

/** 원문을 저장하기 전에 제한된 HTTP 멱등성 key 형식을 검증한다. */
internal fun requireIdempotencyKey(value: String?): String =
    value
        ?.takeIf(IDEMPOTENCY_KEY::matches)
        ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PRECONDITION_REQUIRED)

/** 생성 요청이 `If-None-Match: *` 조건을 명시했는지 검증한다. */
internal fun requireCreateOnly(ifNoneMatch: String?): Boolean =
    if (ifNoneMatch?.trim() == "*") {
        true
    } else {
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PRECONDITION_REQUIRED)
    }

/** 강한 `If-Match` ETag에서 양수 commitment version을 추출한다. */
internal fun requireExpectedVersion(ifMatch: String?): Long {
    val raw = ifMatch?.trim()
        ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PRECONDITION_REQUIRED)
    return STRONG_VERSION_ETAG.matchEntire(raw)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.PRECONDITION_REQUIRED)
}

/** 가예약 결과를 다음 mutation용 ETag와 함께 `202 Accepted`로 반환한다. */
internal fun AppointmentProposalResponse.acceptedResponse(): ResponseEntity<AppointmentProposalResponse> =
    ResponseEntity.accepted()
        .header(HttpHeaders.ETAG, "\"$version\"")
        .body(this)

/** commitment 결과를 다음 mutation용 ETag와 함께 `200 OK`로 반환한다. */
internal fun AppointmentCommitmentResponse.okResponse(): ResponseEntity<AppointmentCommitmentResponse> =
    ResponseEntity.ok()
        .header(HttpHeaders.ETAG, "\"$version\"")
        .body(this)

/** 관리자 직접 생성 결과를 다음 mutation용 ETag와 함께 `201 Created`로 반환한다. */
internal fun AppointmentCommitmentResponse.createdResponse(): ResponseEntity<AppointmentCommitmentResponse> =
    ResponseEntity.status(201)
        .header(HttpHeaders.ETAG, "\"$version\"")
        .body(this)

private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}")
private val STRONG_VERSION_ETAG = Regex("\"([1-9][0-9]*)\"")
