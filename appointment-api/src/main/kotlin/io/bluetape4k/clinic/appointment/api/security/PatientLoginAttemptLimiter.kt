package io.bluetape4k.clinic.appointment.api.security

/**
 * 환자 login attempt rate limiting을 application 경계에 주입하기 위한 port입니다.
 *
 * 실제 제한은 gateway/edge 또는 bounded 외부 adapter가 소유해야 하며, API 프로세스 안에
 * unbounded identifier map을 만들지 않습니다.
 */
fun interface PatientLoginAttemptLimiter {
    /** 허용된 시도이면 `true`, 제한되었으면 `false`를 반환합니다. */
    fun allow(tenantGroupId: Long, identifierKey: String, clientFingerprint: String): Boolean

    companion object {
        /** profile 정책에 맞는 limiter를 선택하고 protected profile의 unsafe default를 차단합니다. */
        fun resolve(
            activeProfiles: Set<String>,
            configured: PatientLoginAttemptLimiter?,
        ): PatientLoginAttemptLimiter {
            configured?.let { return it }
            val isDevelopmentOrTestProfile = activeProfiles.any { it == "dev" || it == "test" }
            if (!isDevelopmentOrTestProfile) {
                throw IllegalStateException("A real PatientLoginAttemptLimiter is required in protected profiles")
            }
            return BoundedNoopPatientLoginAttemptLimiter
        }
    }
}

/** dev/test에서만 사용하는 상태 없는 bounded adapter입니다. */
object BoundedNoopPatientLoginAttemptLimiter : PatientLoginAttemptLimiter {
    override fun allow(tenantGroupId: Long, identifierKey: String, clientFingerprint: String): Boolean = true
}
