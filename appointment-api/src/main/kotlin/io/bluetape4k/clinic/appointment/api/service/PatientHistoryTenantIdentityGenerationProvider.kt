package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import java.nio.charset.StandardCharsets

/**
 * 환자 이력 응답에 사용할 tenant identity generation의 권위 있는 소유자입니다.
 *
 * 구현체는 shared tenant resolver 또는 session registry에서 opaque generation을
 * 읽어야 하며, tenant ID나 process-local 임의 값을 토큰으로 노출해서는 안 됩니다.
 * API를 켤 때 이 port의 외부 구현이 없으면 [ServiceConfig]가 fail-closed 합니다.
 */
fun interface PatientHistoryTenantIdentityGenerationProvider {
    /** 현재 요청의 tenant identity generation을 반환합니다. */
    fun current(tenantCode: String, actor: ActorContext): String
}

/** provider 경계를 통과하는 opaque generation의 wire grammar를 검증합니다. */
fun String.requirePatientHistoryTenantIdentityGeneration(): String {
    if (!TENANT_IDENTITY_GENERATION.matches(this) ||
        toByteArray(StandardCharsets.US_ASCII).size > MAX_TENANT_IDENTITY_GENERATION_BYTES
    ) {
        throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE)
    }
    return this
}

private const val MAX_TENANT_IDENTITY_GENERATION_BYTES = 128
private val TENANT_IDENTITY_GENERATION = Regex("v1\\.[A-Za-z0-9_-]{1,32}")
