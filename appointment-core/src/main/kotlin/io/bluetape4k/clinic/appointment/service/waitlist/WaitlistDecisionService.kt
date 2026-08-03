package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.repository.waitlist.ClinicWaitlistPolicyRecord
import io.bluetape4k.clinic.appointment.repository.waitlist.RankedWaitlistCandidateRow
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * ranked waitlist preview와 staff override decision을 caller-owned transaction 안에서 검증합니다.
 */
class WaitlistDecisionService {

    /** preview가 현재 active policy snapshot과 같은 version/digest인지 확인합니다. */
    fun ensureFreshPreview(
        preview: WaitlistDecisionPreview,
        currentPolicy: ClinicWaitlistPolicyRecord,
    ): WaitlistDecisionPreview {
        if (
            preview.policyVersion != currentPolicy.policyVersion ||
            preview.policyDigest != currentPolicy.policyDigest ||
            preview.defaultWinner.policyVersion != currentPolicy.policyVersion ||
            preview.defaultWinner.policyDigest != currentPolicy.policyDigest
        ) {
            throw WaitlistPolicyConflict()
        }
        return preview
    }

    /**
     * 명시 권한을 가진 actor만 hard eligibility를 통과한 후보 사이에서 기본 후보를 교체합니다.
     */
    fun override(
        defaultWinner: RankedWaitlistCandidateRow,
        requestedCandidate: RankedWaitlistCandidateRow,
        actor: WaitlistDecisionActor,
        policy: ClinicWaitlistPolicyRecord,
        reasonCode: String,
        correlationId: String,
        now: Instant = Instant.now(),
    ): WaitlistDecisionOverrideResult {
        reasonCode.requireNotBlank("reasonCode")
        correlationId.requireNotBlank("correlationId")
        val reason = WaitlistReasonCode(reasonCode)
        if (!actor.canOverrideWaitlist) {
            throw WaitlistOverridePermissionDenied(actor.actorRef)
        }
        if (!requestedCandidate.isHardEligibleFor(policy)) {
            throw WaitlistOverrideRejected(requestedCandidate.entry.id)
        }
        if (!defaultWinner.isHardEligibleFor(policy)) {
            throw WaitlistOverrideRejected(defaultWinner.entry.id)
        }

        appendOverrideEvent(defaultWinner, requestedCandidate, actor, policy, reason, correlationId, now)
        return WaitlistDecisionOverrideResult(
            selected = requestedCandidate,
            previousDefault = defaultWinner,
            actor = actor.actorRef,
            reasonCode = reason,
            correlationId = correlationId,
        )
    }

    private fun RankedWaitlistCandidateRow.isHardEligibleFor(policy: ClinicWaitlistPolicyRecord): Boolean =
        scoreTuple.size == 6 &&
            policyVersion == policy.policyVersion &&
            policyDigest == policy.policyDigest

    private fun appendOverrideEvent(
        defaultWinner: RankedWaitlistCandidateRow,
        requestedCandidate: RankedWaitlistCandidateRow,
        actor: WaitlistDecisionActor,
        policy: ClinicWaitlistPolicyRecord,
        reason: WaitlistReasonCode,
        correlationId: String,
        now: Instant,
    ) {
        val payloadJson =
            """{"defaultEntryId":${defaultWinner.entry.id},"selectedEntryId":${requestedCandidate.entry.id}}"""
        val eventDigest = sha256(
            listOf(
                policy.scope.tenantGroupId,
                policy.scope.clinicId,
                policy.policyVersion,
                defaultWinner.entry.id,
                requestedCandidate.entry.id,
                actor.actorRef.value,
                reason.code,
                correlationId,
                now.toString(),
            ).joinToString("|"),
        )
        WaitlistPolicyEvents.insert {
            it[tenantGroupId] = EntityID(policy.scope.tenantGroupId, TenantGroups)
            it[clinicId] = EntityID(policy.scope.clinicId, Clinics)
            it[policyVersion] = policy.policyVersion
            it[eventType] = "WAITLIST_CANDIDATE_OVERRIDE_SELECTED"
            it[actorRef] = actor.actorRef.value
            it[WaitlistPolicyEvents.correlationId] = correlationId
            it[fromGeneration] = null
            it[toGeneration] = policy.generation
            it[reasonCode] = reason.code
            it[WaitlistPolicyEvents.eventDigest] = eventDigest
            it[WaitlistPolicyEvents.payloadJson] = payloadJson
            it[occurredAt] = now
        }
    }
}

/** waitlist preview 실행 시점에 고정한 기본 후보와 policy snapshot입니다. */
data class WaitlistDecisionPreview(
    val defaultWinner: RankedWaitlistCandidateRow,
    val policyVersion: Long,
    val policyDigest: String,
) : Serializable {
    init {
        require(policyVersion > 0) { "policyVersion must be positive" }
        require(policyDigest.matches(LOWER_SHA256)) { "policyDigest must be lowercase SHA-256" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist override 명령을 실행하는 actor의 최소 권한 snapshot입니다. */
data class WaitlistDecisionActor(
    val actorRef: ActorRef,
    val canOverrideWaitlist: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** override가 선택한 후보와 감사 reason을 caller에게 반환합니다. */
data class WaitlistDecisionOverrideResult(
    val selected: RankedWaitlistCandidateRow,
    val previousDefault: RankedWaitlistCandidateRow,
    val actor: ActorRef,
    val reasonCode: WaitlistReasonCode,
    val correlationId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist 후보 override 권한이 없습니다. */
class WaitlistOverridePermissionDenied(
    val actorRef: ActorRef,
) : RuntimeException("WAITLIST_OVERRIDE_PERMISSION_DENIED"), Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** hard eligibility 또는 snapshot 조건을 만족하지 않는 후보 override입니다. */
class WaitlistOverrideRejected(
    val entryId: Long,
) : RuntimeException("WAITLIST_OVERRIDE_REJECTED"), Serializable {
    init {
        require(entryId > 0) { "entryId must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private val LOWER_SHA256 = Regex("^[a-f0-9]{64}$")

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
