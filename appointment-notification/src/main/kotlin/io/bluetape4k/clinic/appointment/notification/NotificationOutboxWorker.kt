package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand
import kotlinx.coroutines.CancellationException

/**
 * claim된 notification outbox row 하나를 처리하는 worker 계약입니다.
 */
fun interface NotificationOutboxJobWorker {
    suspend fun process(claimed: ClaimedNotification): NotificationOutboxWorkerResult
}

enum class NotificationOutboxWorkerResult {
    COMPLETED,
    RETRY_SCHEDULED,
    LEASE_LOST,
    EXHAUSTED,
    NOT_READY,
}

class NotificationOutboxWorker(
    private val workStore: NotificationOutboxWorkStore,
    private val leaseOwner: String,
    private val readiness: NotificationSchemaReadiness? = null,
    private val retryPolicy: NotificationRetryPolicy = NotificationRetryPolicy(),
    private val deliveryAction: NotificationDeliveryAction = NotificationDeliveryAction {
        NotificationDeliveryResult.retry(NotificationFailureCode.DELIVERY_RESULT_UNKNOWN)
    },
    private val profileResolver: MemberNotificationProfileResolver? = null,
    private val templateRenderer: NotificationTemplateRenderer? = null,
    private val providerChannel: NotificationChannel? = null,
    private val providerIdempotencyKeyFactory: NotificationProviderIdempotencyKeyFactory? = null,
    private val outboxCodec: NotificationOutboxCodec = NotificationOutboxCodec(),
    private val securityAuditSink: NotificationSecurityAuditSink = NoopNotificationSecurityAuditSink,
) : NotificationOutboxJobWorker {

    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 128) {
            "leaseOwner must contain 1..128 characters"
        }
        val runtimeDependencyCount = listOf(
            profileResolver,
            templateRenderer,
            providerChannel,
            providerIdempotencyKeyFactory,
        ).count { it != null }
        require(runtimeDependencyCount == 0 || runtimeDependencyCount == RUNTIME_DEPENDENCY_COUNT) {
            "runtime delivery dependencies must be configured together"
        }
    }

    suspend fun recoverExpiredOnce(limit: Int): List<ClaimedNotification> {
        require(limit > 0) { "limit must be positive" }
        if (readiness?.check()?.available == false) return emptyList()
        return workStore.recoverExpired(limit, leaseOwner)
    }

    override suspend fun process(claimed: ClaimedNotification): NotificationOutboxWorkerResult {
        if (readiness?.check()?.available == false) return NotificationOutboxWorkerResult.NOT_READY
        return try {
            if (profileResolver != null && templateRenderer != null && providerChannel != null && providerIdempotencyKeyFactory != null) {
                return processRuntimeDelivery(claimed, profileResolver, templateRenderer, providerChannel, providerIdempotencyKeyFactory)
            }
            when (val result = deliveryAction.deliver(claimed)) {
                is NotificationDeliveryResult.Sent -> completeSent(claimed, result)
                is NotificationDeliveryResult.RetryableFailure -> handleRetryableFailure(claimed, result.failureCode)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun processRuntimeDelivery(
        claimed: ClaimedNotification,
        profileResolver: MemberNotificationProfileResolver,
        templateRenderer: NotificationTemplateRenderer,
        providerChannel: NotificationChannel,
        keyFactory: NotificationProviderIdempotencyKeyFactory,
    ): NotificationOutboxWorkerResult {
        val envelope = try {
            outboxCodec.decode(claimed.parametersJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificationContractException) {
            return handleRetryableFailure(claimed, e.failureCode)
        } catch (e: IllegalArgumentException) {
            return handleRetryableFailure(claimed, NotificationFailureCode.TEMPLATE_PARAMETER_INVALID)
        } catch (e: RuntimeException) {
            return handleRetryableFailure(claimed, NotificationFailureCode.TEMPLATE_PARAMETER_INVALID)
        }
        val profileResult = profileResolver.resolve(
            MemberNotificationProfileRequest(
                tenantGroupId = claimed.tenantGroupId,
                clinicId = claimed.clinicId,
                memberId = claimed.memberId,
                channel = claimed.channel,
            )
        )
        val profileDecision = MemberNotificationProfileClassifier.classify(
            result = profileResult,
            context = MemberProfileResolutionContext(
                tenantGroupId = claimed.tenantGroupId,
                clinicId = claimed.clinicId,
                channel = claimed.channel,
                memberId = claimed.memberId,
            ),
            auditSink = securityAuditSink,
        )
        profileDecision.failureCode?.let { return handleRetryableFailure(claimed, it) }
        profileDecision.suppressionReason?.let { return completeSuppressed(claimed, it) }
        val profile = checkNotNull(profileDecision.profile)
        val rendered = try {
            templateRenderer.render(
                key = claimed.templateKey,
                version = claimed.templateVersion,
                channel = claimed.channel,
                parameters = envelope.parameters,
                profile = profile,
            )
        } catch (e: NotificationTemplateException) {
            return handleRetryableFailure(claimed, e.failureCode)
        } catch (e: IllegalArgumentException) {
            return handleRetryableFailure(claimed, NotificationFailureCode.TEMPLATE_PARAMETER_INVALID)
        }
        val request = try {
            NotificationProviderRequest(
                channel = claimed.channel,
                destination = checkNotNull(profile.destination) { "resolved profile destination must exist" },
                idempotencyKey = keyFactory.create(claimed.idempotencyKey),
                templateKey = claimed.templateKey,
                templateVersion = claimed.templateVersion,
                rendered = rendered,
            )
        } catch (e: IllegalArgumentException) {
            return handleRetryableFailure(claimed, NotificationFailureCode.TEMPLATE_PARAMETER_INVALID)
        }
        val providerResult = try {
            providerChannel.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificationProviderException) {
            return handleRetryableFailure(claimed, e.failureCode)
        }
        return when (providerResult) {
            is NotificationProviderResult.Accepted -> completeSent(
                claimed,
                NotificationDeliveryResult.Sent(providerMessageReference = providerResult.providerMessageReference),
            )
            is NotificationProviderResult.RetryableFailure -> handleRetryableFailure(claimed, providerResult.failureCode)
            is NotificationProviderResult.Suppressed -> completeSuppressed(claimed, providerResult.reason)
        }
    }

    private suspend fun completeSent(
        claimed: ClaimedNotification,
        result: NotificationDeliveryResult.Sent,
    ): NotificationOutboxWorkerResult {
        val completed = workStore.complete(
            CompleteNotificationCommand(
                outboxId = claimed.id,
                owner = claimed.owner,
                token = claimed.token,
                attemptNumber = claimed.attemptNumber,
                terminalStatus = NotificationOutboxStatus.SENT,
                providerMessageReference = result.providerMessageReference,
                destinationFingerprint = result.destinationFingerprint,
                correlationId = result.correlationId,
                traceId = result.traceId,
            )
        )
        return if (completed) NotificationOutboxWorkerResult.COMPLETED else NotificationOutboxWorkerResult.LEASE_LOST
    }

    private suspend fun handleRetryableFailure(
        claimed: ClaimedNotification,
        failureCode: NotificationFailureCode,
    ): NotificationOutboxWorkerResult {
        val decision = retryPolicy.decide(
            attemptNumber = claimed.attemptNumber,
            firstAttemptAt = claimed.firstAttemptAt,
            now = workStore.currentDatabaseTime(),
            failureCode = failureCode,
            jitterSeed = claimed.id,
        )
        return when (decision.kind) {
            NotificationRetryDecisionKind.RETRY_WAIT -> {
                val retried = workStore.retry(
                    RetryNotificationCommand(
                        outboxId = claimed.id,
                        owner = claimed.owner,
                        token = claimed.token,
                        attemptNumber = claimed.attemptNumber,
                        failureCode = decision.failureCode,
                        retryDelay = requireNotNull(decision.retryDelay),
                    )
                )
                if (retried) NotificationOutboxWorkerResult.RETRY_SCHEDULED else NotificationOutboxWorkerResult.LEASE_LOST
            }
            NotificationRetryDecisionKind.EXHAUSTED -> {
                val completed = workStore.complete(
                    CompleteNotificationCommand(
                        outboxId = claimed.id,
                        owner = claimed.owner,
                        token = claimed.token,
                        attemptNumber = claimed.attemptNumber,
                        terminalStatus = NotificationOutboxStatus.EXHAUSTED,
                        failureCode = decision.failureCode,
                    )
                )
                if (completed) NotificationOutboxWorkerResult.EXHAUSTED else NotificationOutboxWorkerResult.LEASE_LOST
            }
        }
    }

    private suspend fun completeSuppressed(
        claimed: ClaimedNotification,
        reason: io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode,
    ): NotificationOutboxWorkerResult {
        val completed = workStore.complete(
            CompleteNotificationCommand(
                outboxId = claimed.id,
                owner = claimed.owner,
                token = claimed.token,
                attemptNumber = claimed.attemptNumber,
                terminalStatus = NotificationOutboxStatus.SUPPRESSED,
                suppressionReason = reason,
            )
        )
        return if (completed) NotificationOutboxWorkerResult.COMPLETED else NotificationOutboxWorkerResult.LEASE_LOST
    }
}

private const val RUNTIME_DEPENDENCY_COUNT = 4

/** provider adapter가 구현하는 단일 delivery 시도 계약입니다. */
fun interface NotificationDeliveryAction {
    suspend fun deliver(claimed: ClaimedNotification): NotificationDeliveryResult
}

/** provider I/O 뒤 worker lifecycle로 전달하는 개인정보 비포함 결과입니다. */
sealed class NotificationDeliveryResult : java.io.Serializable {
    data class Sent(
        val providerMessageReference: io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference? = null,
        val destinationFingerprint: io.bluetape4k.clinic.appointment.event.notification.NotificationDestinationFingerprint? = null,
        val correlationId: io.bluetape4k.clinic.appointment.event.notification.NotificationCorrelationId? = null,
        val traceId: io.bluetape4k.clinic.appointment.event.notification.NotificationTraceId? = null,
    ) : NotificationDeliveryResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class RetryableFailure(
        val failureCode: NotificationFailureCode,
    ) : NotificationDeliveryResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun sent(): NotificationDeliveryResult = Sent()

        fun retry(failureCode: NotificationFailureCode): NotificationDeliveryResult =
            RetryableFailure(failureCode)
    }
}
