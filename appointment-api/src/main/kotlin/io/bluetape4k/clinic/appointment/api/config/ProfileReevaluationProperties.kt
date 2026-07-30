package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationMutationMode
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRuntimeAccess
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationTargets
import io.bluetape4k.clinic.appointment.service.ProfileReevaluationTargetResolver
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * 프로필 변경 예약 재평가의 플랫폼 기본값과 점진 배포 경계를 정의합니다.
 *
 * 기본 상태는 비활성입니다. 기능을 활성화해도 빈 병원 허용 목록은 전체 허용이 아니라
 * 적용 대상 없음으로 해석합니다. 처리 목표는 병원 정책, 테넌트 정책, 이 설정 순서로
 * 해석하며 기존 작업의 처리 시각은 뒤로 미루지 않습니다.
 */
@ConfigurationProperties("appointment.profile-reevaluation")
data class ProfileReevaluationProperties(
    val enabled: Boolean = false,
    val mutationMode: ProfileReevaluationMutationMode = ProfileReevaluationMutationMode.DRY_RUN,
    val clinicAllowlist: Set<Long> = emptySet(),
    val heldTarget: Duration = Duration.ofMinutes(5),
    val proposedTarget: Duration = Duration.ofMinutes(30),
    val globalConcurrency: Int = 8,
    val perClinicConcurrency: Int = 2,
    val pageSize: Int = 50,
    val maxAppointmentsPerTick: Int = 100,
    val pollInterval: Duration = Duration.ofSeconds(1),
    val leaseDuration: Duration = Duration.ofSeconds(30),
    val leaseRenewInterval: Duration = Duration.ofSeconds(10),
    val retryMaxAttempts: Int = 5,
    val retryMaxElapsedTime: Duration = Duration.ofMinutes(15),
    val retryInitialBackoff: Duration = Duration.ofSeconds(2),
    val retryMaxBackoff: Duration = Duration.ofMinutes(1),
    val retryJitter: Double = 0.2,
    val autoRedriveMax: Int = 2,
    val autoRedriveCooldown: Duration = Duration.ofMinutes(30),
    val assessment: ProfileAssessmentProperties = ProfileAssessmentProperties(),
) {
    init {
        require(clinicAllowlist.all { it > 0L }) {
            "clinicAllowlist must contain only positive clinic IDs"
        }
        targetFor(AppointmentCommitmentStatus.HELD)
        targetFor(AppointmentCommitmentStatus.PROPOSED)
        require(globalConcurrency in 1..64) { "globalConcurrency must be between 1 and 64" }
        require(perClinicConcurrency in 1..globalConcurrency) {
            "perClinicConcurrency must be between 1 and globalConcurrency"
        }
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        require(maxAppointmentsPerTick in 1..10_000) {
            "maxAppointmentsPerTick must be between 1 and 10000"
        }
        require(pollInterval.isPositive() && pollInterval <= Duration.ofMinutes(1)) {
            "pollInterval must be positive and at most one minute"
        }
        require(leaseDuration.isPositive() && leaseDuration <= Duration.ofMinutes(10)) {
            "leaseDuration must be positive and at most ten minutes"
        }
        require(leaseRenewInterval.isPositive() && leaseRenewInterval < leaseDuration) {
            "leaseRenewInterval must be positive and shorter than leaseDuration"
        }
        require(retryMaxAttempts in 1..20) { "retryMaxAttempts must be between 1 and 20" }
        require(retryMaxElapsedTime.isPositive()) { "retryMaxElapsedTime must be positive" }
        require(retryInitialBackoff.isPositive() && retryInitialBackoff <= retryMaxBackoff) {
            "retryInitialBackoff must be positive and not exceed retryMaxBackoff"
        }
        require(retryMaxBackoff.isPositive()) { "retryMaxBackoff must be positive" }
        require(retryJitter in 0.0..1.0) { "retryJitter must be between 0 and 1" }
        require(autoRedriveMax in 0..2) { "autoRedriveMax must be between 0 and 2" }
        require(autoRedriveCooldown.isPositive()) { "autoRedriveCooldown must be positive" }
        if (enabled && mutationMode != ProfileReevaluationMutationMode.DISABLED) {
            assessment.requireUsableEndpoint()
        }
    }

    fun platformTargets(): ProfileReevaluationTargets =
        ProfileReevaluationTargets(heldTarget = heldTarget, proposedTarget = proposedTarget)

    fun targetFor(status: AppointmentCommitmentStatus): Duration =
        ProfileReevaluationTargetResolver.resolve(status, platformTargets())

    /**
     * 현재 프로세스에서 즉시 다시 읽을 수 있는 fail-closed 실행 접근권을 만듭니다.
     */
    fun runtimeAccess(): ProfileReevaluationRuntimeAccess =
        if (!enabled || mutationMode == ProfileReevaluationMutationMode.DISABLED) {
            ProfileReevaluationRuntimeAccess.disabled()
        } else {
            ProfileReevaluationRuntimeAccess.enabled(
                mode = mutationMode,
                allowedClinicIds = clinicAllowlist,
            )
        }
}

data class ProfileAssessmentProperties(
    val baseUrl: URI? = null,
    val allowedHosts: Set<String> = emptySet(),
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(3),
    val maxResponseBytes: Int = 64 * 1024,
    val maxConcurrency: Int = 8,
) {
    init {
        require(allowedHosts.all { it.isNotBlank() && it == it.lowercase() }) {
            "assessment allowedHosts must contain normalized host names"
        }
        require(connectTimeout.isPositive() && readTimeout.isPositive()) {
            "assessment timeouts must be positive"
        }
        require(maxResponseBytes in 1..1_048_576) {
            "assessment maxResponseBytes must be between 1 and 1048576"
        }
        require(maxConcurrency in 1..64) { "assessment maxConcurrency must be between 1 and 64" }
    }

    fun requireUsableEndpoint(): URI {
        val endpoint = requireNotNull(baseUrl) {
            "assessment baseUrl is required when profile reevaluation is enabled"
        }
        require(endpoint.scheme.equals("https", ignoreCase = true)) {
            "assessment baseUrl must use HTTPS"
        }
        require(endpoint.userInfo == null && endpoint.fragment == null && endpoint.host != null) {
            "assessment baseUrl must contain a fixed host without user-info or fragment"
        }
        require(endpoint.host.lowercase() in allowedHosts) {
            "assessment baseUrl host must be present in assessment allowedHosts"
        }
        return endpoint
    }
}
