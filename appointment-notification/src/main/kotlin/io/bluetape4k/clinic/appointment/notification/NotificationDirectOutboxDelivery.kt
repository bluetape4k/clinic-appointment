package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 전환기 event route가 동일 outbox 행을 조건부 claim하는 최소 저장소 계약입니다. */
fun interface NotificationDirectOutboxStore {
    suspend fun claimReady(
        scope: TenantClinicScope,
        appointmentId: AppointmentId,
        eventType: NotificationEventType,
        owner: String,
    ): ClaimedNotification?
}

/** Spring event listener가 사용하는 privacy-safe 전환기 전달 port입니다. */
fun interface NotificationDirectDeliveryPort {
    suspend fun deliver(
        scope: TenantClinicScope,
        appointmentId: Long,
        eventType: NotificationEventType,
    ): NotificationDirectDeliveryResult
}

/** 전환기 전달 결과입니다. 원본 수신자나 provider payload를 포함하지 않습니다. */
sealed interface NotificationDirectDeliveryResult {
    data object RouteRejected : NotificationDirectDeliveryResult
    data object NotFound : NotificationDirectDeliveryResult
    data class Processed(val workerResult: NotificationOutboxWorkerResult) : NotificationDirectDeliveryResult
}

/**
 * legacy Spring event를 새 durable outbox 처리 파이프라인에 연결합니다.
 *
 * 별도 message나 recipient를 만들지 않고 같은 outbox 행을 claim한 경우에만 worker를
 * 호출하므로 rolling deployment에서도 provider 호출 전 fencing을 공유합니다.
 */
class NotificationDirectOutboxDelivery(
    private val store: NotificationDirectOutboxStore,
    private val worker: NotificationOutboxJobWorker,
    private val routeGate: NotificationDeliveryRouteGate,
    private val leaseOwner: String = "notification-direct-event",
    globalConcurrency: Int = 1,
    perClinicConcurrency: Int = 1,
    private val metrics: NotificationOutboxMetrics? = null,
) : NotificationDirectDeliveryPort {

    private val globalPermits = Semaphore(globalConcurrency)
    private val clinicPermits = NotificationClinicPermitRegistry(perClinicConcurrency)

    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 128) {
            "leaseOwner must contain 1..128 characters"
        }
        require(globalConcurrency > 0) { "globalConcurrency must be positive" }
        require(perClinicConcurrency in 1..globalConcurrency) {
            "perClinicConcurrency must be between 1 and globalConcurrency"
        }
    }

    override suspend fun deliver(
        scope: TenantClinicScope,
        appointmentId: Long,
        eventType: NotificationEventType,
    ): NotificationDirectDeliveryResult {
        if (!routeGate.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope)) {
            metrics?.recordDirectEventScopeRejected(NotificationOutboxMetrics.DIRECT_EVENT_SCOPE_REJECTED)
            return NotificationDirectDeliveryResult.RouteRejected
        }
        return globalPermits.withPermit {
            clinicPermits.withPermit(scope.tenantGroupId, scope.clinicId) {
                val claimed = store.claimReady(
                    scope = scope,
                    appointmentId = AppointmentId(appointmentId),
                    eventType = eventType,
                    owner = leaseOwner,
                ) ?: return@withPermit NotificationDirectDeliveryResult.NotFound
                if (claimed.tenantGroupId.value != scope.tenantGroupId || claimed.clinicId.value != scope.clinicId) {
// worker/provider 작업 직전에 defensive scope check를 유지한다.
                    metrics?.recordDirectEventScopeRejected(NotificationOutboxMetrics.DIRECT_EVENT_CLAIM_SCOPE_MISMATCH)
                    return@withPermit NotificationDirectDeliveryResult.NotFound
                }
                NotificationDirectDeliveryResult.Processed(worker.process(claimed))
            }
        }
    }
}
