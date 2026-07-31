package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
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
) : NotificationOutboxJobWorker {

    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 128) {
            "leaseOwner must contain 1..128 characters"
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
            when (val result = deliveryAction.deliver(claimed)) {
                is NotificationDeliveryResult.Sent -> completeSent(claimed, result)
                is NotificationDeliveryResult.RetryableFailure -> handleRetryableFailure(claimed, result.failureCode)
            }
        } catch (e: CancellationException) {
            throw e
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
}

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
