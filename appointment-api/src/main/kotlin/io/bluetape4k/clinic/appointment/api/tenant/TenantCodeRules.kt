package io.bluetape4k.clinic.appointment.api.tenant

/**
 * HTTP tenant code와 JWT tenant grant가 공유하는 canonical slug 규칙이다.
 *
 * 외부 입력은 이 규칙으로 정규화하지 않고 그대로 검증한다. 따라서 대문자나 다른
 * 구분자를 소문자로 바꾸어 권한을 얻을 수 없으며, reserved API version root도 tenant
 * namespace로 재사용할 수 없다.
 */
internal object TenantCodeRules {
    private val CANONICAL = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val RESERVED_ROOTS = setOf("v1", "v2")

    fun isCanonical(value: String): Boolean =
        value.length <= MAX_LENGTH &&
            CANONICAL.matches(value) &&
            value !in RESERVED_ROOTS

    private const val MAX_LENGTH = 64
}
