package io.bluetape4k.clinic.appointment.api.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

/**
 * 신규 예약의 회원 ID 강제 단계와 병원별 한시 예외를 정의합니다.
 *
 * 기본값은 [MemberIdEnforcementMode.ENFORCE]입니다. 병원별 `OBSERVE` 예외는
 * 담당자와 만료 시각이 모두 있어야 하며, 만료된 예외는 자동으로 기본 단계로
 * 돌아갑니다.
 */
@ConfigurationProperties("appointment.notification")
data class NotificationMemberIdProperties(
    val memberIdEnforcement: MemberIdEnforcementMode = MemberIdEnforcementMode.ENFORCE,
    val memberIdOverrides: List<NotificationMemberIdOverride> = emptyList(),
) {
    init {
        require(
            memberIdOverrides
                .map { it.tenantGroupId to it.clinicId }
                .distinct()
                .size == memberIdOverrides.size,
        ) {
            "memberIdOverrides must be unique by tenantGroupId and clinicId"
        }
    }

    fun modeFor(
        tenantGroupId: Long,
        clinicId: Long,
        now: Instant,
    ): MemberIdEnforcementMode {
        val override = memberIdOverrides.singleOrNull {
            it.tenantGroupId == tenantGroupId && it.clinicId == clinicId
        }
        return if (override?.expiresAt?.let(now::isBefore) == true) {
            override.mode
        } else {
            memberIdEnforcement
        }
    }
}

/**
 * 회원 ID 필수화의 점진 배포 단계입니다.
 */
enum class MemberIdEnforcementMode {
    /** 누락된 legacy 요청을 기록만 하고 한시적으로 허용합니다. */
    OBSERVE,

    /** 회원 ID가 없는 신규 legacy 요청을 거절합니다. */
    ENFORCE,
}

/**
 * 병원 한 곳에만 적용하는 만료 가능한 `OBSERVE` 예외입니다.
 */
data class NotificationMemberIdOverride(
    val tenantGroupId: Long,
    val clinicId: Long,
    val mode: MemberIdEnforcementMode,
    val expiresAt: Instant?,
    val owner: String,
) {
    init {
        require(tenantGroupId > 0L) { "memberId override tenantGroupId must be positive" }
        require(clinicId > 0L) { "memberId override clinicId must be positive" }
        require(mode == MemberIdEnforcementMode.OBSERVE) {
            "memberId clinic overrides support OBSERVE only"
        }
        require(expiresAt != null) { "OBSERVE memberId override requires expiresAt" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "OBSERVE memberId override owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
    }

    private companion object {
        const val MAX_OWNER_LENGTH = 160
    }
}
