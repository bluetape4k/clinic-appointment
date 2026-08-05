package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.vendors.MariaDBDialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** relay가 소유한 bounded lease claim이다. */
data class AppointmentOutboxClaim(
    val id: Long,
    val eventId: AppointmentEventId,
    val eventType: AppointmentEventType,
    val tenantGroupId: Long,
    val clinicId: Long,
    val aggregateType: String,
    val aggregateId: AppointmentAggregateId,
    val topic: AppointmentTopic,
    val partitionKey: AppointmentPartitionKey,
    val payloadJson: String,
    val attemptNumber: Int,
    val owner: String,
    val token: String,
    val leaseUntil: Instant,
)

/** tenant/clinic/appointment 식별자를 metric에 노출하지 않는 bounded backlog snapshot이다. */
data class AppointmentOutboxBacklogSnapshot(
    val pending: Long,
    val oldestAgeSeconds: Double,
    val partitionSkew: Double,
)

/** DB lease와 owner/token fencing을 소유하는 저장소 port다. */
interface AppointmentOutboxStore {
    fun backlogSnapshot(now: Instant): AppointmentOutboxBacklogSnapshot =
        AppointmentOutboxBacklogSnapshot(pending = 0, oldestAgeSeconds = 0.0, partitionSkew = 0.0)

    fun claim(
        owner: String,
        limit: Int,
        leaseDuration: Duration,
    ): List<AppointmentOutboxClaim>

    fun markPublished(claim: AppointmentOutboxClaim): Boolean

    fun markRetry(
        claim: AppointmentOutboxClaim,
        retryAfter: Duration,
        failureCode: String,
    ): Boolean

    fun markFailed(
        claim: AppointmentOutboxClaim,
        failureCode: String,
    ): Boolean
}

/**
 * appointment allow-list row만 bounded batch로 claim하는 JDBC store다.
 * 모든 SQL은 메서드가 소유한 Exposed transaction 안에서 실행된다.
 */
class JdbcAppointmentOutboxStore internal constructor(
    private val maxAttempts: Int = 8,
    private val allowedTopics: Set<AppointmentTopic> = setOf(AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC)),
    private val maxClinicBatch: Int = 1,
    private val metrics: AppointmentOutboxMetrics = NoopAppointmentOutboxMetrics,
) : AppointmentOutboxStore {

    init {
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
        require(allowedTopics.isNotEmpty()) { "allowedTopics must not be empty" }
        require(maxClinicBatch in 1..4) { "maxClinicBatch must be between 1 and 4" }
    }

    override fun claim(owner: String, limit: Int, leaseDuration: Duration): List<AppointmentOutboxClaim> {
        require(owner.isNotBlank() && owner.length <= 160) { "owner must be bounded" }
        require(limit in 1..MAX_CLAIM_SIZE) { "limit must be between 1 and $MAX_CLAIM_SIZE" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
        return transaction {
            // Lease predicates and timestamps must share the database clock. A worker clock
            // can be skewed, while CURRENT_TIMESTAMP is the authority used by all claimers.
            val now = AppointmentDatabaseClock.current.now()
            val leaseUntil = now.plus(leaseDuration)
            val predecessor = SchedulingOutboxEvents.alias("appointment_outbox_predecessor")
            val invalidReadyRows = SchedulingOutboxEvents
                .select(SchedulingOutboxEvents.id)
                .where {
                    (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING) and
                        (SchedulingOutboxEvents.aggregateType eq AppointmentEventEnvelope.AGGREGATE_TYPE) and
                        ((SchedulingOutboxEvents.nextAttemptAt.isNull()) or
                            (SchedulingOutboxEvents.nextAttemptAt lessEq now)) and
                        ((SchedulingOutboxEvents.leaseUntil.isNull()) or
                            (SchedulingOutboxEvents.leaseUntil lessEq now)) and
                        (
                            invalidEventTypePredicate() or
                                SchedulingOutboxEvents.topic.isNull() or
                                SchedulingOutboxEvents.partitionKey.isNull()
                            )
                }
                .orderBy(SchedulingOutboxEvents.createdAt to SortOrder.ASC, SchedulingOutboxEvents.id to SortOrder.ASC)
                .limit(MAX_CANDIDATE_PAGE_SIZE)
                .forUpdate(readyClaimLockOption())
                .map { InvalidRow(it[SchedulingOutboxEvents.id].value, AppointmentOutboxRelay.FAILURE_INVALID_METADATA) }
            markInvalidRows(invalidReadyRows, now)
            // Preserve the existing one-cycle invalid-row contract: terminalize the bad
            // metadata first, then let the next claim observe a valid successor. This
            // prevents an invalid predecessor from being skipped and its successor from
            // being claimed in the same transaction.
            if (invalidReadyRows.isNotEmpty()) return@transaction emptyList()

            val candidates = SchedulingOutboxEvents
                .selectAll()
                .where {
                    (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING) and
                        (SchedulingOutboxEvents.aggregateType eq AppointmentEventEnvelope.AGGREGATE_TYPE) and
                        (SchedulingOutboxEvents.eventType inList allowedEventTypeWireNames()) and
                        SchedulingOutboxEvents.topic.isNotNull() and
                        SchedulingOutboxEvents.partitionKey.isNotNull() and
                        ((SchedulingOutboxEvents.nextAttemptAt.isNull()) or
                            (SchedulingOutboxEvents.nextAttemptAt lessEq now)) and
                        ((SchedulingOutboxEvents.leaseUntil.isNull()) or
                            (SchedulingOutboxEvents.leaseUntil lessEq now)) and
                        notExists(
                            predecessor
                                .selectAll()
                                .where {
                                    (predecessor[SchedulingOutboxEvents.aggregateType] eq
                                        SchedulingOutboxEvents.aggregateType) and
                                        (predecessor[SchedulingOutboxEvents.aggregateId] eq
                                            SchedulingOutboxEvents.aggregateId) and
                                        (predecessor[SchedulingOutboxEvents.aggregateType] eq
                                            AppointmentEventEnvelope.AGGREGATE_TYPE) and
                                        (predecessor[SchedulingOutboxEvents.status] eq SchedulingOutboxStatus.PENDING) and
                                        (
                                            (predecessor[SchedulingOutboxEvents.createdAt] less
                                                SchedulingOutboxEvents.createdAt) or
                                                (
                                                    (predecessor[SchedulingOutboxEvents.createdAt] eq
                                                        SchedulingOutboxEvents.createdAt) and
                                                        (predecessor[SchedulingOutboxEvents.id] less SchedulingOutboxEvents.id)
                                                    )
                                            )
                                },
                        )
                }
                .orderBy(SchedulingOutboxEvents.createdAt to SortOrder.ASC, SchedulingOutboxEvents.id to SortOrder.ASC)
                .limit(minOf(MAX_CANDIDATE_PAGE_SIZE, limit * maxClinicBatch * CLINIC_PAGE_MULTIPLIER))
                .forUpdate(readyClaimLockOption())
                .toList()

            val candidatesToClaim = mutableListOf<ClaimCandidate>()
            val aggregateIdsInBatch = mutableSetOf<String>()
            val clinicCounts = mutableMapOf<Long, Int>()
            val invalidRows = mutableListOf<InvalidRow>()
            for (row in candidates) {
                val id = row[SchedulingOutboxEvents.id].value
                val topicValue = row[SchedulingOutboxEvents.topic]
                val partitionValue = row[SchedulingOutboxEvents.partitionKey]
                val aggregateId = row[SchedulingOutboxEvents.aggregateId]
                val topic: AppointmentTopic? = topicValue?.let { runCatching { AppointmentTopic(it) }.getOrNull() }
                val parsedAggregateId: AppointmentAggregateId? = aggregateId?.toLongOrNull()?.let { number ->
                    runCatching { AppointmentAggregateId(number) }.getOrNull()
                }
                val parsedEventType: AppointmentEventType? = runCatching {
                    AppointmentEventType.fromWireName(row[SchedulingOutboxEvents.eventType])
                }.getOrNull()
                val parsedClinicId: Long? = row[SchedulingOutboxEvents.clinicId]?.value
                val parsedAggregateType: String? = row[SchedulingOutboxEvents.aggregateType]
                val parsedPartitionKey: AppointmentPartitionKey? = partitionValue?.let {
                    runCatching { AppointmentPartitionKey(it) }.getOrNull()
                }
                val parsedEventId: AppointmentEventId? =
                    runCatching { AppointmentEventId(row[SchedulingOutboxEvents.eventId]) }.getOrNull()
                if (topic == null || parsedPartitionKey == null || parsedAggregateId == null ||
                    parsedEventType == null || parsedClinicId == null || parsedAggregateType == null ||
                    parsedEventId == null
                ) {
                    invalidRows += InvalidRow(id, AppointmentOutboxRelay.FAILURE_INVALID_METADATA)
                    continue
                }
                if (topic !in allowedTopics) {
                    invalidRows += InvalidRow(id, AppointmentOutboxRelay.FAILURE_DISALLOWED_TOPIC)
                    continue
                }
                if (!aggregateIdsInBatch.add(parsedAggregateId.value.toString())) continue
                val attemptNumber = row[SchedulingOutboxEvents.attemptCount] + 1
                if (attemptNumber > this@JdbcAppointmentOutboxStore.maxAttempts) {
                    invalidRows += InvalidRow(id, AppointmentOutboxRelay.FAILURE_ATTEMPT_EXHAUSTED)
                    continue
                }
                val clinicCount = clinicCounts[parsedClinicId] ?: 0
                if (clinicCount >= maxClinicBatch) continue
                clinicCounts[parsedClinicId] = clinicCount + 1
                candidatesToClaim += ClaimCandidate(
                    id = id,
                    eventId = parsedEventId,
                    eventType = parsedEventType,
                    tenantGroupId = row[SchedulingOutboxEvents.tenantGroupId].value,
                    clinicId = parsedClinicId,
                    aggregateType = parsedAggregateType,
                    aggregateId = parsedAggregateId,
                    topic = topic,
                    partitionKey = parsedPartitionKey,
                    payloadJson = row[SchedulingOutboxEvents.payloadJson],
                    attemptNumber = attemptNumber,
                    token = UUID.randomUUID().toString(),
                )
                if (candidatesToClaim.size >= limit) break
            }

            markInvalidRows(invalidRows, now)

            if (candidatesToClaim.isEmpty()) return@transaction emptyList()

            // The claim is one bounded conditional UPDATE. CASE expressions preserve a
            // random fencing token and the per-row attempt increment without falling back
            // to N row-level updates. H2 uses the same bounded IN CAS; it simply omits
            // SKIP LOCKED because its dialect does not support that option.
            val candidateIds = candidatesToClaim.map { it.id }
            val candidateAttemptPredicate = candidatesToClaim
                .map { candidate ->
                    (SchedulingOutboxEvents.id eq candidate.id) and
                        (SchedulingOutboxEvents.attemptCount eq candidate.attemptNumber - 1)
                }
                .reduce { left, right -> left or right }
            val tokenCase = case().When(
                SchedulingOutboxEvents.id eq candidatesToClaim.first().id,
                stringLiteral(candidatesToClaim.first().token),
            )
            candidatesToClaim.drop(1).forEach { candidate ->
                tokenCase.When(SchedulingOutboxEvents.id eq candidate.id, stringLiteral(candidate.token))
            }
            val tokenExpression = tokenCase.Else(stringLiteral(""))
            val attemptCase = case().When(
                SchedulingOutboxEvents.id eq candidatesToClaim.first().id,
                intLiteral(candidatesToClaim.first().attemptNumber),
            )
            candidatesToClaim.drop(1).forEach { candidate ->
                attemptCase.When(SchedulingOutboxEvents.id eq candidate.id, intLiteral(candidate.attemptNumber))
            }
            val attemptExpression = attemptCase.Else(SchedulingOutboxEvents.attemptCount)
            SchedulingOutboxEvents.update({
                (SchedulingOutboxEvents.id inList candidateIds) and
                    (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING) and
                    candidateAttemptPredicate and
                    ((SchedulingOutboxEvents.nextAttemptAt.isNull()) or
                        (SchedulingOutboxEvents.nextAttemptAt lessEq now)) and
                    ((SchedulingOutboxEvents.leaseUntil.isNull()) or
                        (SchedulingOutboxEvents.leaseUntil lessEq now))
            }) {
                it[SchedulingOutboxEvents.leaseOwner] = owner
                it[SchedulingOutboxEvents.leaseToken] = tokenExpression
                it[SchedulingOutboxEvents.leaseUntil] = leaseUntil
                it[SchedulingOutboxEvents.attemptCount] = attemptExpression
            }

            // Re-read only the bounded candidate page so a concurrent CAS loss cannot
            // escape as a fabricated claim. No per-row update is performed here.
            val candidateById = candidatesToClaim.associateBy { it.id }
            SchedulingOutboxEvents
                .selectAll()
                .where { SchedulingOutboxEvents.id inList candidateIds }
                .mapNotNull { row ->
                    val candidate = candidateById[row[SchedulingOutboxEvents.id].value] ?: return@mapNotNull null
                    if (row[SchedulingOutboxEvents.leaseOwner] != owner ||
                        row[SchedulingOutboxEvents.leaseToken] != candidate.token ||
                        row[SchedulingOutboxEvents.attemptCount] != candidate.attemptNumber
                    ) {
                        return@mapNotNull null
                    }
                    AppointmentOutboxClaim(
                        id = candidate.id,
                        eventId = candidate.eventId,
                        eventType = candidate.eventType,
                        tenantGroupId = candidate.tenantGroupId,
                        clinicId = candidate.clinicId,
                        aggregateType = candidate.aggregateType,
                        aggregateId = candidate.aggregateId,
                        topic = candidate.topic,
                        partitionKey = candidate.partitionKey,
                        payloadJson = candidate.payloadJson,
                        attemptNumber = candidate.attemptNumber,
                        owner = owner,
                        token = candidate.token,
                        leaseUntil = leaseUntil,
                    )
                }
        }
    }

    override fun backlogSnapshot(now: Instant): AppointmentOutboxBacklogSnapshot = transaction {
        val pendingPredicate = (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING) and
            (SchedulingOutboxEvents.aggregateType eq AppointmentEventEnvelope.AGGREGATE_TYPE)
        val countExpression = SchedulingOutboxEvents.id.count()
        val pending = SchedulingOutboxEvents
            .select(countExpression)
            .where { pendingPredicate }
            .single()[countExpression]
        val oldest = SchedulingOutboxEvents
            .select(SchedulingOutboxEvents.createdAt)
            .where { pendingPredicate }
            .orderBy(SchedulingOutboxEvents.createdAt to SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.get(SchedulingOutboxEvents.createdAt)
        val partitionCounts = SchedulingOutboxEvents
            .select(SchedulingOutboxEvents.partitionKey)
            .where { pendingPredicate and SchedulingOutboxEvents.partitionKey.isNotNull() }
            .limit(METRICS_PARTITION_SAMPLE_SIZE)
            .mapNotNull { it[SchedulingOutboxEvents.partitionKey] }
            .groupingBy { it }
            .eachCount()
        val maxPartition = partitionCounts.values.maxOrNull()?.toDouble() ?: 0.0
        val averagePartition = if (partitionCounts.isEmpty()) 0.0 else {
            partitionCounts.values.average()
        }
        AppointmentOutboxBacklogSnapshot(
            pending = pending,
            oldestAgeSeconds = oldest?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) / 1000.0 } ?: 0.0,
            partitionSkew = if (averagePartition == 0.0) 0.0 else maxPartition / averagePartition,
        )
    }

    override fun markPublished(claim: AppointmentOutboxClaim): Boolean {
        return transaction {
            val now = AppointmentDatabaseClock.current.now()
            SchedulingOutboxEvents.update({ fencedPredicate(claim, now) }) {
                it[SchedulingOutboxEvents.status] = SchedulingOutboxStatus.PUBLISHED
                it[SchedulingOutboxEvents.publishedAt] = now
                it[SchedulingOutboxEvents.leaseOwner] = null
                it[SchedulingOutboxEvents.leaseToken] = null
                it[SchedulingOutboxEvents.leaseUntil] = null
            } == 1
        }
    }

    override fun markRetry(claim: AppointmentOutboxClaim, retryAfter: Duration, failureCode: String): Boolean {
        require(!retryAfter.isNegative) { "retryAfter must not be negative" }
        requireFailureCode(failureCode)
        return transaction {
            val now = AppointmentDatabaseClock.current.now()
            SchedulingOutboxEvents.update({ fencedPredicate(claim, now) }) {
                it[SchedulingOutboxEvents.status] = if (claim.attemptNumber >= this@JdbcAppointmentOutboxStore.maxAttempts) {
                    SchedulingOutboxStatus.FAILED
                } else {
                    SchedulingOutboxStatus.PENDING
                }
                it[SchedulingOutboxEvents.nextAttemptAt] =
                    if (claim.attemptNumber >= this@JdbcAppointmentOutboxStore.maxAttempts) {
                        null
                    } else {
                        now.plus(retryAfter)
                    }
                it[SchedulingOutboxEvents.lastFailureCode] = failureCode
                it[SchedulingOutboxEvents.lastFailureAt] = now
                it[SchedulingOutboxEvents.leaseOwner] = null
                it[SchedulingOutboxEvents.leaseToken] = null
                it[SchedulingOutboxEvents.leaseUntil] = null
            } == 1
        }
    }

    override fun markFailed(claim: AppointmentOutboxClaim, failureCode: String): Boolean {
        requireFailureCode(failureCode)
        return transaction {
            val now = AppointmentDatabaseClock.current.now()
            SchedulingOutboxEvents.update({ fencedPredicate(claim, now) }) {
                it[SchedulingOutboxEvents.status] = SchedulingOutboxStatus.FAILED
                it[SchedulingOutboxEvents.nextAttemptAt] = null
                it[SchedulingOutboxEvents.lastFailureCode] = failureCode
                it[SchedulingOutboxEvents.lastFailureAt] = now
                it[SchedulingOutboxEvents.leaseOwner] = null
                it[SchedulingOutboxEvents.leaseToken] = null
                it[SchedulingOutboxEvents.leaseUntil] = null
            } == 1
        }
    }

    private fun fencedPredicate(claim: AppointmentOutboxClaim, now: Instant) =
        (SchedulingOutboxEvents.id eq claim.id) and
            (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING) and
            (SchedulingOutboxEvents.attemptCount eq claim.attemptNumber) and
            (SchedulingOutboxEvents.leaseOwner eq claim.owner) and
            (SchedulingOutboxEvents.leaseToken eq claim.token) and
            (SchedulingOutboxEvents.leaseUntil greater now) and
            ((SchedulingOutboxEvents.nextAttemptAt.isNull()) or
                (SchedulingOutboxEvents.nextAttemptAt lessEq now))

    /** PostgreSQL/MySQL에서는 다른 relay가 잠근 행을 기다리지 않고 다음 후보로 진행한다. */
    private fun readyClaimLockOption(): ForUpdateOption = when (currentDialect) {
        is PostgreSQLDialect -> ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)
        is MysqlDialect -> ForUpdateOption.MySQL.ForUpdate(ForUpdateOption.MySQL.MODE.SKIP_LOCKED)
        is MariaDBDialect -> ForUpdateOption.ForUpdate
        else -> ForUpdateOption.ForUpdate
    }

    private fun markInvalidRows(rows: List<InvalidRow>, now: Instant) {
        if (rows.isEmpty()) return
        val ids = rows.map(InvalidRow::id)
        val failureCase = case().When(
            SchedulingOutboxEvents.id eq rows.first().id,
            stringLiteral(rows.first().failureCode),
        )
        rows.drop(1).forEach { row ->
            failureCase.When(SchedulingOutboxEvents.id eq row.id, stringLiteral(row.failureCode))
        }
        val updated = SchedulingOutboxEvents.update({
            (SchedulingOutboxEvents.id inList ids) and
                (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PENDING)
        }) {
            it[SchedulingOutboxEvents.status] = SchedulingOutboxStatus.FAILED
            it[SchedulingOutboxEvents.nextAttemptAt] = null
            it[SchedulingOutboxEvents.lastFailureCode] = failureCase.Else(stringLiteral("INVALID_METADATA"))
            it[SchedulingOutboxEvents.lastFailureAt] = now
            it[SchedulingOutboxEvents.leaseOwner] = null
            it[SchedulingOutboxEvents.leaseToken] = null
            it[SchedulingOutboxEvents.leaseUntil] = null
        }
        if (updated > 0) {
            rows.forEach { row ->
                metrics.publishFailed(row.failureCode)
                metrics.contractRejected(row.failureCode)
            }
        }
    }

    private fun requireFailureCode(value: String) {
        require(FAILURE_CODE_PATTERN.matches(value)) { "failureCode must be stable and bounded" }
    }

    private fun invalidEventTypePredicate() = APPROVED_EVENT_TYPE_WIRE_NAMES
        .map { SchedulingOutboxEvents.eventType neq it }
        .reduce { left, right -> left and right }

    private fun allowedEventTypeWireNames(): List<String> = APPROVED_EVENT_TYPE_WIRE_NAMES

    companion object {
        private val APPROVED_EVENT_TYPE_WIRE_NAMES = listOf(
            AppointmentEventType.CREATED.wireName,
            AppointmentEventType.STATUS_CHANGED.wireName,
            AppointmentEventType.CANCELLED.wireName,
            AppointmentEventType.RESCHEDULED.wireName,
        )
        private const val MAX_CLAIM_SIZE = 32
        private const val MAX_CANDIDATE_PAGE_SIZE = 128
        private const val CLINIC_PAGE_MULTIPLIER = 4
        private const val METRICS_PARTITION_SAMPLE_SIZE = 1_000
        private val FAILURE_CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }

    private data class ClaimCandidate(
        val id: Long,
        val eventId: AppointmentEventId,
        val eventType: AppointmentEventType,
        val tenantGroupId: Long,
        val clinicId: Long,
        val aggregateType: String,
        val aggregateId: AppointmentAggregateId,
        val topic: AppointmentTopic,
        val partitionKey: AppointmentPartitionKey,
        val payloadJson: String,
        val attemptNumber: Int,
        val token: String,
    )

    private data class InvalidRow(
        val id: Long,
        val failureCode: String,
    )
}
