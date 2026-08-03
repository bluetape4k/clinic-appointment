package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClaimWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/**
 * waitlist offer와 replacement appointment command를 application boundary에서 조정합니다.
 *
 * confirm은 세 단계로 나뉩니다.
 * 1. idempotency reservation을 짧은 transaction으로 durable commit합니다.
 * 2. claim, replacement appointment 생성, hold consume을 하나의 business transaction으로 실행합니다.
 * 3. business 결과를 별도 transaction으로 stable success/failure replay에 기록합니다.
 */
class WaitlistApplicationService(
    private val database: Database,
    private val commandReservationPort: WaitlistCommandReservationPort,
    private val claimPort: WaitlistOfferClaimPort,
    private val replacementPlanner: WaitlistReplacementCommandPlanner,
    private val replacementPort: WaitlistReplacementAppointmentPort,
    private val holdConsumptionPort: WaitlistCapacityHoldConsumptionPort,
    private val cancellationPort: WaitlistCancellationPort,
    private val vacancyOpeningPort: WaitlistVacancyOpeningPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 하나의 unexpired offer를 정확히 한 replacement appointment로 확정합니다.
     */
    fun confirmOffer(command: WaitlistOfferConfirmationCommand): WaitlistOfferConfirmationResult {
        val reservedAt = clock.instant()
        return when (
            val reservation = transaction(database) {
                commandReservationPort.reserve(command, reservedAt)
            }
        ) {
            is WaitlistApplicationReservation.Acquired -> confirmAcquired(command, reservation.recordId)
            WaitlistApplicationReservation.InProgress -> replayReconciled(command)

            is WaitlistApplicationReservation.ReplaySucceeded ->
                WaitlistOfferConfirmationResult(
                    appointmentId = reservation.appointmentId,
                    idempotentReplay = true,
                )

            is WaitlistApplicationReservation.ReplayFailed ->
                throw WaitlistApplicationConflict(
                    code = WaitlistApplicationError.IDEMPOTENT_FAILURE_REPLAY,
                    message = reservation.failureCode,
                )
        }
    }

    /**
     * appointment cancellation과 vacancy job 생성을 한 transaction 안에서 실행합니다.
     */
    fun cancelAndOpenVacancy(command: WaitlistCancellationVacancyCommand): WaitlistVacancyResult =
        transaction(database) {
            val cancelled = cancellationPort.cancel(command)
            val opened = vacancyOpeningPort.open(cancelled)
            WaitlistVacancyResult(
                appointmentId = cancelled.appointmentId,
                vacancyJobId = opened.vacancyJobId,
            )
        }

    private fun confirmAcquired(
        command: WaitlistOfferConfirmationCommand,
        recordId: Long,
    ): WaitlistOfferConfirmationResult {
        val replacement =
            try {
                transaction(database) {
                    val now = clock.instant()
                    val claim = claimPort.claim(
                        ClaimWaitlistOfferCommand(
                            offerId = command.offerId,
                            scope = command.scope(),
                            expectedVersion = command.expectedOfferVersion,
                            correlationId = command.correlationId,
                            actorRef = command.actorRef,
                        ),
                    )
                    val plan = replacementPlanner.plan(command, claim)
                    val confirmed = replacementPort.confirm(plan)
                    holdConsumptionPort.consume(command.scope(), claim.holdId, now)
                    confirmed
                }
            } catch (failure: RuntimeException) {
                val failureCode = stableFailureCode(failure)
                transaction(database) {
                    commandReservationPort.completeFailed(recordId, command, failureCode, clock.instant())
                }
                log.warn(failure) {
                    "Waitlist offer confirmation failed: tenantGroupId=${command.tenantGroupId}, " +
                        "clinicId=${command.clinicId}, offerId=${command.offerId}, failureCode=$failureCode"
                }
                throw failure
            }

        transaction(database) {
            commandReservationPort.completeSucceeded(
                recordId = recordId,
                command = command,
                appointmentId = replacement.appointmentId,
                now = clock.instant(),
            )
        }
        log.info {
            "Waitlist offer confirmed: tenantGroupId=${command.tenantGroupId}, clinicId=${command.clinicId}, " +
                "offerId=${command.offerId}, appointmentId=${replacement.appointmentId}, " +
                "correlationId=${command.correlationId.value}"
        }
        return WaitlistOfferConfirmationResult(replacement.appointmentId, idempotentReplay = false)
    }

    private fun stableFailureCode(failure: RuntimeException): String =
        when (failure) {
            is WaitlistApplicationConflict -> failure.code.name
            else -> "WAITLIST_CONFIRM_FAILED"
        }

    private fun replayReconciled(command: WaitlistOfferConfirmationCommand): WaitlistOfferConfirmationResult =
        when (
            val reconciled = transaction(database) {
                commandReservationPort.reconcileInProgress(command, clock.instant())
            }
        ) {
            is WaitlistApplicationReservation.ReplaySucceeded ->
                WaitlistOfferConfirmationResult(reconciled.appointmentId, idempotentReplay = true)

            is WaitlistApplicationReservation.ReplayFailed ->
                throw WaitlistApplicationConflict(
                    code = WaitlistApplicationError.IDEMPOTENT_FAILURE_REPLAY,
                    message = reconciled.failureCode,
                )

            WaitlistApplicationReservation.InProgress,
            is WaitlistApplicationReservation.Acquired,
            -> throw WaitlistApplicationConflict(
                code = WaitlistApplicationError.IDEMPOTENCY_IN_PROGRESS,
                message = "waitlist command is already processing",
            )
        }

    private companion object : KLogging()
}

/** staff가 offer 확정을 요청할 때 application service에 전달하는 bounded command입니다. */
data class WaitlistOfferConfirmationCommand(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val offerId: Long,
    val expectedOfferVersion: Long,
    val confirmationSource: String? = null,
    val idempotencyKeyDigest: String,
    val requestDigest: String,
    val responseDigest: String,
    val correlationId: CorrelationId,
    val actorRef: ActorRef,
) : Serializable {
    constructor(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        offerId: Long,
        expectedOfferVersion: Long,
        idempotencyKeyDigest: String,
        requestDigest: String,
        responseDigest: String,
        correlationId: CorrelationId,
        actorRef: ActorRef,
    ) : this(
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        memberId = memberId,
        offerId = offerId,
        expectedOfferVersion = expectedOfferVersion,
        confirmationSource = null,
        idempotencyKeyDigest = idempotencyKeyDigest,
        requestDigest = requestDigest,
        responseDigest = responseDigest,
        correlationId = correlationId,
        actorRef = actorRef,
    )

    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        memberId.value.requireNotBlank("memberId")
        offerId.requirePositiveNumber("offerId")
        require(expectedOfferVersion >= 0L) { "expectedOfferVersion must be zero or positive" }
        confirmationSource?.let {
            require(it.isNotBlank() && it.length <= 64) { "confirmationSource must contain 1..64 characters" }
        }
        require(idempotencyKeyDigest.startsWith("hmac-sha256:")) {
            "idempotencyKeyDigest must be a waitlist HMAC digest"
        }
        require(SHA256.matches(requestDigest)) { "requestDigest must be lowercase SHA-256" }
        require(SHA256.matches(responseDigest)) { "responseDigest must be lowercase SHA-256" }
    }

    fun scope(): WaitlistScope =
        WaitlistScope(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            memberId = memberId,
        )

    fun commandKey(): WaitlistCommandKey =
        WaitlistCommandKey(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            commandType = COMMAND_TYPE_CONFIRM_OFFER,
            keyDigest = idempotencyKeyDigest,
        )

    val expectedVersion: Long
        get() = expectedOfferVersion

    companion object {
        private const val serialVersionUID = 1L
        private val SHA256 = Regex("^[a-f0-9]{64}$")

        fun from(
            scope: TenantScope,
            memberId: MemberId,
            offerId: Long,
            idempotencyKeyDigest: String,
            command: ConfirmWaitlistOfferCommand,
            responseDigest: String,
        ): WaitlistOfferConfirmationCommand =
            WaitlistOfferConfirmationCommand(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                memberId = memberId,
                offerId = offerId,
                expectedOfferVersion = command.expectedVersion,
                confirmationSource = command.confirmationSource,
                idempotencyKeyDigest = idempotencyKeyDigest,
                requestDigest = command.requestDigest,
                responseDigest = responseDigest,
                correlationId = CorrelationId(scope.correlationId),
                actorRef = waitlistActorRef(scope.actor),
            )
    }
}

/**
 * controller/API lane에서 검증된 tenant scope를 application command factory에 넘기는 별칭입니다.
 */
typealias TenantScope = WaitlistTenantScope

/**
 * public API confirm request가 application service에 넘기는 비보안 command fragment입니다.
 */
data class ConfirmWaitlistOfferCommand(
    val expectedVersion: Long,
    val confirmationSource: String? = null,
    val requestDigest: String,
) : Serializable {
    init {
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
        confirmationSource?.let {
            require(it.isNotBlank() && it.length <= 64) { "confirmationSource must contain 1..64 characters" }
        }
        require(SHA256.matches(requestDigest)) { "requestDigest must be lowercase SHA-256" }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

/** cancellation과 vacancy opening을 함께 실행하기 위한 command입니다. */
data class WaitlistCancellationVacancyCommand(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
    val expectedVersion: Long,
    val reason: WaitlistReasonCode,
    val correlationId: CorrelationId,
    val actorRef: ActorRef,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        appointmentId.requirePositiveNumber("appointmentId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer confirmation response boundary입니다. */
data class WaitlistOfferConfirmationResult(
    val appointmentId: Long,
    val idempotentReplay: Boolean,
) : Serializable {
    init {
        appointmentId.requirePositiveNumber("appointmentId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** cancellation/vacancy transaction 결과입니다. */
data class WaitlistVacancyResult(
    val appointmentId: Long,
    val vacancyJobId: Long,
) : Serializable {
    init {
        appointmentId.requirePositiveNumber("appointmentId")
        vacancyJobId.requirePositiveNumber("vacancyJobId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

class WaitlistApplicationConflict(
    val code: WaitlistApplicationError,
    override val message: String,
) : RuntimeException(message)

enum class WaitlistApplicationError {
    IDEMPOTENCY_IN_PROGRESS,
    IDEMPOTENT_FAILURE_REPLAY,
}

private fun waitlistActorRef(actor: ActorContext): ActorRef =
    ActorRef("staff:${sha256(actor.actorId).take(24)}")

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private const val COMMAND_TYPE_CONFIRM_OFFER = "WAITLIST_CONFIRM_OFFER"
