package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * waitlist delivery가 tenant와 clinic을 함께 제한하는 최소 scope입니다.
 */
data class ClinicWaitlistScope(
    val tenantGroupId: Long,
    val clinicId: Long,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * vacancy worker lease의 owner/version fencing snapshot입니다.
 */
data class VacancyLease(
    val owner: String,
    val version: Long,
    val expiresAt: Instant,
) : Serializable {
    init {
        owner.requireNotBlank("owner")
        require(version >= 0L) { "version must be zero or positive" }
    }

    fun isValid(owner: String, version: Long, now: Instant): Boolean =
        this.owner == owner && this.version == version && now < expiresAt

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * raw idempotency key를 저장하지 않는 waitlist command key입니다.
 */
data class WaitlistCommandKey(
    val tenantGroupId: Long,
    val clinicId: Long,
    val commandType: String,
    val keyDigest: String,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        commandType.requireNotBlank("commandType")
        require(HMAC_SHA256_DIGEST.matches(keyDigest)) {
            "keyDigest must be hmac-sha256 digest"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist delivery 전용 stable failure base type입니다. */
sealed class WaitlistDeliveryException(
    message: String,
) : RuntimeException(message), Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** vacancy generation CAS가 최신 상태와 충돌했습니다. */
class VacancyGenerationConflict : WaitlistDeliveryException("VACANCY_GENERATION_CONFLICT") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** command가 기대한 waitlist policy version과 현재 version이 다릅니다. */
class WaitlistPolicyConflict : WaitlistDeliveryException("POLICY_CONFLICT") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 같은 idempotency key에 다른 request digest가 관측됐습니다. */
class IdempotencyRequestMismatch : WaitlistDeliveryException("IDEMPOTENCY_REQUEST_MISMATCH") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** active vacancy 또는 command 선점 중 경쟁 상태가 감지됐습니다. */
class WaitlistContention : WaitlistDeliveryException("WAITLIST_CONTENTION") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private val HMAC_SHA256_DIGEST = Regex("^hmac-sha256:[a-f0-9]{64}$")
