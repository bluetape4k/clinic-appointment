package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.clinic.appointment.api.dto.ApprovalReferenceRequest
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Instant

/**
 * 수동 재알림 명령을 검증하고 outbox 재생성 port로 전달합니다.
 *
 * 이 서비스는 recipient, member profile 원문, provider payload를 다루지 않습니다. 현재
 * 회원·동의·template 적합성은 [NotificationReNotifyEligibilityPort]가 판단하고,
 * 새 generation/key와 enqueue 재개는 [NotificationReNotifyEnqueuePort]가 담당합니다.
 */
@Service
@ConditionalOnBean(
    NotificationReNotifyEligibilityPort::class,
    NotificationReNotifyEnqueuePort::class,
    NotificationReNotifyAuditSink::class,
    NotificationReNotifyApprovalVerifier::class,
    NotificationReNotifyRateLimiter::class,
)
class NotificationReNotifyService(
    private val eligibilityPort: NotificationReNotifyEligibilityPort,
    private val enqueuePort: NotificationReNotifyEnqueuePort,
    private val auditSink: NotificationReNotifyAuditSink,
    private val approvalVerifier: NotificationReNotifyApprovalVerifier,
    private val rateLimiter: NotificationReNotifyRateLimiter,
) {

    suspend fun reNotify(command: NotificationReNotifyCommand): NotificationReNotifyResult {
        val valid = command.validate()
        requirePlatformExecutor(valid.actor, valid.clinicId)
        val platformApproval = approvalVerifier.verifyPlatform(valid.actor, valid.platformApproval)
        requirePlatformApproval(valid.actor, valid.clinicId, platformApproval)
        val clinicApproval = approvalVerifier.verifyClinic(
            tenantGroupId = valid.tenantGroupId,
            clinicId = valid.clinicId,
            reference = valid.clinicApproval,
        )
        requireClinicApproval(valid.actor, valid.clinicId, clinicApproval)

        auditSink.record(
            NotificationReNotifyAuditEvent.started(
                generation = valid.generation,
                tenantGroupId = valid.tenantGroupId,
                clinicId = valid.clinicId,
                executorRef = platformApproval.subjectReference,
                clinicApproverRef = clinicApproval.subjectReference,
                requestedCount = valid.appointmentIds.size,
                dryRun = valid.dryRun,
            )
        )

        try {
            val decisions = eligibilityPort.evaluate(valid)
            requireCompleteEligibilityCoverage(valid, decisions)
            val normalized = decisions.map(::applyDefaultExclusions)
            val accepted = normalized.filter { it.decision == NotificationReNotifyDecision.ACCEPT }
            val skippedReasons = normalized
                .filterNot { it.decision == NotificationReNotifyDecision.ACCEPT }
                .groupingBy { it.reasonCode ?: RE_NOTIFY_SKIP_UNKNOWN }
                .eachCount()

            if (!valid.dryRun && accepted.isNotEmpty()) {
                rateLimiter.acquire(
                    NotificationReNotifyRateLimitRequest(
                        tenantGroupId = valid.tenantGroupId,
                        clinicId = valid.clinicId,
                        providerKeys = accepted.mapTo(sortedSetOf()) { it.providerKey },
                        requestedCount = accepted.size,
                    )
                )
            }

            val enqueued = if (valid.dryRun || accepted.isEmpty()) {
                NotificationReNotifyEnqueueResult(
                    generation = valid.generation,
                    acceptedCount = accepted.size,
                    resumed = false,
                )
            } else {
                enqueuePort.enqueue(valid, accepted)
            }
            require(enqueued.generation == valid.generation) {
                "enqueue result generation must match command generation"
            }
            require(enqueued.acceptedCount == accepted.size) {
                "enqueue result acceptedCount must match accepted decisions"
            }

            val result = NotificationReNotifyResult(
                generation = enqueued.generation,
                dryRun = valid.dryRun,
                requestedCount = valid.appointmentIds.size,
                acceptedCount = enqueued.acceptedCount,
                skippedCount = valid.appointmentIds.size - accepted.size,
                skippedReasons = skippedReasons.toSortedMap(),
            )
            auditSink.record(
                NotificationReNotifyAuditEvent.completed(
                    generation = result.generation,
                    tenantGroupId = valid.tenantGroupId,
                    clinicId = valid.clinicId,
                    executorRef = platformApproval.subjectReference,
                    clinicApproverRef = clinicApproval.subjectReference,
                    acceptedCount = result.acceptedCount,
                    skippedCount = result.skippedCount,
                    dryRun = result.dryRun,
                )
            )
            log.info {
                "수동 재알림 처리 완료: generation=${result.generation}, dryRun=${result.dryRun}, " +
                    "accepted=${result.acceptedCount}, skipped=${result.skippedCount}"
            }
            return result
        } catch (e: CancellationException) {
            recordInterrupted(valid, platformApproval, clinicApproval)
            throw e
        } catch (e: Exception) {
            recordInterrupted(valid, platformApproval, clinicApproval)
            log.warn {
                "수동 재알림 처리 중단: generation=${valid.generation}, failureType=${e::class.simpleName}"
            }
            throw e
        }
    }

    private suspend fun recordInterrupted(
        command: NotificationReNotifyCommand,
        platformApproval: VerifiedNotificationApproval,
        clinicApproval: VerifiedNotificationApproval,
    ) {
        withContext(NonCancellable) {
            try {
                auditSink.record(
                    NotificationReNotifyAuditEvent.interrupted(
                        generation = command.generation,
                        tenantGroupId = command.tenantGroupId,
                        clinicId = command.clinicId,
                        executorRef = platformApproval.subjectReference,
                        clinicApproverRef = clinicApproval.subjectReference,
                        dryRun = command.dryRun,
                    )
                )
            } catch (auditFailure: Exception) {
                log.warn {
                    "수동 재알림 중단 감사 기록 실패: failureType=${auditFailure::class.simpleName}"
                }
            }
        }
    }

    private fun requireCompleteEligibilityCoverage(
        command: NotificationReNotifyCommand,
        decisions: List<NotificationReNotifyEligibility>,
    ) {
        val decisionIds = decisions.map { it.appointmentId }
        require(decisionIds.size == decisionIds.toSet().size) {
            "eligibility decisions must contain unique appointment IDs"
        }
        require(decisionIds.toSet() == command.appointmentIds) {
            "eligibility decisions must cover exactly the requested appointments"
        }
    }

    private fun applyDefaultExclusions(
        eligibility: NotificationReNotifyEligibility,
    ): NotificationReNotifyEligibility {
        if (eligibility.decision == NotificationReNotifyDecision.SKIP) {
            return eligibility
        }
        val reason = when {
            eligibility.currentStatus in DEFAULT_RE_NOTIFY_EXCLUDED_STATUSES -> RE_NOTIFY_SKIP_SENT
            eligibility.failureCode in DEFAULT_RE_NOTIFY_EXCLUDED_FAILURE_CODES ->
                RE_NOTIFY_SKIP_DELIVERY_RESULT_UNKNOWN
            eligibility.suppressionReason != null -> "SUPPRESSED_${eligibility.suppressionReason.name}"
            !eligibility.profileEligible -> RE_NOTIFY_SKIP_PROFILE_INELIGIBLE
            !eligibility.consentEligible -> RE_NOTIFY_SKIP_CONSENT_INELIGIBLE
            !eligibility.templateEligible -> RE_NOTIFY_SKIP_TEMPLATE_INELIGIBLE
            else -> null
        }
        return reason?.let { NotificationReNotifyEligibility.skip(eligibility.appointmentId, it) } ?: eligibility
    }

    private fun requirePlatformApproval(
        actor: SchedulingUserPrincipal,
        clinicId: Long,
        approval: VerifiedNotificationApproval,
    ) {
        val valid = approval.subjectReference == actor.userId &&
            approval.actorType == ActorType.SYSTEM &&
            approval.assurance == AuthenticationAssurance.SERVICE &&
            approval.roles == setOf(SchedulingRole.SYSTEM) &&
            clinicId in approval.allowedClinicIds
        if (!valid) {
            throw AccessDeniedException("platform approval is not bound to the service executor and clinic")
        }
    }

    private fun requireClinicApproval(
        actor: SchedulingUserPrincipal,
        clinicId: Long,
        approval: VerifiedNotificationApproval,
    ) {
        val humanRoles = setOf(SchedulingRole.ADMIN, SchedulingRole.STAFF)
        val hasHumanRole = approval.roles.any { it in humanRoles }
        val valid = approval.subjectReference != actor.userId &&
            approval.actorType in setOf(ActorType.ADMIN, ActorType.STAFF) &&
            approval.assurance == AuthenticationAssurance.MFA &&
            hasHumanRole &&
            approval.roles.all { it in humanRoles } &&
            clinicId in approval.allowedClinicIds
        if (!valid) {
            throw AccessDeniedException("independent MFA clinic approver with exact clinic membership is required")
        }
    }

    companion object : KLogging() {
        const val MAX_RE_NOTIFY_APPOINTMENTS = 100
        private const val MAX_REFERENCE_LENGTH = 128
        private const val RE_NOTIFY_SKIP_UNKNOWN = "UNKNOWN"
        private const val RE_NOTIFY_SKIP_SENT = "SENT"
        private const val RE_NOTIFY_SKIP_DELIVERY_RESULT_UNKNOWN = "DELIVERY_RESULT_UNKNOWN"
        private const val RE_NOTIFY_SKIP_PROFILE_INELIGIBLE = "PROFILE_INELIGIBLE"
        private const val RE_NOTIFY_SKIP_CONSENT_INELIGIBLE = "CONSENT_INELIGIBLE"
        private const val RE_NOTIFY_SKIP_TEMPLATE_INELIGIBLE = "TEMPLATE_INELIGIBLE"
        private val SAFE_REFERENCE = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    }

    private fun NotificationReNotifyCommand.validate(): NotificationReNotifyCommand {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        require(appointmentIds.isNotEmpty()) { "appointmentIds must not be empty" }
        require(appointmentIds.size <= MAX_RE_NOTIFY_APPOINTMENTS) {
            "appointmentIds must not exceed $MAX_RE_NOTIFY_APPOINTMENTS"
        }
        appointmentIds.forEach { it.requirePositiveNumber("appointmentId") }
        require(appointmentIds.size == appointmentIds.toSet().size) { "appointmentIds must be unique" }
        generation.requireNotBlank("generation")
        require(SAFE_REFERENCE.matches(generation) && generation.length <= MAX_REFERENCE_LENGTH) {
            "generation is invalid"
        }
        platformApproval.validate("platformApproval")
        clinicApproval.validate("clinicApproval")
        return copy(appointmentIds = appointmentIds.toSet())
    }

    private fun ApprovalReference.validate(name: String) {
        authority.requireNotBlank("$name.authority")
        reference.requireNotBlank("$name.reference")
        require(authority.length <= MAX_REFERENCE_LENGTH && SAFE_REFERENCE.matches(authority)) {
            "$name.authority is invalid"
        }
        require(reference.length <= MAX_REFERENCE_LENGTH && SAFE_REFERENCE.matches(reference)) {
            "$name.reference is invalid"
        }
    }

    private fun requirePlatformExecutor(actor: SchedulingUserPrincipal, clinicId: Long) {
        val platformExecutor = actor.actorType == ActorType.SYSTEM &&
            actor.assurance == AuthenticationAssurance.SERVICE &&
            actor.roles == setOf(SchedulingRole.SYSTEM) &&
            "notification:renotify" in actor.scopes &&
            clinicId in actor.allowedClinicIds
        if (!platformExecutor) {
            throw AccessDeniedException("platform service executor with exact clinic membership is required")
        }
    }

}

/** 검증된 플랫폼 실행자와 승인 참조를 포함한 재알림 명령입니다. */
data class NotificationReNotifyCommand(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentIds: Set<Long>,
    val generation: String,
    val platformApproval: ApprovalReference,
    val clinicApproval: ApprovalReference,
    val dryRun: Boolean,
    val actor: SchedulingUserPrincipal,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 승인 시스템에서 조회할 수 있는 비식별 참조입니다. */
data class ApprovalReference(
    val authority: String,
    val reference: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 전송 DTO를 서비스 계층의 승인 참조로 변환합니다. */
fun ApprovalReferenceRequest.toApprovalReference(): ApprovalReference =
    ApprovalReference(authority = authority, reference = reference)

/** 현재 회원 프로필, 동의, template, 기존 발송 결과를 다시 평가하는 port입니다. */
fun interface NotificationReNotifyEligibilityPort {
    suspend fun evaluate(command: NotificationReNotifyCommand): List<NotificationReNotifyEligibility>
}

/** 새 generation의 outbox를 생성하거나 같은 generation 작업을 재개하는 port입니다. */
fun interface NotificationReNotifyEnqueuePort {
    suspend fun enqueue(
        command: NotificationReNotifyCommand,
        accepted: List<NotificationReNotifyEligibility>,
    ): NotificationReNotifyEnqueueResult
}

/** 예약 ID 원문 없이 재알림 실행 단계를 기록하는 감사 port입니다. */
fun interface NotificationReNotifyAuditSink {
    suspend fun record(event: NotificationReNotifyAuditEvent)
}

/**
 * 플랫폼 실행 승인과 병원 담당자 승인을 각각 검증합니다.
 *
 * 요청의 `authority` 문자열을 직접 신뢰하지 않고 외부 승인 저장소에서 검증한 identity 증거를
 * 반환해야 합니다.
 */
interface NotificationReNotifyApprovalVerifier {
    suspend fun verifyPlatform(
        executor: SchedulingUserPrincipal,
        reference: ApprovalReference,
    ): VerifiedNotificationApproval

    suspend fun verifyClinic(
        tenantGroupId: Long,
        clinicId: Long,
        reference: ApprovalReference,
    ): VerifiedNotificationApproval
}

/** clinic과 provider 범주별 재알림 처리량을 함께 제한하는 port입니다. */
fun interface NotificationReNotifyRateLimiter {
    suspend fun acquire(request: NotificationReNotifyRateLimitRequest)
}

/** 예약 한 건의 현재 재알림 적합성입니다. */
data class NotificationReNotifyEligibility(
    val appointmentId: Long,
    val decision: NotificationReNotifyDecision,
    val reasonCode: String? = null,
    val currentStatus: NotificationOutboxStatus = NotificationOutboxStatus.PENDING,
    val failureCode: NotificationFailureCode? = null,
    val suppressionReason: NotificationSuppressionReasonCode? = null,
    val profileEligible: Boolean = true,
    val consentEligible: Boolean = true,
    val templateEligible: Boolean = true,
    val providerKey: String = "default",
) : Serializable {
    init {
        appointmentId.requirePositiveNumber("appointmentId")
        require(decision == NotificationReNotifyDecision.ACCEPT || !reasonCode.isNullOrBlank()) {
            "reasonCode is required for skipped re-notify decisions"
        }
        providerKey.requireNotBlank("providerKey")
        require(SAFE_PROVIDER_KEY.matches(providerKey)) { "providerKey is invalid" }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val SAFE_PROVIDER_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

        fun accept(appointmentId: Long): NotificationReNotifyEligibility =
            NotificationReNotifyEligibility(appointmentId, NotificationReNotifyDecision.ACCEPT)

        fun accept(
            appointmentId: Long,
            currentStatus: NotificationOutboxStatus,
            failureCode: NotificationFailureCode? = null,
            suppressionReason: NotificationSuppressionReasonCode? = null,
            profileEligible: Boolean = true,
            consentEligible: Boolean = true,
            templateEligible: Boolean = true,
            providerKey: String = "default",
        ): NotificationReNotifyEligibility =
            NotificationReNotifyEligibility(
                appointmentId = appointmentId,
                decision = NotificationReNotifyDecision.ACCEPT,
                currentStatus = currentStatus,
                failureCode = failureCode,
                suppressionReason = suppressionReason,
                profileEligible = profileEligible,
                consentEligible = consentEligible,
                templateEligible = templateEligible,
                providerKey = providerKey,
            )

        fun skip(appointmentId: Long, reasonCode: String): NotificationReNotifyEligibility =
            NotificationReNotifyEligibility(appointmentId, NotificationReNotifyDecision.SKIP, reasonCode)
    }
}

/** 현재 정보 재평가 결과입니다. */
enum class NotificationReNotifyDecision {
    ACCEPT,
    SKIP,
}

/** enqueue 또는 재개 결과의 비식별 집계입니다. */
data class NotificationReNotifyEnqueueResult(
    val generation: String,
    val acceptedCount: Int,
    val resumed: Boolean,
) : Serializable {
    init {
        generation.requireNotBlank("generation")
        require(acceptedCount >= 0) { "acceptedCount must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 승인 저장소가 검증한 실행자 또는 승인자 identity 증거입니다. */
data class VerifiedNotificationApproval(
    val subjectReference: String,
    val actorType: ActorType,
    val assurance: AuthenticationAssurance,
    val roles: Set<String>,
    val allowedClinicIds: Set<Long>,
) : Serializable {
    init {
        subjectReference.requireNotBlank("subjectReference")
        require(subjectReference.length <= MAX_SUBJECT_REFERENCE_LENGTH) {
            "subjectReference must not exceed $MAX_SUBJECT_REFERENCE_LENGTH characters"
        }
        require(SAFE_SUBJECT_REFERENCE.matches(subjectReference)) { "subjectReference is invalid" }
        require(roles.isNotEmpty()) { "roles must not be empty" }
        require(roles.size <= MAX_ROLES) { "roles must not exceed $MAX_ROLES entries" }
        require(allowedClinicIds.size <= MAX_ALLOWED_CLINICS) {
            "allowedClinicIds must not exceed $MAX_ALLOWED_CLINICS entries"
        }
        allowedClinicIds.forEach { it.requirePositiveNumber("allowedClinicId") }
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_SUBJECT_REFERENCE_LENGTH = 128
        private const val MAX_ROLES = 5
        private const val MAX_ALLOWED_CLINICS = 100
        private val SAFE_SUBJECT_REFERENCE = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    }
}

/** clinic과 provider 범주를 결합한 rate-limit 요청입니다. */
data class NotificationReNotifyRateLimitRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val providerKeys: Set<String>,
    val requestedCount: Int,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        require(requestedCount in 1..NotificationReNotifyService.MAX_RE_NOTIFY_APPOINTMENTS) {
            "requestedCount must be between 1 and ${NotificationReNotifyService.MAX_RE_NOTIFY_APPOINTMENTS}"
        }
        require(providerKeys.isNotEmpty()) { "providerKeys must not be empty" }
        providerKeys.forEach {
            it.requireNotBlank("providerKey")
            require(SAFE_PROVIDER_KEY.matches(it)) { "providerKey is invalid" }
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val SAFE_PROVIDER_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
    }
}

/** 재알림 실행 결과의 비식별 집계입니다. */
data class NotificationReNotifyResult(
    val generation: String,
    val dryRun: Boolean,
    val requestedCount: Int,
    val acceptedCount: Int,
    val skippedCount: Int,
    val skippedReasons: Map<String, Int>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 원시 예약 ID를 남기지 않는 수동 재알림 감사 이벤트입니다.
 */
data class NotificationReNotifyAuditEvent(
    val generation: String,
    val phase: NotificationReNotifyAuditPhase,
    val tenantGroupId: Long,
    val clinicId: Long,
    val executorRef: String,
    val clinicApproverRef: String,
    val requestedCount: Int? = null,
    val acceptedCount: Int? = null,
    val skippedCount: Int? = null,
    val dryRun: Boolean,
    val occurredAt: Instant = Instant.now(),
) : Serializable {
    override fun toString(): String =
        "NotificationReNotifyAuditEvent(generation=$generation, phase=$phase, scope=<redacted>, dryRun=$dryRun)"

    companion object {
        private const val serialVersionUID = 1L

        fun started(
            generation: String,
            tenantGroupId: Long,
            clinicId: Long,
            executorRef: String,
            clinicApproverRef: String,
            requestedCount: Int,
            dryRun: Boolean,
        ): NotificationReNotifyAuditEvent =
            NotificationReNotifyAuditEvent(
                generation = generation,
                phase = NotificationReNotifyAuditPhase.STARTED,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                executorRef = executorRef,
                clinicApproverRef = clinicApproverRef,
                requestedCount = requestedCount,
                dryRun = dryRun,
            )

        fun completed(
            generation: String,
            tenantGroupId: Long,
            clinicId: Long,
            executorRef: String,
            clinicApproverRef: String,
            acceptedCount: Int,
            skippedCount: Int,
            dryRun: Boolean,
        ): NotificationReNotifyAuditEvent =
            NotificationReNotifyAuditEvent(
                generation = generation,
                phase = NotificationReNotifyAuditPhase.COMPLETED,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                executorRef = executorRef,
                clinicApproverRef = clinicApproverRef,
                acceptedCount = acceptedCount,
                skippedCount = skippedCount,
                dryRun = dryRun,
            )

        fun interrupted(
            generation: String,
            tenantGroupId: Long,
            clinicId: Long,
            executorRef: String,
            clinicApproverRef: String,
            dryRun: Boolean,
        ): NotificationReNotifyAuditEvent =
            NotificationReNotifyAuditEvent(
                generation = generation,
                phase = NotificationReNotifyAuditPhase.INTERRUPTED,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                executorRef = executorRef,
                clinicApproverRef = clinicApproverRef,
                dryRun = dryRun,
            )
    }
}

/** 감사 저장소에 남기는 재알림 실행 단계입니다. */
enum class NotificationReNotifyAuditPhase {
    STARTED,
    INTERRUPTED,
    COMPLETED,
}

/** 별도 override 없이 재알림하지 않는 완료 상태입니다. */
val DEFAULT_RE_NOTIFY_EXCLUDED_STATUSES: Set<NotificationOutboxStatus> =
    setOf(NotificationOutboxStatus.SENT)

/** 발송 여부가 불명확해 중복 발송 위험이 있는 기본 제외 실패 코드입니다. */
val DEFAULT_RE_NOTIFY_EXCLUDED_FAILURE_CODES: Set<NotificationFailureCode> =
    setOf(NotificationFailureCode.DELIVERY_RESULT_UNKNOWN)
