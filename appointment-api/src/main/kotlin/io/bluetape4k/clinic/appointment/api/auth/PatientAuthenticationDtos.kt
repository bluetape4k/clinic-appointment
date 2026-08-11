package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifier
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import java.time.Instant

/** HTTP에서 사용하는 구조화된 환자 login identifier입니다. */
data class PatientLoginIdentifierRequest(
    val key: PatientLoginIdentifierKey,
    val value: String,
)

/** 환자 회원가입 요청입니다. tenant는 URL path에서만 결정됩니다. */
data class PatientRegisterRequest(
    val displayName: String,
    val password: String,
    val identifiers: List<PatientLoginIdentifierRequest>,
)

/** 환자 로그인 요청입니다. identifier를 packed string으로 받지 않습니다. */
data class PatientLoginRequest(
    val identifier: PatientLoginIdentifierRequest,
    val password: String,
)

/** 환자 session의 public summary입니다. token과 credential은 포함하지 않습니다. */
data class PatientSessionSummary(
    val tenantCode: String,
    val role: String,
    val displayName: String,
    val expiresAt: Instant,
)

/** 회원가입 성공 결과입니다. patientSubject는 opaque 내부 식별자이며 HTTP 응답에는 노출하지 않습니다. */
data class PatientRegistrationResult(
    val accountId: Long,
    val patientSubject: String,
    val identifierKeys: Set<PatientLoginIdentifierKey>,
)

/** 등록 성공을 나타내는 최소 public response입니다. */
data class PatientRegistrationResponse(
    val registered: Boolean = true,
)

/** 로그인 성공 결과입니다. token은 controller가 HttpOnly cookie로만 전달합니다. */
data class PatientLoginResult(
    val token: String,
    val session: PatientSessionSummary,
)

/** CSRF bootstrap의 무해한 응답입니다. secret은 response body로 반환하지 않습니다. */
data class PatientCsrfResponse(
    val ready: Boolean = true,
)

/** HTTP 입력을 core의 canonical identifier로 변환하는 단일 경계입니다. */
object PatientLoginIdentifierNormalizer {
    fun normalize(request: PatientLoginIdentifierRequest): PatientLoginIdentifier =
        try {
            PatientLoginIdentifier.of(request.key, request.value)
        } catch (_: IllegalArgumentException) {
            throw PatientAuthenticationValidationException("identifier input is invalid")
        }
}
