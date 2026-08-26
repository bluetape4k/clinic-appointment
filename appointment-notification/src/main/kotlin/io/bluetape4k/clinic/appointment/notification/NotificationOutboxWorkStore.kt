package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
import io.bluetape4k.clinic.appointment.notification.persistence.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationCandidate
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationFairCursor
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxObservation
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxObservationPersistence
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxWorkPersistence
import io.bluetape4k.clinic.appointment.notification.persistence.RetryNotificationCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * notification worker가 outbox row를 짧은 DB transaction으로만 조회·선점·종결하는 port입니다.
 */
interface NotificationOutboxWorkStore {
    suspend fun findFairCandidates(
        limit: Int,
        cursor: NotificationFairCursor?,
    ): NotificationCandidatePage

    /** route와 병원별 동시성에 맞춰 DB 후보 집합 자체를 제한합니다. */
    suspend fun findFairCandidatesForRoute(
        limit: Int,
        cursor: NotificationFairCursor?,
        perClinicLimit: Int,
        eligibleScopes: Set<TenantClinicScope>?,
    ): NotificationCandidatePage =
        findFairCandidates(limit, cursor).let { page ->
            val scopes = eligibleScopes
            when {
                scopes != null -> page.copy(
                    candidates = page.candidates.filter {
                        TenantClinicScope(it.tenantGroupId.value, it.clinicId.value) in scopes
                    },
                )
                else -> page
            }
        }

    suspend fun claim(
        id: Long,
        owner: String,
    ): ClaimedNotification?

    suspend fun recoverExpired(
        limit: Int,
        owner: String,
    ): List<ClaimedNotification>

    /**
     * route의 tenant/clinic allowlist만 복구한다. 기본 구현은 custom store의 source
     * 호환성을 유지하며, JDBC store는 이 predicate를 SQL에 반영해야 한다.
     */
    suspend fun recoverExpired(
        limit: Int,
        owner: String,
        eligibleScopes: Set<TenantClinicScope>?,
    ): List<ClaimedNotification> = recoverExpired(limit, owner)

    suspend fun complete(command: CompleteNotificationCommand): Boolean

    suspend fun retry(command: RetryNotificationCommand): Boolean

    suspend fun currentDatabaseTime(): Instant

    suspend fun deleteTerminalBatch(
        status: NotificationOutboxStatus,
        retention: Duration,
        limit: Int,
    ): Int
}

data class NotificationCandidatePage(
    val candidates: List<NotificationCandidate>,
    val nextCursor: NotificationFairCursor?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * notification persistence capability의 caller-transaction 계약을 coroutine worker 경계에 맞춥니다.
 */
class JdbcNotificationOutboxWorkStore(
    private val database: Database,
    private val persistence: NotificationOutboxWorkPersistence,
    private val tokenGenerator: NotificationLeaseTokenGenerator = SecureNotificationLeaseTokenGenerator(),
) : NotificationOutboxWorkStore, NotificationDirectOutboxStore {

    override suspend fun findFairCandidates(
        limit: Int,
        cursor: NotificationFairCursor?,
    ): NotificationCandidatePage =
        findFairCandidatesForRoute(
            limit,
            cursor,
            perClinicLimit = 1,
            eligibleScopes = null,
        )

    override suspend fun findFairCandidatesForRoute(
        limit: Int,
        cursor: NotificationFairCursor?,
        perClinicLimit: Int,
        eligibleScopes: Set<TenantClinicScope>?,
    ): NotificationCandidatePage =
        ioTransaction {
            require(limit > 0) { "limit must be positive" }
            require(perClinicLimit > 0) { "perClinicLimit must be positive" }
            val clinicLimit = (limit + perClinicLimit - 1) / perClinicLimit
            val clinics = persistence.findReadyClinicKeys(
                cursor = cursor,
                limit = clinicLimit,
                eligibleScopes = eligibleScopes,
            )
                .ifEmpty {
                    if (cursor == null) emptyList()
                    else persistence.findReadyClinicKeys(
                        cursor = null,
                        limit = clinicLimit,
                        eligibleScopes = eligibleScopes,
                    )
                }
            val candidates = clinics
                .asSequence()
                .flatMap { clinic ->
                    persistence.findReadyCandidates(clinic, cursorId = null, limit = perClinicLimit).asSequence()
                }
                .take(limit)
                .toList()
            NotificationCandidatePage(
                candidates = candidates,
                nextCursor = candidates.lastOrNull()?.let {
                    NotificationFairCursor(it.tenantGroupId, it.clinicId)
                } ?: cursor,
            )
        }

    override suspend fun claim(
        id: Long,
        owner: String,
    ): ClaimedNotification? =
        ioTransaction {
            persistence.claim(id, owner, tokenGenerator.nextToken())
        }

    override suspend fun claimReady(
        scope: TenantClinicScope,
        appointmentId: AppointmentId,
        eventType: NotificationEventType,
        owner: String,
    ): ClaimedNotification? =
        ioTransaction {
            persistence.claimReadyForDirect(
                scope = scope,
                appointmentId = appointmentId,
                eventType = eventType,
                owner = owner,
                token = tokenGenerator.nextToken(),
            )
        }

    override suspend fun recoverExpired(
        limit: Int,
        owner: String,
    ): List<ClaimedNotification> =
        recoverExpired(limit, owner, eligibleScopes = null)

    override suspend fun recoverExpired(
        limit: Int,
        owner: String,
        eligibleScopes: Set<TenantClinicScope>?,
    ): List<ClaimedNotification> =
        ioTransaction {
            persistence.findExpiredProcessingIds(limit, eligibleScopes)
                .mapNotNull { id -> persistence.recoverExpired(id, owner, tokenGenerator.nextToken()) }
        }

    override suspend fun complete(command: CompleteNotificationCommand): Boolean =
        ioTransaction { persistence.complete(command) }

    override suspend fun retry(command: RetryNotificationCommand): Boolean =
        ioTransaction { persistence.scheduleRetry(command) }

    override suspend fun currentDatabaseTime(): Instant =
        ioTransaction { persistence.currentDatabaseTime() }

    override suspend fun deleteTerminalBatch(
        status: NotificationOutboxStatus,
        retention: Duration,
        limit: Int,
    ): Int =
        ioTransaction { persistence.deleteTerminalBatch(status, retention, limit) }

    private suspend fun <T> ioTransaction(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }
}

/** indexed active-row query를 제한된 수만 읽어 Micrometer snapshot으로 변환합니다. */
class JdbcNotificationOutboxObservationStore(
    private val database: Database,
    private val persistence: NotificationOutboxObservationPersistence,
    private val observationLimit: Int = 10_001,
) : NotificationOutboxObservationStore {
    init {
        require(observationLimit > 0) { "observationLimit must be positive" }
    }

    override suspend fun loadBoundedSnapshot(): NotificationOutboxObservationSnapshot =
        withContext(Dispatchers.IO) {
            transaction(database) {
                persistence.observeReady(observationLimit).toSnapshot()
            }
        }

    private fun NotificationOutboxObservation.toSnapshot(): NotificationOutboxObservationSnapshot =
        NotificationOutboxObservationSnapshot(
            pendingReady = readyCount,
            oldestActiveAge = oldestReadyAt?.let { Duration.between(it, observedAt).coerceAtLeast(Duration.ZERO) },
            capped = capped,
        )
}

fun interface NotificationLeaseTokenGenerator {
    fun nextToken(): String
}

class SecureNotificationLeaseTokenGenerator : NotificationLeaseTokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun nextToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 24
    }
}
