package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAction
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAuditRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTrigger
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTriggerType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityDecisions
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityOverrides
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityReevaluationJobs
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 예약 신뢰성 사건, 결정, 직원 override 감사 ledger를 저장합니다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed transaction 안에서 실행합니다.
 */
class BookingReliabilityRepository {

    fun recordEvent(
        tenantGroupId: Long,
        clinicId: Long,
        record: BookingReliabilityEventRecord,
        correlationId: String? = null,
        retentionClass: String = "STANDARD",
    ): BookingReliabilityEventRecord {
        validateScope(tenantGroupId, clinicId)
        require(correlationId == null || correlationId.length <= 160) {
            "correlationId must not exceed 160 characters"
        }
        require(retentionClass.isNotBlank() && retentionClass.length <= 32) {
            "retentionClass must contain 1..32 characters"
        }

        val eventHash = record.eventHash ?: canonicalEventHash(tenantGroupId, clinicId, record)
        findEventRow(tenantGroupId, clinicId, record.memberId, record.eventId, record.sourceVersion)
            ?.let { row ->
                if (row[BookingReliabilityEvents.eventHash] != eventHash) {
                    throw BookingReliabilityIdempotencyConflictException(
                        "event identity is already bound to a different event payload",
                    )
                }
                return row.toEventRecord()
            }

        val id =
            BookingReliabilityEvents.insertAndGetId {
                it[BookingReliabilityEvents.tenantGroupId] = tenantGroupId
                it[BookingReliabilityEvents.clinicId] = clinicId
                it[memberId] = record.memberId.value
                it[eventId] = record.eventId
                it[appointmentId] = record.appointmentId
                it[eventType] = record.eventType
                it[responsibility] = record.responsibility
                it[scheduledStartAt] = record.scheduledStartAt
                it[occurredAt] = record.occurredAt
                it[sourceVersion] = record.sourceVersion
                it[BookingReliabilityEvents.eventHash] = eventHash
                it[eventSource] = record.source
                it[BookingReliabilityEvents.correlationId] = correlationId
                it[BookingReliabilityEvents.retentionClass] = retentionClass
            }.value
        return BookingReliabilityEvents
            .selectAll()
            .where { BookingReliabilityEvents.id eq id }
            .single()
            .toEventRecord()
    }

    fun findEvents(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        fromInclusive: Instant,
        untilInclusive: Instant,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): List<BookingReliabilityEventRecord> {
        validateScope(tenantGroupId, clinicId)
        require(!untilInclusive.isBefore(fromInclusive)) {
            "untilInclusive must be at or after fromInclusive"
        }
        require(limit in 1..DEFAULT_HISTORY_LIMIT) {
            "limit must be in 1..$DEFAULT_HISTORY_LIMIT"
        }
        return BookingReliabilityEvents
            .selectAll()
            .where {
                (BookingReliabilityEvents.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityEvents.clinicId eq clinicId) and
                    (BookingReliabilityEvents.memberId eq memberId.value) and
                    (BookingReliabilityEvents.occurredAt greater fromInclusive.minusNanos(1)) and
                    (BookingReliabilityEvents.occurredAt lessEq untilInclusive)
            }
            .orderBy(
                BookingReliabilityEvents.occurredAt to SortOrder.ASC,
                BookingReliabilityEvents.eventId to SortOrder.ASC,
                BookingReliabilityEvents.sourceVersion to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toEventRecord() }
    }

    fun saveDecision(
        record: BookingReliabilityDecisionRecord,
        actorRef: String = "booking-reliability-evaluator",
        correlationId: String? = null,
    ): BookingReliabilityDecisionRecord {
        require(actorRef.isNotBlank() && actorRef.length <= 128) { "actorRef must contain 1..128 characters" }
        require(correlationId == null || correlationId.length <= 160) {
            "correlationId must not exceed 160 characters"
        }
        findDecisionRow(record.tenantGroupId, record.clinicId, record.memberId, record.decisionDigest)
            ?.let { return it.toDecisionRecord() }

        val id =
            BookingReliabilityDecisions.insertAndGetId {
                it[tenantGroupId] = record.tenantGroupId
                it[clinicId] = record.clinicId
                it[memberId] = record.memberId.value
                it[policyVersionId] = record.policyVersionId
                it[policyHash] = record.policyHash
                it[evaluatedAt] = record.evaluatedAt
                it[verdict] = record.verdict
                it[reasonCodesCsv] = record.reasonCodes.toReasonCodesCsv()
                it[triggerAppointmentIdsCsv] = record.triggers.joinToString(",") { trigger ->
                    trigger.appointmentId.toString()
                }
                it[triggerTypesCsv] = record.triggers.joinToString(",") { trigger -> trigger.type.name }
                it[noShowCount] = record.noShowCount
                it[lateCancellationCount] = record.lateCancellationCount
                it[effectiveFrom] = record.effectiveFrom
                it[expiresAt] = record.expiresAt
                it[decisionDigest] = record.decisionDigest
                it[hasAdditionalTriggers] = record.hasAdditionalTriggers
                it[auditCursor] = record.auditCursor
                it[BookingReliabilityDecisions.actorRef] = actorRef
                it[BookingReliabilityDecisions.correlationId] = correlationId
            }.value
        return requireNotNull(findDecision(id))
    }

    fun findDecision(decisionId: Long): BookingReliabilityDecisionRecord? =
        BookingReliabilityDecisions
            .selectAll()
            .where { BookingReliabilityDecisions.id eq decisionId }
            .singleOrNull()
            ?.toDecisionRecord()

    fun findDecisionByDigest(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        decisionDigest: String,
    ): BookingReliabilityDecisionRecord? =
        findDecisionRow(tenantGroupId, clinicId, memberId, decisionDigest)?.toDecisionRecord()

    fun findLatestDecision(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
    ): BookingReliabilityDecisionRecord? {
        validateScope(tenantGroupId, clinicId)
        return BookingReliabilityDecisions
            .selectAll()
            .where {
                (BookingReliabilityDecisions.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityDecisions.clinicId eq clinicId) and
                    (BookingReliabilityDecisions.memberId eq memberId.value)
            }
            .orderBy(
                BookingReliabilityDecisions.evaluatedAt to SortOrder.DESC,
                BookingReliabilityDecisions.id to SortOrder.DESC,
            )
            .limit(1)
            .singleOrNull()
            ?.toDecisionRecord()
    }

    /**
     * 한 page의 member를 한 번에 조회해 각 member의 최신 decision만 반환합니다.
     *
     * caller가 전달한 scope와 평가 시각 이전 조건을 SQL에 포함하고, 동일 member의
     * tie는 evaluatedAt DESC, id DESC로 결정합니다. member별 추가 조회는 하지 않습니다.
     */
    fun findLatestDecisions(
        tenantGroupId: Long,
        clinicId: Long,
        memberIds: Collection<MemberId>,
        evaluatedAt: Instant,
    ): Map<MemberId, BookingReliabilityDecisionRecord> {
        validateScope(tenantGroupId, clinicId)
        val distinctMemberIds = memberIds.distinct()
        require(distinctMemberIds.isNotEmpty()) { "memberIds must not be empty" }
        require(distinctMemberIds.size <= 500) { "memberIds must not exceed 500" }
        val values = distinctMemberIds.map(MemberId::value)
        return BookingReliabilityDecisions
            .selectAll()
            .where {
                (BookingReliabilityDecisions.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityDecisions.clinicId eq clinicId) and
                    (BookingReliabilityDecisions.memberId inList values) and
                    (BookingReliabilityDecisions.evaluatedAt lessEq evaluatedAt)
            }
            .orderBy(
                BookingReliabilityDecisions.memberId to SortOrder.ASC,
                BookingReliabilityDecisions.evaluatedAt to SortOrder.DESC,
                BookingReliabilityDecisions.id to SortOrder.DESC,
            )
            .asSequence()
            .map { it.toDecisionRecord() }
            .distinctBy { it.memberId }
            .associateBy { it.memberId }
    }

    /**
     * booking command와 override command가 같은 reliability head를 직렬화하도록 잠급니다.
     * 호출자는 이 조회 결과와 decision stamp 검증을 같은 transaction 안에서 수행해야 합니다.
     */
    fun findLatestDecisionForUpdate(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
    ): BookingReliabilityDecisionRecord? {
        validateScope(tenantGroupId, clinicId)
        return BookingReliabilityDecisions
            .selectAll()
            .where {
                (BookingReliabilityDecisions.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityDecisions.clinicId eq clinicId) and
                    (BookingReliabilityDecisions.memberId eq memberId.value)
            }
            .orderBy(
                BookingReliabilityDecisions.evaluatedAt to SortOrder.DESC,
                BookingReliabilityDecisions.id to SortOrder.DESC,
            )
            .limit(1)
            .forUpdate()
            .singleOrNull()
            ?.toDecisionRecord()
    }

    /** actuator health가 식별자 없이 읽을 수 있는 bounded 운영 집계입니다. */
    fun summarizeOperations(): BookingReliabilityOperationalSummary {
        val dbNow = currentDatabaseTimestamp()
        val backlogStates = listOf(
            io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus.PENDING,
            io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus.RETRY_WAIT,
            io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus.RUNNING,
        )
        val pendingJobs = BookingReliabilityReevaluationJobs
            .selectAll()
            .where { BookingReliabilityReevaluationJobs.status inList backlogStates }
            .count()
        val oldestDueAt = BookingReliabilityReevaluationJobs
            .selectAll()
            .where {
                BookingReliabilityReevaluationJobs.status inList backlogStates.take(2)
            }
            .orderBy(BookingReliabilityReevaluationJobs.nextAttemptAt to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.get(BookingReliabilityReevaluationJobs.nextAttemptAt)
        val unavailableDecisions = BookingReliabilityDecisions
            .selectAll()
            .where { BookingReliabilityDecisions.verdict eq BookingReliabilityVerdict.UNAVAILABLE }
            .count()
        val deadLetterJobs = BookingReliabilityReevaluationJobs
            .selectAll()
            .where {
                BookingReliabilityReevaluationJobs.status eq
                    io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus.DEAD_LETTER
            }
            .count()
        return BookingReliabilityOperationalSummary(
            pendingJobs = pendingJobs,
            oldestBacklogAge = oldestDueAt
                ?.let { Duration.between(it, dbNow).coerceAtLeast(Duration.ZERO) }
                ?: Duration.ZERO,
            unavailableDecisions = unavailableDecisions,
            deadLetterJobs = deadLetterJobs,
        )
    }

    fun appendOverride(record: BookingReliabilityOverrideAuditRecord): BookingReliabilityOverrideAuditRecord {
        findOverrideCommandRow(record.tenantGroupId, record.clinicId, record.memberId, record.idempotencyKeyHash)
            ?.let { row ->
                if (row[BookingReliabilityOverrides.commandHash] != record.commandHash) {
                    throw BookingReliabilityIdempotencyConflictException(
                        "idempotency key is already bound to a different override command hash",
                    )
                }
                return row.toOverrideAuditRecord()
            }

        val currentDecision = findLatestDecisionForUpdate(
            record.tenantGroupId,
            record.clinicId,
            record.memberId,
        )
        if (record.expectedVersion > 0L && currentDecision?.decisionId != record.expectedVersion) {
            throw BookingReliabilityStaleDecisionException("expected decision version is stale")
        }
        if (record.previousDecisionDigest != null &&
            currentDecision?.decisionDigest != record.previousDecisionDigest
        ) {
            throw BookingReliabilityStaleDecisionException("expected decision digest is stale")
        }

        val id =
            BookingReliabilityOverrides.insertAndGetId {
                it[tenantGroupId] = record.tenantGroupId
                it[clinicId] = record.clinicId
                it[memberId] = record.memberId.value
                it[decisionId] = record.decisionId
                it[policyVersionId] = record.policyVersionId
                it[previousDecisionDigest] = record.previousDecisionDigest
                it[action] = record.action
                it[verdict] = record.verdict
                it[reasonCode] = record.reasonCode
                it[actorId] = record.actorId
                it[actorType] = record.actorType
                it[idempotencyKeyHash] = record.idempotencyKeyHash
                it[commandHash] = record.commandHash
                it[resultDigest] = record.resultDigest
                it[expectedVersion] = record.expectedVersion
                it[effectiveFrom] = record.effectiveFrom
                it[expiresAt] = record.expiresAt
                it[correlationId] = record.correlationId
            }.value
        return BookingReliabilityOverrides
            .selectAll()
            .where { BookingReliabilityOverrides.id eq id }
            .single()
            .toOverrideAuditRecord()
    }

    fun findOverrideAudit(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        limit: Int = DEFAULT_AUDIT_LIMIT,
    ): List<BookingReliabilityOverrideAuditRecord> {
        validateScope(tenantGroupId, clinicId)
        require(limit in 1..DEFAULT_AUDIT_LIMIT) {
            "limit must be in 1..$DEFAULT_AUDIT_LIMIT"
        }
        return BookingReliabilityOverrides
            .selectAll()
            .where {
                (BookingReliabilityOverrides.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityOverrides.clinicId eq clinicId) and
                    (BookingReliabilityOverrides.memberId eq memberId.value)
            }
            .orderBy(
                BookingReliabilityOverrides.effectiveFrom to SortOrder.ASC,
                BookingReliabilityOverrides.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toOverrideAuditRecord() }
    }

    fun findLatestActiveOverride(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        at: Instant,
    ): BookingReliabilityOverrideRecord? {
        validateScope(tenantGroupId, clinicId)
        val latest =
            BookingReliabilityOverrides
                .selectAll()
                .where {
                    (BookingReliabilityOverrides.tenantGroupId eq tenantGroupId) and
                        (BookingReliabilityOverrides.clinicId eq clinicId) and
                        (BookingReliabilityOverrides.memberId eq memberId.value) and
                        (BookingReliabilityOverrides.effectiveFrom lessEq at) and
                        (
                            BookingReliabilityOverrides.expiresAt.isNull() or
                                (BookingReliabilityOverrides.expiresAt greater at)
                            )
                }
                .orderBy(
                    BookingReliabilityOverrides.effectiveFrom to SortOrder.DESC,
                    BookingReliabilityOverrides.id to SortOrder.DESC,
                )
                .limit(1)
                .singleOrNull()
                ?: return null
        return latest.toOverrideAuditRecord().toOverrideRecord()
    }

    private fun findEventRow(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        eventId: String,
        sourceVersion: Long,
    ): ResultRow? =
        BookingReliabilityEvents
            .selectAll()
            .where {
                (BookingReliabilityEvents.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityEvents.clinicId eq clinicId) and
                    (BookingReliabilityEvents.memberId eq memberId.value) and
                    (BookingReliabilityEvents.eventId eq eventId) and
                    (BookingReliabilityEvents.sourceVersion eq sourceVersion)
            }
            .singleOrNull()

    private fun findDecisionRow(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        decisionDigest: String,
    ): ResultRow? {
        validateScope(tenantGroupId, clinicId)
        require(SHA256_REGEX.matches(decisionDigest)) { "decisionDigest must be lowercase SHA-256" }
        return BookingReliabilityDecisions
            .selectAll()
            .where {
                (BookingReliabilityDecisions.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityDecisions.clinicId eq clinicId) and
                    (BookingReliabilityDecisions.memberId eq memberId.value) and
                    (BookingReliabilityDecisions.decisionDigest eq decisionDigest)
            }
            .singleOrNull()
    }

    private fun findOverrideCommandRow(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        idempotencyKeyHash: String,
    ): ResultRow? {
        validateScope(tenantGroupId, clinicId)
        require(SHA256_REGEX.matches(idempotencyKeyHash)) {
            "idempotencyKeyHash must be lowercase SHA-256"
        }
        return BookingReliabilityOverrides
            .selectAll()
            .where {
                (BookingReliabilityOverrides.tenantGroupId eq tenantGroupId) and
                    (BookingReliabilityOverrides.clinicId eq clinicId) and
                    (BookingReliabilityOverrides.memberId eq memberId.value) and
                    (BookingReliabilityOverrides.idempotencyKeyHash eq idempotencyKeyHash)
            }
            .singleOrNull()
    }
    companion object {
        const val DEFAULT_HISTORY_LIMIT = 100
        const val DEFAULT_AUDIT_LIMIT = 100
    }
}

data class BookingReliabilityOperationalSummary(
    val pendingJobs: Long,
    val oldestBacklogAge: Duration,
    val unavailableDecisions: Long,
    val deadLetterJobs: Long,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

/** 동일 idempotency identity에 서로 다른 bounded payload가 제출되었습니다. */
class BookingReliabilityIdempotencyConflictException(message: String) : RuntimeException(message)

/** 직원 명령이 최신 decision digest/version과 일치하지 않습니다. */
class BookingReliabilityStaleDecisionException(message: String) : RuntimeException(message)

private fun canonicalEventHash(
    tenantGroupId: Long,
    clinicId: Long,
    record: BookingReliabilityEventRecord,
): String {
    val canonical = listOf(
        tenantGroupId.toString(),
        clinicId.toString(),
        record.memberId.value,
        record.eventId,
        record.sourceVersion.toString(),
        record.appointmentId.toString(),
        record.eventType.name,
        record.responsibility.name,
        record.scheduledStartAt.toString(),
        record.occurredAt.toString(),
        record.source.name,
    ).joinToString("\u0000")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun ResultRow.toEventRecord(): BookingReliabilityEventRecord =
    BookingReliabilityEventRecord(
        appointmentId = this[BookingReliabilityEvents.appointmentId],
        memberId = MemberId(this[BookingReliabilityEvents.memberId]),
        eventType = this[BookingReliabilityEvents.eventType],
        responsibility = this[BookingReliabilityEvents.responsibility],
        scheduledStartAt = this[BookingReliabilityEvents.scheduledStartAt],
        occurredAt = this[BookingReliabilityEvents.occurredAt],
        eventId = this[BookingReliabilityEvents.eventId],
        sourceVersion = this[BookingReliabilityEvents.sourceVersion],
        source = this[BookingReliabilityEvents.eventSource],
        eventHash = this[BookingReliabilityEvents.eventHash],
    )

private fun ResultRow.toDecisionRecord(): BookingReliabilityDecisionRecord =
    BookingReliabilityDecisionRecord(
        tenantGroupId = this[BookingReliabilityDecisions.tenantGroupId],
        clinicId = this[BookingReliabilityDecisions.clinicId],
        memberId = MemberId(this[BookingReliabilityDecisions.memberId]),
        policyVersionId = this[BookingReliabilityDecisions.policyVersionId],
        policyHash = this[BookingReliabilityDecisions.policyHash],
        evaluatedAt = this[BookingReliabilityDecisions.evaluatedAt],
        verdict = this[BookingReliabilityDecisions.verdict],
        reasonCodes = parseReasonCodes(this[BookingReliabilityDecisions.reasonCodesCsv]),
        triggers = parseTriggers(
            appointmentIdsCsv = this[BookingReliabilityDecisions.triggerAppointmentIdsCsv],
            typesCsv = this[BookingReliabilityDecisions.triggerTypesCsv],
        ),
        noShowCount = this[BookingReliabilityDecisions.noShowCount],
        lateCancellationCount = this[BookingReliabilityDecisions.lateCancellationCount],
        effectiveFrom = this[BookingReliabilityDecisions.effectiveFrom],
        expiresAt = this[BookingReliabilityDecisions.expiresAt],
        decisionDigest = this[BookingReliabilityDecisions.decisionDigest],
        hasAdditionalTriggers = this[BookingReliabilityDecisions.hasAdditionalTriggers],
        auditCursor = this[BookingReliabilityDecisions.auditCursor],
        decisionId = this[BookingReliabilityDecisions.id].value,
    )

private fun ResultRow.toOverrideAuditRecord(): BookingReliabilityOverrideAuditRecord =
    BookingReliabilityOverrideAuditRecord(
        tenantGroupId = this[BookingReliabilityOverrides.tenantGroupId],
        clinicId = this[BookingReliabilityOverrides.clinicId],
        memberId = MemberId(this[BookingReliabilityOverrides.memberId]),
        action = this[BookingReliabilityOverrides.action],
        verdict = this[BookingReliabilityOverrides.verdict],
        reasonCode = this[BookingReliabilityOverrides.reasonCode],
        policyVersionId = this[BookingReliabilityOverrides.policyVersionId],
        effectiveFrom = this[BookingReliabilityOverrides.effectiveFrom],
        expiresAt = this[BookingReliabilityOverrides.expiresAt],
        actorId = this[BookingReliabilityOverrides.actorId],
        actorType = this[BookingReliabilityOverrides.actorType],
        idempotencyKeyHash = this[BookingReliabilityOverrides.idempotencyKeyHash],
        commandHash = this[BookingReliabilityOverrides.commandHash],
        resultDigest = this[BookingReliabilityOverrides.resultDigest],
        expectedVersion = this[BookingReliabilityOverrides.expectedVersion],
        decisionId = this[BookingReliabilityOverrides.decisionId],
        previousDecisionDigest = this[BookingReliabilityOverrides.previousDecisionDigest],
        correlationId = this[BookingReliabilityOverrides.correlationId],
        auditId = this[BookingReliabilityOverrides.id].value,
    )

private fun Set<BookingReliabilityReasonCode>.toReasonCodesCsv(): String =
    sortedBy { it.name }.joinToString(",") { it.name }

private fun parseReasonCodes(csv: String): Set<BookingReliabilityReasonCode> =
    csv.split(",")
        .filter { it.isNotBlank() }
        .mapTo(linkedSetOf()) { BookingReliabilityReasonCode.valueOf(it) }

private fun parseTriggers(
    appointmentIdsCsv: String,
    typesCsv: String,
): List<BookingReliabilityTrigger> {
    if (appointmentIdsCsv.isBlank() && typesCsv.isBlank()) return emptyList()
    val appointmentIds = appointmentIdsCsv.split(",").filter { it.isNotBlank() }.map { it.toLong() }
    val types = typesCsv.split(",").filter { it.isNotBlank() }.map { BookingReliabilityTriggerType.valueOf(it) }
    require(appointmentIds.size == types.size) { "trigger appointment IDs and types are inconsistent" }
    return appointmentIds.zip(types) { appointmentId, type ->
        BookingReliabilityTrigger(appointmentId = appointmentId, type = type)
    }
}

private fun validateScope(tenantGroupId: Long, clinicId: Long) {
    require(tenantGroupId > 0) { "tenantGroupId must be positive" }
    require(clinicId > 0) { "clinicId must be positive" }
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
