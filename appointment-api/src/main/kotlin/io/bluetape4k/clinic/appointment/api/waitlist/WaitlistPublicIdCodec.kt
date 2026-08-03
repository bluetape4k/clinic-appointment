package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import java.nio.charset.StandardCharsets
import java.util.Base64

data class WaitlistTenantScope(
    val tenantGroupId: Long,
    val tenantCode: String,
    val clinicId: Long,
    val actor: ActorContext,
    val correlationId: String,
) {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(tenantCode.isNotBlank()) { "tenantCode must not be blank" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
    }
}

enum class WaitlistPublicIdKind(val prefix: String) {
    ENTRY("e"),
    OFFER("o"),
    POLICY("p"),
    RESTRICTION("r"),
    RECOVERY_CREDIT("rc"),
    BENEFIT_GRANT("bg"),
    APPOINTMENT("a"),
}

class WaitlistPublicIdCodec {
    fun encode(scope: WaitlistTenantScope, kind: WaitlistPublicIdKind, internalId: Long): String {
        require(internalId > 0) { "internalId must be positive" }
        val payload = listOf(VERSION, scope.tenantGroupId, scope.clinicId, internalId).joinToString("|")
        return "${kind.prefix}_${ENCODER.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))}"
    }

    fun decode(scope: WaitlistTenantScope, expectedKind: WaitlistPublicIdKind, reference: String): Long {
        val trimmed = reference.trim()
        if (trimmed.isBlank()) {
            throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND)
        }
        val encodedPrefix = "${expectedKind.prefix}_"
        if (trimmed.startsWith(encodedPrefix)) {
            return decodeScoped(scope, trimmed.removePrefix(encodedPrefix))
        }
        val fixturePrefix = "${expectedKind.prefix}-"
        if (trimmed.startsWith(fixturePrefix)) {
            return trimmed.removePrefix(fixturePrefix)
                .takeIf(POSITIVE_LONG::matches)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND)
        }
        throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND)
    }

    private fun decodeScoped(scope: WaitlistTenantScope, encodedPayload: String): Long {
        val payload = try {
            DECODER.decode(encodedPayload).toString(StandardCharsets.UTF_8)
        } catch (ex: IllegalArgumentException) {
            throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND, ex)
        }
        val parts = payload.split("|")
        if (parts.size != 4 || parts[0] != VERSION) {
            throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND)
        }
        val tenantGroupId = parts[1].toLongOrNull()
        val clinicId = parts[2].toLongOrNull()
        val internalId = parts[3].toLongOrNull()
        if (tenantGroupId != scope.tenantGroupId || clinicId != scope.clinicId || internalId == null || internalId <= 0L) {
            throw WaitlistApiException(WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND)
        }
        return internalId
    }

    private companion object {
        private const val VERSION = "v1"
        private val POSITIVE_LONG = Regex("[1-9][0-9]*")
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}

object WaitlistIdempotencyKeys {
    private val PRINTABLE_ASCII = Regex("^[\\x21-\\x7E]{16,128}$")

    fun requireValid(value: String?): String =
        value?.takeIf(PRINTABLE_ASCII::matches)
            ?: throw WaitlistApiException(WaitlistApiError.INVALID_IDEMPOTENCY_KEY)
}
