package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Gateway actor를 영속 Plan·appointment scope와 결합하는 server-side authorization 경계이다.
 *
 * request body의 Plan/appointment ID는 lookup key일 뿐 권위가 아니다. 모든 lookup은
 * Gateway가 선택한 정확한 tenant·clinic 범위를 반복하고, 고객 명령은 보호된 환자
 * fingerprint까지 비교한다. 이 resolver는 원문 환자 식별자나 fingerprint를 로그에
 * 남기지 않으며 실패 응답으로 다른 scope 자원의 존재 여부를 노출하지 않는다.
 *
 * Task 9의 [AppointmentCommitmentApplicationService] 구현은 정책·자원 inventory를
 * 조립하기 전에 이 resolver로 caller scope와 환자 소유권을 검증해야 한다.
 */
internal class AppointmentCommitmentAccessResolver(
    private val database: Database,
    private val patientSubjectFingerprintResolver: PatientSubjectFingerprintResolver,
    private val tenantGroupRepository: TenantGroupRepository = TenantGroupRepository(),
    private val appointmentPlanRepository: AppointmentPlanRepository = AppointmentPlanRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
) {

    /**
     * actor scope 안의 Plan을 조회하고 고객이면 같은 환자 fingerprint인지 검증한다.
     *
     * @throws AppointmentCommitmentApiException scope가 모호하거나 Plan이 다른 scope에
     * 있거나 고객 subject가 Plan 소유 환자와 다를 때 발생한다.
     */
    fun resolvePlan(
        actor: ActorContext,
        appointmentPlanId: Long,
    ): ResolvedAppointmentPlanAccess {
        val scope = resolveScope(actor)
        return transaction(database) {
            val plan = appointmentPlanRepository.findPlanByIdAndTenantClinic(
                appointmentPlanId,
                scope.tenantGroupId,
                scope.clinicId,
            ) ?: forbidden()
            requirePatientOwnership(actor, scope.tenantGroupId, plan.patientReferenceFingerprint)
            ResolvedAppointmentPlanAccess(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                plan = plan,
            )
        }
    }

    /**
     * 기존 commitment가 actor scope와 고객 소유권을 모두 만족하는지 검증한다.
     *
     * 관리자도 tenant·clinic 범위를 건너뛸 수 없고, 고객은 Plan ingress 때 사용한 것과
     * 같은 fingerprint domain으로 appointment 소유 환자를 다시 검증한다.
     */
    fun requireAppointmentAccess(
        actor: ActorContext,
        appointmentId: Long,
    ): ResolvedAppointmentAccess {
        val scope = resolveScope(actor)
        return transaction(database) {
            val appointmentFingerprint = appointmentRepository.findPatientReferenceFingerprint(
                appointmentId,
                scope.tenantGroupId,
                scope.clinicId,
            ) ?: forbidden()
            requirePatientOwnership(actor, scope.tenantGroupId, appointmentFingerprint)
            ResolvedAppointmentAccess(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                appointmentId = appointmentId,
                patientReferenceFingerprint = appointmentFingerprint,
            )
        }
    }

    /**
     * 취소 명령 전용 자원 접근을 검증한다.
     *
     * 일반 commitment 변경은 관리자와 환자만 수행하지만 취소는 운영 staff도
     * tenant·clinic 범위 안에서 수행할 수 있다. 따라서 기존 read/write resolver의
     * 정책을 넓히지 않고 취소 전용 ownership 경계를 별도로 둔다.
     */
    fun requireAppointmentCancellationAccess(
        actor: ActorContext,
        appointmentId: Long,
    ): ResolvedAppointmentAccess {
        val scope = resolveScope(actor)
        return transaction(database) {
            val appointmentFingerprint = appointmentRepository.findPatientReferenceFingerprint(
                appointmentId,
                scope.tenantGroupId,
                scope.clinicId,
            ) ?: forbidden()
            requireCancellationOwnership(actor, scope.tenantGroupId, appointmentFingerprint)
            ResolvedAppointmentAccess(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                appointmentId = appointmentId,
                patientReferenceFingerprint = appointmentFingerprint,
            )
        }
    }

    /**
     * 동의 authority가 actor의 정확한 tenant namespace에서 발행됐는지 검증한다.
     *
     * prefix 유사 tenant를 허용하지 않도록 `tenantCode:` 전체를 case-sensitive하게
     * 비교한다. 등록 authority와 원본 동의의 proposal·약관 검증은 Task 9 adapter가
     * 이어서 수행한다.
     */
    fun requireConsentAuthority(
        actor: ActorContext,
        evidenceAuthority: String,
    ) {
        val scope = resolveScope(actor)
        if (!evidenceAuthority.startsWith("${scope.tenantCode}:")) {
            forbidden()
        }
    }

    private fun resolveScope(actor: ActorContext): ResolvedActorScope {
        val tenantCode = actor.selectedTenantCode
            ?.takeIf(TenantCodeRules::isCanonical)
            ?.takeIf(actor.allowedTenantCodes::contains)
            ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_MISMATCH)
        val clinicId = actor.selectedClinicId
            ?.takeIf(actor.allowedClinicIds::contains)
            ?: throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_MISMATCH)
        val tenantGroupId = transaction(database) {
            tenantGroupRepository.findActiveByCode(tenantCode)?.id
        } ?: forbidden()
        return ResolvedActorScope(tenantCode, tenantGroupId, clinicId)
    }

    private fun requirePatientOwnership(
        actor: ActorContext,
        tenantGroupId: Long,
        expectedFingerprint: String,
    ) {
        when (actor.actorType) {
            ActorType.PATIENT -> {
                val patientSubjectId = actor.patientSubjectId
                    ?.takeIf(String::isNotBlank)
                    ?: forbidden()
                val actualFingerprint =
                    patientSubjectFingerprintResolver.fingerprint(tenantGroupId, patientSubjectId)
                if (!constantTimeEquals(expectedFingerprint, actualFingerprint)) {
                    forbidden()
                }
            }

            ActorType.ADMIN -> Unit
            ActorType.STAFF,
            ActorType.DOCTOR,
            ActorType.SYSTEM,
            -> forbidden()
        }
    }

    private fun requireCancellationOwnership(
        actor: ActorContext,
        tenantGroupId: Long,
        expectedFingerprint: String,
    ) {
        when (actor.actorType) {
            ActorType.ADMIN,
            ActorType.STAFF,
            -> Unit

            ActorType.PATIENT -> {
                val patientSubjectId = actor.patientSubjectId
                    ?.takeIf(String::isNotBlank)
                    ?: forbidden()
                val actualFingerprint =
                    patientSubjectFingerprintResolver.fingerprint(tenantGroupId, patientSubjectId)
                if (!constantTimeEquals(expectedFingerprint, actualFingerprint)) {
                    forbidden()
                }
            }

            ActorType.DOCTOR,
            ActorType.SYSTEM,
            -> forbidden()
        }
    }

    private fun constantTimeEquals(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )

    private fun forbidden(): Nothing =
        throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
}

/**
 * Gateway patient subject를 구매 Plan ingress와 동일한 tenant-scoped fingerprint로 변환한다.
 *
 * 구현체는 원문 subject를 저장하거나 로그에 남기지 않고, 구매 이벤트의
 * `patientReferenceFingerprint`와 동일한 key·algorithm·domain separation을 사용해야 한다.
 */
internal fun interface PatientSubjectFingerprintResolver {
    fun fingerprint(
        tenantGroupId: Long,
        patientSubjectId: String,
    ): String
}

/**
 * 구매 Plan ingress와 같은 HMAC fingerprint adapter가 연결되지 않은 환경의 보수적 기본값이다.
 *
 * Gateway subject를 일반 SHA-256으로 임의 변환하면 구매 ingress의 HMAC fingerprint와
 * 일치할 수 없고 배포자가 이를 정상 구성으로 오인할 수 있다. 따라서 patient actor의
 * 예약 접근만 명시적으로 거부하고 관리자 actor의 tenant·clinic 접근은 영향을 받지 않는다.
 */
internal class FailClosedPatientSubjectFingerprintResolver : PatientSubjectFingerprintResolver {
    override fun fingerprint(
        tenantGroupId: Long,
        patientSubjectId: String,
    ): String =
        throw AppointmentCommitmentApiException(
            AppointmentCommitmentApiError.SCOPE_FORBIDDEN,
            "patient subject fingerprint resolver is not configured",
        )
}

/** Plan command 조립에 사용할 검증 완료 tenant·clinic·Plan 묶음이다. */
internal data class ResolvedAppointmentPlanAccess(
    val tenantGroupId: Long,
    val clinicId: Long,
    val plan: AppointmentPlanRecord,
)

/** 기존 appointment command/query에 사용할 검증 완료 scope이다. */
internal data class ResolvedAppointmentAccess(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
    val patientReferenceFingerprint: String,
)

private data class ResolvedActorScope(
    val tenantCode: String,
    val tenantGroupId: Long,
    val clinicId: Long,
)
