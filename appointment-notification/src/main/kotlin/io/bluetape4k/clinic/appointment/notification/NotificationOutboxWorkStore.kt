package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationCandidate
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.security.SecureRandom
import java.util.Base64

/**
 * notification worker가 outbox row를 짧은 DB transaction으로만 조회·선점·종결하는 port입니다.
 */
interface NotificationOutboxWorkStore {
    suspend fun findFairCandidates(
        limit: Int,
        cursor: NotificationFairCursor?,
    ): NotificationCandidatePage

    suspend fun claim(
        id: Long,
        owner: String,
    ): ClaimedNotification?

    suspend fun recoverExpired(
        limit: Int,
        owner: String,
    ): List<ClaimedNotification>

    suspend fun complete(command: CompleteNotificationCommand): Boolean

    suspend fun retry(command: RetryNotificationCommand): Boolean
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
 * [NotificationOutboxRepository]의 caller-transaction 계약을 coroutine worker 경계에 맞춥니다.
 */
class JdbcNotificationOutboxWorkStore(
    private val database: Database,
    private val repository: NotificationOutboxRepository,
    private val tokenGenerator: NotificationLeaseTokenGenerator = SecureNotificationLeaseTokenGenerator(),
) : NotificationOutboxWorkStore {

    override suspend fun findFairCandidates(
        limit: Int,
        cursor: NotificationFairCursor?,
    ): NotificationCandidatePage =
        ioTransaction {
            require(limit > 0) { "limit must be positive" }
            val clinics = repository.findReadyClinicKeys(cursor, limit)
                .ifEmpty {
                    if (cursor == null) emptyList() else repository.findReadyClinicKeys(null, limit)
                }
            val candidates = clinics
                .asSequence()
                .mapNotNull { clinic -> repository.findReadyCandidates(clinic, cursorId = null, limit = 1).firstOrNull() }
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
            repository.claim(id, owner, tokenGenerator.nextToken())
        }

    override suspend fun recoverExpired(
        limit: Int,
        owner: String,
    ): List<ClaimedNotification> =
        ioTransaction {
            repository.findExpiredProcessingIds(limit)
                .mapNotNull { id -> repository.recoverExpired(id, owner, tokenGenerator.nextToken()) }
        }

    override suspend fun complete(command: CompleteNotificationCommand): Boolean =
        ioTransaction { repository.complete(command) }

    override suspend fun retry(command: RetryNotificationCommand): Boolean =
        ioTransaction { repository.scheduleRetry(command) }

    private suspend fun <T> ioTransaction(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }
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
