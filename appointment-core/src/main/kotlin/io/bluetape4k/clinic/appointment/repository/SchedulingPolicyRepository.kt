package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EffectiveSchedulingPolicySnapshotRecord
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.time.Instant

/**
 * Caller-transaction persistence primitives for scheduling policies.
 *
 * Every method must execute inside a caller-owned Exposed `transaction {}`.
 * This class intentionally does not open transactions because activation must
 * combine definition checks, scope-head CAS, snapshot/outbox writes, and
 * command completion atomically. Callers acquiring both scopes must use
 * [lockScopeHeads], which always locks tenant before clinic to avoid inversion.
 */
class SchedulingPolicyRepository {

    /**
     * Inserts one policy definition after validating its cross-dialect scope key.
     *
     * The unique definition identity is tenant, scope, non-null clinic sentinel,
     * kind, and version. Published payload bytes must be represented by a new
     * version rather than updated in place.
     */
    fun createDefinition(record: SchedulingPolicyDefinitionRecord): SchedulingPolicyDefinitionRecord {
        validateDefinitionRecord(record)
        val definitionId = SchedulingPolicyDefinitions.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[scope] = record.scope
            it[clinicId] = record.clinicId
            it[clinicScopeKey] = record.clinicScopeKey
            it[policyKind] = record.kind
            it[version] = record.version
            it[schemaVersion] = record.schemaVersion
            it[lifecycle] = record.lifecycle
            it[effectiveFrom] = record.effectiveFrom
            it[effectiveUntil] = record.effectiveUntil
            it[revision] = record.revision
            it[payloadHash] = record.payloadHash
            it[payloadJson] = record.payloadJson
            it[createdByActorId] = record.createdByActorId
            it[createdByActorRole] = record.createdByActorRole
            it[changeReason] = record.changeReason
        }.value
        return findDefinition(definitionId)
            ?: error("Inserted policy definition $definitionId was not readable")
    }

    /** Returns a definition by database identity inside the current transaction. */
    fun findDefinition(definitionId: Long): SchedulingPolicyDefinitionRecord? =
        SchedulingPolicyDefinitions
            .selectAll()
            .where { SchedulingPolicyDefinitions.id eq definitionId }
            .singleOrNull()
            ?.toSchedulingPolicyDefinitionRecord()

    /**
     * Appends approval evidence for exactly one draft revision and actor.
     *
     * Duplicate actor approval for the same revision is rejected by the
     * database. Approvals from older revisions remain queryable but stale.
     */
    fun addApproval(record: SchedulingPolicyApprovalRecord): SchedulingPolicyApprovalRecord {
        require(record.definitionId > 0) { "definitionId must be positive" }
        require(record.draftRevision > 0) { "draftRevision must be positive" }
        require(record.actorId.isNotBlank() && record.actorId.length <= 160) {
            "actorId must contain 1..160 characters"
        }
        require(record.assuranceLevel.isNotBlank() && record.assuranceLevel.length <= 64) {
            "assuranceLevel must contain 1..64 characters"
        }
        val approvalId = SchedulingPolicyApprovals.insertAndGetId {
            it[definitionId] = record.definitionId
            it[draftRevision] = record.draftRevision
            it[actorId] = record.actorId
            it[actorRole] = record.actorRole
            it[assuranceLevel] = record.assuranceLevel
            it[approvedAt] = record.approvedAt
        }.value
        return SchedulingPolicyApprovals
            .selectAll()
            .where { SchedulingPolicyApprovals.id eq approvalId }
            .single()
            .toSchedulingPolicyApprovalRecord()
    }

    /** Returns approval evidence for one exact definition revision. */
    fun findApprovals(
        definitionId: Long,
        draftRevision: Long,
    ): List<SchedulingPolicyApprovalRecord> =
        SchedulingPolicyApprovals
            .selectAll()
            .where {
                (SchedulingPolicyApprovals.definitionId eq definitionId) and
                    (SchedulingPolicyApprovals.draftRevision eq draftRevision)
            }
            .orderBy(SchedulingPolicyApprovals.id, SortOrder.ASC)
            .map { it.toSchedulingPolicyApprovalRecord() }

    /**
     * Bootstraps and locks the scope serialization row.
     *
     * `insertIgnore` makes first access race-safe. The subsequent `FOR UPDATE`
     * lock must remain held by the caller transaction through all overlap,
     * generation, snapshot, command-result, and outbox writes.
     */
    fun lockScopeHead(scope: PolicyScopeRef): SchedulingPolicyScopeHeadRecord {
        bootstrapScopeHead(scope)
        return SchedulingPolicyScopeHeads
            .selectAll()
            .where { scopeHeadPredicate(scope) }
            .forUpdate()
            .single()
            .toSchedulingPolicyScopeHeadRecord()
    }

    /**
     * Locks distinct scopes in deterministic tenant-before-clinic order.
     *
     * Passing clinic first does not change lock order. Duplicate references are
     * collapsed so the same row is never acquired twice.
     */
    fun lockScopeHeads(vararg scopes: PolicyScopeRef): List<SchedulingPolicyScopeHeadRecord> =
        scopes
            .distinct()
            .sortedWith(compareBy<PolicyScopeRef>({ it.scope != PolicyScope.TENANT_DEFAULT }, { it.clinicScopeKey }))
            .map(::lockScopeHead)

    /**
     * Advances both revision and generation iff [expectedRevision] is current.
     *
     * The scope row is locked first. A mismatch throws
     * [PolicyScopeHeadConflictException] and changes no counter.
     */
    fun compareAndIncrementGeneration(
        scope: PolicyScopeRef,
        expectedRevision: Long,
    ): SchedulingPolicyScopeHeadRecord {
        require(expectedRevision >= 0) { "expectedRevision must be non-negative" }
        val current = lockScopeHead(scope)
        if (current.revision != expectedRevision) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        val affected = SchedulingPolicyScopeHeads.update({
            scopeHeadPredicate(scope) and
                (SchedulingPolicyScopeHeads.revision eq expectedRevision)
        }) {
            it[revision] = current.revision + 1
            it[generation] = current.generation + 1
            it.update(updatedAt, CurrentTimestamp)
        }
        if (affected != 1) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        return lockScopeHead(scope)
    }

    /**
     * Finds active or scheduled definitions whose half-open validity interval
     * overlaps `[from, until)`. A null [until] means an unbounded query end.
     *
     * Callers must hold the matching scope-head lock before using this result to
     * decide an activation winner.
     */
    fun findOverlappingPublishedDefinitions(
        scope: PolicyScopeRef,
        kind: SchedulingPolicyKind,
        from: Instant,
        until: Instant?,
    ): List<SchedulingPolicyDefinitionRecord> {
        require(until == null || until > from) { "until must be later than from" }
        val startsBeforeQueryEnd = until?.let {
            SchedulingPolicyDefinitions.effectiveFrom less it
        } ?: Op.TRUE
        val endsAfterQueryStart =
            SchedulingPolicyDefinitions.effectiveUntil.isNull() or
                (SchedulingPolicyDefinitions.effectiveUntil greater from)
        return SchedulingPolicyDefinitions
            .selectAll()
            .where {
                (SchedulingPolicyDefinitions.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyDefinitions.scope eq scope.scope) and
                    (SchedulingPolicyDefinitions.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyDefinitions.policyKind eq kind) and
                    (SchedulingPolicyDefinitions.lifecycle inList listOf(
                        PolicyLifecycle.SCHEDULED,
                        PolicyLifecycle.ACTIVE,
                    )) and
                    startsBeforeQueryEnd and
                    endsAfterQueryStart
            }
            .orderBy(SchedulingPolicyDefinitions.effectiveFrom, SortOrder.ASC)
            .map { it.toSchedulingPolicyDefinitionRecord() }
    }

    /**
     * Inserts or reuses an immutable snapshot by scoped canonical hash.
     *
     * If the hash already exists, the original bytes and generation metadata
     * win. This makes retry behavior idempotent and prevents an update path from
     * rewriting historical scheduling evidence.
     */
    @Suppress("LongParameterList")
    fun saveSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        tenantGeneration: Long,
        clinicGeneration: Long,
        sourceVersionsJson: String,
        sourceByPathJson: String,
        disabledFeaturesJson: String,
        warningsJson: String,
        payloadJson: String,
        snapshotHash: String,
    ): EffectiveSchedulingPolicySnapshotRecord {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(serviceAt >= decisionAt) { "serviceAt must not precede decisionAt" }
        require(tenantGeneration > 0) { "tenantGeneration must be positive" }
        require(clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(SHA256_REGEX.matches(snapshotHash)) { "snapshotHash must be lowercase SHA-256" }
        findSnapshot(tenantGroupId, clinicId, snapshotHash)?.let { return it }
        insertSnapshot(
            tenantGroupId,
            clinicId,
            decisionAt,
            serviceAt,
            tenantGeneration,
            clinicGeneration,
            sourceVersionsJson,
            sourceByPathJson,
            disabledFeaturesJson,
            warningsJson,
            payloadJson,
            snapshotHash,
        )
        return requireNotNull(findSnapshot(tenantGroupId, clinicId, snapshotHash)) {
            "Snapshot insert did not produce a readable row"
        }
    }

    /** Returns one immutable snapshot by tenant, clinic, and canonical hash. */
    fun findSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        snapshotHash: String,
    ): EffectiveSchedulingPolicySnapshotRecord? =
        EffectiveSchedulingPolicySnapshots
            .selectAll()
            .where {
                (EffectiveSchedulingPolicySnapshots.tenantGroupId eq tenantGroupId) and
                    (EffectiveSchedulingPolicySnapshots.clinicId eq clinicId) and
                    (EffectiveSchedulingPolicySnapshots.snapshotHash eq snapshotHash)
            }
            .singleOrNull()
            ?.toEffectiveSchedulingPolicySnapshotRecord()

    private fun scopeHeadPredicate(scope: PolicyScopeRef): Op<Boolean> =
        (SchedulingPolicyScopeHeads.tenantGroupId eq scope.tenantGroupId) and
            (SchedulingPolicyScopeHeads.scope eq scope.scope) and
            (SchedulingPolicyScopeHeads.clinicScopeKey eq scope.clinicScopeKey)

    private fun bootstrapScopeHead(scope: PolicyScopeRef) {
        val insertBody: SchedulingPolicyScopeHeads.(UpdateBuilder<*>) -> Unit = {
            it[tenantGroupId] = scope.tenantGroupId
            it[SchedulingPolicyScopeHeads.scope] = scope.scope
            it[clinicScopeKey] = scope.clinicScopeKey
            it[revision] = 0L
            it[generation] = 0L
        }
        if (isH2Dialect()) {
            val exists = SchedulingPolicyScopeHeads.selectAll()
                .where { scopeHeadPredicate(scope) }
                .limit(1)
                .any()
            if (!exists) {
                try {
                    SchedulingPolicyScopeHeads.insert(insertBody)
                } catch (error: ExposedSQLException) {
                    val competingInsertWon = SchedulingPolicyScopeHeads.selectAll()
                        .where { scopeHeadPredicate(scope) }
                        .limit(1)
                        .any()
                    if (!competingInsertWon) throw error
                }
            }
        } else {
            SchedulingPolicyScopeHeads.insertIgnore(insertBody)
        }
    }

    @Suppress("LongParameterList")
    private fun insertSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        tenantGeneration: Long,
        clinicGeneration: Long,
        sourceVersionsJson: String,
        sourceByPathJson: String,
        disabledFeaturesJson: String,
        warningsJson: String,
        payloadJson: String,
        snapshotHash: String,
    ) {
        val insertBody:
            EffectiveSchedulingPolicySnapshots.(UpdateBuilder<*>) -> Unit = {
                it[EffectiveSchedulingPolicySnapshots.tenantGroupId] = tenantGroupId
                it[EffectiveSchedulingPolicySnapshots.clinicId] = clinicId
                it[EffectiveSchedulingPolicySnapshots.decisionAt] = decisionAt
                it[EffectiveSchedulingPolicySnapshots.serviceAt] = serviceAt
                it[EffectiveSchedulingPolicySnapshots.tenantGeneration] = tenantGeneration
                it[EffectiveSchedulingPolicySnapshots.clinicGeneration] = clinicGeneration
                it[EffectiveSchedulingPolicySnapshots.sourceVersionsJson] = sourceVersionsJson
                it[EffectiveSchedulingPolicySnapshots.sourceByPathJson] = sourceByPathJson
                it[EffectiveSchedulingPolicySnapshots.disabledFeaturesJson] = disabledFeaturesJson
                it[EffectiveSchedulingPolicySnapshots.warningsJson] = warningsJson
                it[EffectiveSchedulingPolicySnapshots.payloadJson] = payloadJson
                it[EffectiveSchedulingPolicySnapshots.snapshotHash] = snapshotHash
            }
        if (isH2Dialect()) {
            try {
                EffectiveSchedulingPolicySnapshots.insert(insertBody)
            } catch (error: ExposedSQLException) {
                val competingInsertWon = findSnapshot(tenantGroupId, clinicId, snapshotHash) != null
                if (!competingInsertWon) throw error
            }
        } else {
            EffectiveSchedulingPolicySnapshots.insertIgnore(insertBody)
        }
    }

    private fun isH2Dialect(): Boolean =
        TransactionManager.current().db.dialect.name.equals("h2", ignoreCase = true)

    private fun validateDefinitionRecord(record: SchedulingPolicyDefinitionRecord) {
        val scope = PolicyScopeRef(record.tenantGroupId, record.scope, record.clinicId)
        require(record.clinicScopeKey == scope.clinicScopeKey) {
            "clinicScopeKey must match scope and clinicId"
        }
        require(record.version > 0) { "version must be positive" }
        require(record.schemaVersion > 0) { "schemaVersion must be positive" }
        require(record.revision > 0) { "revision must be positive" }
        require(record.effectiveUntil == null || record.effectiveUntil > record.effectiveFrom) {
            "effectiveUntil must be later than effectiveFrom"
        }
        require(SHA256_REGEX.matches(record.payloadHash)) { "payloadHash must be lowercase SHA-256" }
        require(record.payloadJson.toByteArray().size <= MAX_JSON_BYTES) {
            "payloadJson exceeds $MAX_JSON_BYTES bytes"
        }
        require(record.createdByActorId.isNotBlank() && record.createdByActorId.length <= 160) {
            "createdByActorId must contain 1..160 characters"
        }
        require(record.changeReason.isNotBlank() && record.changeReason.length <= 1000) {
            "changeReason must contain 1..1000 characters"
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 256 * 1024
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * Optimistic scope-head mismatch.
 *
 * @property scope Scope whose revision changed.
 * @property expectedRevision Revision supplied by the caller.
 * @property actualRevision Revision observed under row lock.
 */
class PolicyScopeHeadConflictException(
    val scope: PolicyScopeRef,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Policy scope head revision conflict: expected=$expectedRevision, actual=$actualRevision"
)
