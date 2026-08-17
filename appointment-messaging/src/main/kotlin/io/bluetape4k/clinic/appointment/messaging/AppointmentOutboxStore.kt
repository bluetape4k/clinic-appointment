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
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
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
class JdbcAppointmentOutboxStore(
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
            // lease predicate와 timestamp는 database clock을 공유해야 합니다. worker clock은
            // 어긋날 수 있지만 CURRENT_TIMESTAMP가 모든 claimer의 권위입니다.
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
            // 기존 one-cycle invalid-row 계약을 유지합니다. 잘못된 metadata를 먼저 terminalize한
            // 다음 다음 claim이 유효한 successor를 보게 합니다. 이 방식으로 invalid predecessor를
            // 건너뛰고 같은 트랜잭션에서 successor를 claim하는 일을 막습니다.
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
                // PostgreSQL의 row lock은 LIMIT에 포함된 모든 행에 잡힙니다. 요청량보다 큰
                // 페이지를 잠그면 한 relay가 후속 행까지 독점해 다른 relay의 claim을
                // 굶기므로, 한 transaction이 잠그는 후보 수를 실제 요청량으로 제한합니다.
                .limit(minOf(MAX_CANDIDATE_PAGE_SIZE, limit))
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

            // claim은 하나의 제한된 conditional UPDATE입니다. CASE expression으로 random fencing
            // token과 행별 attempt 증가를 유지하며 N번의 row-level update로 되돌아가지 않습니다.
            // claim update는 bounded IN CAS로 유지하며, 후보 조회에서 PostgreSQL SKIP LOCKED를 사용합니다.
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

// concurrent CAS 손실이 조작된 claim으로 빠져나가지 않도록 범위가 제한된 candidate
// page만 다시 읽는다. 여기서는 행별 update를 수행하지 않는다.
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

    /** 다른 relay가 잠근 행을 기다리지 않고 다음 후보로 진행하는 PostgreSQL lock입니다. */
    private fun readyClaimLockOption(): ForUpdateOption =
        ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)

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
