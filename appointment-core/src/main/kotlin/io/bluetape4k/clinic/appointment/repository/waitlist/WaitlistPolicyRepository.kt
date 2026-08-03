package io.bluetape4k.clinic.appointment.repository.waitlist

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyVersions
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.DecodedWaitlistPolicyDocument
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyDocumentCodec
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyValidationException
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * waitlist delivery policy version을 caller-owned Exposed transaction 안에서 다룬다.
 *
 * 이 repository는 트랜잭션을 열지 않는다. activation 호출자는 정책 검증, clinic row lock,
 * generation CAS, outbox/event 기록을 하나의 `transaction {}` 경계에 묶어야 한다.
 */
class WaitlistPolicyRepository {

    fun insertDraft(
        scope: ClinicWaitlistScope,
        policy: DecodedWaitlistPolicyDocument,
        effectiveFrom: Instant,
        effectiveUntil: Instant?,
        actor: ActorRef,
        now: Instant = Instant.now(),
    ): ClinicWaitlistPolicyRecord {
        require(effectiveUntil == null || effectiveUntil > effectiveFrom) {
            "effectiveUntil must be later than effectiveFrom"
        }
        lockClinic(scope)
        val policyVersion = nextPolicyVersion(scope)
        val draftGeneration = -policyVersion
        val policyId = WaitlistPolicyVersions.insertAndGetId {
            it[tenantGroupId] = EntityID(scope.tenantGroupId, TenantGroups)
            it[clinicId] = EntityID(scope.clinicId, Clinics)
            it[generation] = draftGeneration
            it[WaitlistPolicyVersions.policyVersion] = policyVersion
            it[policyDigest] = policy.digest
            it[urgencyWeight] = policy.document.urgencyWeight
            it[recoveryWeight] = policy.document.recoveryWeight
            it[benefitWeight] = policy.document.benefitWeight
            it[reliabilityWeight] = policy.document.reliabilityWeight
            it[waitingAgeWeight] = policy.document.waitingAgeWeight
            it[slotFitWeight] = policy.document.slotFitWeight
            it[status] = WaitlistPolicyState.DRAFT
            it[WaitlistPolicyVersions.effectiveFrom] = effectiveFrom
            it[WaitlistPolicyVersions.effectiveUntil] = effectiveUntil
            it[canonicalPolicyJson] = policy.canonicalJson
            it[createdBy] = actor.value
            it[createdAt] = now
        }.value
        appendEvent(
            scope = scope,
            policyVersion = policyVersion,
            eventType = "WAITLIST_POLICY_DRAFT_CREATED",
            actor = actor,
            correlationId = "policy:$policyId",
            fromGeneration = null,
            toGeneration = draftGeneration,
            reasonCode = "DRAFT_CREATED",
            payloadJson = """{"policyId":$policyId,"policyVersion":$policyVersion,"status":"DRAFT"}""",
            now = now,
        )
        return findById(scope, policyId) ?: error("Inserted waitlist policy $policyId was not readable")
    }

    fun activate(
        scope: ClinicWaitlistScope,
        policyId: Long,
        expectedGeneration: Long,
        actor: ActorRef,
        now: Instant = Instant.now(),
    ): ClinicWaitlistPolicyRecord {
        policyId.requirePositiveNumber("policyId")
        require(expectedGeneration >= 0L) { "expectedGeneration must be zero or positive" }
        lockClinic(scope)
        val draft = findByIdForUpdate(scope, policyId)
            ?.takeIf { it.status == WaitlistPolicyState.DRAFT }
            ?: throw WaitlistPolicyConflict()
        requireRankedProjectionSupport(draft)
        if (currentGeneration(scope) != expectedGeneration || overlapsActiveWindow(scope, draft)) {
            throw WaitlistPolicyConflict()
        }
        val nextGeneration = expectedGeneration + 1L
        val updated = WaitlistPolicyVersions.update({
            (WaitlistPolicyVersions.id eq policyId) and
                scopeCondition(scope) and
                (WaitlistPolicyVersions.status eq WaitlistPolicyState.DRAFT) and
                (WaitlistPolicyVersions.generation eq draft.generation)
        }) {
            it[status] = WaitlistPolicyState.ACTIVE
            it[generation] = nextGeneration
        }
        if (updated != 1) {
            throw WaitlistPolicyConflict()
        }
        appendEvent(
            scope = scope,
            policyVersion = draft.policyVersion,
            eventType = "WAITLIST_POLICY_ACTIVATED",
            actor = actor,
            correlationId = "policy:${draft.id}",
            fromGeneration = expectedGeneration,
            toGeneration = nextGeneration,
            reasonCode = "ACTIVATION",
            payloadJson = """{"policyId":${draft.id},"policyVersion":${draft.policyVersion}}""",
            now = now,
        )
        return findById(scope, policyId) ?: error("Activated waitlist policy $policyId was not readable")
    }

    fun findById(scope: ClinicWaitlistScope, policyId: Long): ClinicWaitlistPolicyRecord? {
        policyId.requirePositiveNumber("policyId")
        return WaitlistPolicyVersions
            .selectAll()
            .where { scopeCondition(scope) and (WaitlistPolicyVersions.id eq policyId) }
            .singleOrNull()
            ?.toRecord()
    }

    fun findActive(scope: ClinicWaitlistScope): ClinicWaitlistPolicyRecord? =
        WaitlistPolicyVersions
            .selectAll()
            .where { scopeCondition(scope) and (WaitlistPolicyVersions.status eq WaitlistPolicyState.ACTIVE) }
            .orderBy(
                WaitlistPolicyVersions.generation to SortOrder.DESC,
                WaitlistPolicyVersions.id to SortOrder.DESC,
            )
            .limit(1)
            .singleOrNull()
            ?.toRecord()

    private fun lockClinic(scope: ClinicWaitlistScope) {
        Clinics
            .selectAll()
            .where {
                (Clinics.id eq scope.clinicId) and
                    (Clinics.tenantGroupId eq scope.tenantGroupId)
            }
            .forUpdate()
            .singleOrNull()
            ?: throw WaitlistPolicyConflict()
    }

    private fun findByIdForUpdate(scope: ClinicWaitlistScope, policyId: Long): ClinicWaitlistPolicyRecord? =
        WaitlistPolicyVersions
            .selectAll()
            .where { scopeCondition(scope) and (WaitlistPolicyVersions.id eq policyId) }
            .forUpdate()
            .singleOrNull()
            ?.toRecord()

    private fun currentGeneration(scope: ClinicWaitlistScope): Long =
        findActive(scope)?.generation ?: 0L

    private fun overlapsActiveWindow(
        scope: ClinicWaitlistScope,
        draft: ClinicWaitlistPolicyRecord,
    ): Boolean =
        WaitlistPolicyVersions
            .selectAll()
            .where {
                scopeCondition(scope) and
                    (WaitlistPolicyVersions.status eq WaitlistPolicyState.ACTIVE) and
                    (WaitlistPolicyVersions.effectiveFrom less (draft.effectiveUntil ?: FAR_FUTURE)) and
                    (WaitlistPolicyVersions.effectiveUntil.isNull() or
                        (WaitlistPolicyVersions.effectiveUntil greater draft.effectiveFrom))
            }
            .limit(1)
            .any()

    private fun nextPolicyVersion(scope: ClinicWaitlistScope): Long =
        WaitlistPolicyVersions
            .selectAll()
            .where { scopeCondition(scope) }
            .orderBy(WaitlistPolicyVersions.policyVersion, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(WaitlistPolicyVersions.policyVersion)
            ?.plus(1L)
            ?: 1L

    private fun requireRankedProjectionSupport(policy: ClinicWaitlistPolicyRecord) {
        val document = policyCodec.decode(policy.canonicalPolicyJson).document
        if (document.reliabilityWeight > 0) {
            throw WaitlistPolicyValidationException(
                "waitlist policy activation requires a persisted booking reliability tier projection",
            )
        }
    }

    private fun appendEvent(
        scope: ClinicWaitlistScope,
        policyVersion: Long,
        eventType: String,
        actor: ActorRef,
        correlationId: String?,
        fromGeneration: Long?,
        toGeneration: Long?,
        reasonCode: String,
        payloadJson: String,
        now: Instant,
    ) {
        val digest = sha256(
            listOf(
                scope.tenantGroupId,
                scope.clinicId,
                policyVersion,
                eventType,
                correlationId.orEmpty(),
                fromGeneration?.toString().orEmpty(),
                toGeneration?.toString().orEmpty(),
                reasonCode,
                payloadJson,
                now.toString(),
            ).joinToString("|"),
        )
        WaitlistPolicyEvents.insert {
            it[tenantGroupId] = EntityID(scope.tenantGroupId, TenantGroups)
            it[clinicId] = EntityID(scope.clinicId, Clinics)
            it[WaitlistPolicyEvents.policyVersion] = policyVersion
            it[WaitlistPolicyEvents.eventType] = eventType
            it[actorRef] = actor.value
            it[WaitlistPolicyEvents.correlationId] = correlationId
            it[WaitlistPolicyEvents.fromGeneration] = fromGeneration
            it[WaitlistPolicyEvents.toGeneration] = toGeneration
            it[WaitlistPolicyEvents.reasonCode] = reasonCode
            it[eventDigest] = digest
            it[WaitlistPolicyEvents.payloadJson] = payloadJson
            it[occurredAt] = now
        }
    }

    private fun scopeCondition(scope: ClinicWaitlistScope): Op<Boolean> =
        (WaitlistPolicyVersions.tenantGroupId eq scope.tenantGroupId) and
            (WaitlistPolicyVersions.clinicId eq scope.clinicId)

    private fun ResultRow.toRecord(): ClinicWaitlistPolicyRecord =
        ClinicWaitlistPolicyRecord(
            id = this[WaitlistPolicyVersions.id].value,
            scope = ClinicWaitlistScope(
                tenantGroupId = this[WaitlistPolicyVersions.tenantGroupId].value,
                clinicId = this[WaitlistPolicyVersions.clinicId].value,
            ),
            generation = this[WaitlistPolicyVersions.generation],
            policyVersion = this[WaitlistPolicyVersions.policyVersion],
            policyDigest = this[WaitlistPolicyVersions.policyDigest],
            status = this[WaitlistPolicyVersions.status],
            effectiveFrom = this[WaitlistPolicyVersions.effectiveFrom],
            effectiveUntil = this[WaitlistPolicyVersions.effectiveUntil],
            canonicalPolicyJson = this[WaitlistPolicyVersions.canonicalPolicyJson],
            createdBy = this[WaitlistPolicyVersions.createdBy],
            createdAt = this[WaitlistPolicyVersions.createdAt],
            retiredBy = this[WaitlistPolicyVersions.retiredBy],
            retiredAt = this[WaitlistPolicyVersions.retiredAt],
        )

    private companion object {
        private val FAR_FUTURE: Instant = Instant.parse("9999-12-31T23:59:59Z")
        private val policyCodec = WaitlistPolicyDocumentCodec()
    }
}

data class ClinicWaitlistPolicyRecord(
    val id: Long,
    val scope: ClinicWaitlistScope,
    val generation: Long,
    val policyVersion: Long,
    val policyDigest: String,
    val status: WaitlistPolicyState,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val canonicalPolicyJson: String,
    val createdBy: String,
    val createdAt: Instant,
    val retiredBy: String?,
    val retiredAt: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
