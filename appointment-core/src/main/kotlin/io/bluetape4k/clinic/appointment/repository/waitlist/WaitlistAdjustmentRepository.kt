package io.bluetape4k.clinic.appointment.repository.waitlist

import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.Instant

/**
 * waitlist delivery 보정 projection을 caller-owned transaction 안에서 CAS로 갱신한다.
 */
class WaitlistAdjustmentRepository {

    fun releaseRestriction(
        scope: ClinicWaitlistScope,
        restrictionId: Long,
        expectedVersion: Long,
        actor: ActorRef,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        restrictionId.requirePositiveNumber("restrictionId")
        requireVersion(expectedVersion)
        lockClinic(scope)
        val updated = BookingRestrictions.update({
            restrictionCondition(scope, restrictionId) and
                versionCondition(BookingRestrictions.reversalVersion, expectedVersion) and
                BookingRestrictions.releasedAt.isNull()
        }) {
            it[releasedBy] = actor.value
            it[releasedAt] = now
            it[reversalVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            ensureRestrictionExists(scope, restrictionId)
            throw VersionConflict(restrictionId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_RESTRICTION_RELEASED",
            actor = actor,
            decisionRef = decisionRef,
            targetId = restrictionId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    fun expireRestriction(
        scope: ClinicWaitlistScope,
        restrictionId: Long,
        expectedVersion: Long,
        actor: ActorRef,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        restrictionId.requirePositiveNumber("restrictionId")
        requireVersion(expectedVersion)
        lockClinic(scope)
        val updated = BookingRestrictions.update({
            restrictionCondition(scope, restrictionId) and
                versionCondition(BookingRestrictions.reversalVersion, expectedVersion) and
                BookingRestrictions.releasedAt.isNull() and
                (BookingRestrictions.expiresAt lessEq now)
        }) {
            it[releasedBy] = actor.value
            it[releasedAt] = now
            it[reversalVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            ensureRestrictionExists(scope, restrictionId)
            throw VersionConflict(restrictionId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_RESTRICTION_EXPIRED",
            actor = actor,
            decisionRef = decisionRef,
            targetId = restrictionId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    fun consumeRecoveryCredit(
        scope: ClinicWaitlistScope,
        creditId: Long,
        expectedVersion: Long,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        creditId.requirePositiveNumber("creditId")
        requireVersion(expectedVersion)
        lockClinic(scope)
        val updated = DisruptionRecoveryCredits.update({
            creditCondition(scope, creditId) and
                versionCondition(DisruptionRecoveryCredits.reversalVersion, expectedVersion) and
                DisruptionRecoveryCredits.consumedAt.isNull() and
                DisruptionRecoveryCredits.reversedAt.isNull() and
                (DisruptionRecoveryCredits.expiresAt greater now)
        }) {
            it[consumedAt] = now
            it[reversalVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            ensureCreditExists(scope, creditId)
            throw VersionConflict(creditId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_RECOVERY_CREDIT_CONSUMED",
            actor = SYSTEM_ACTOR,
            decisionRef = decisionRef,
            targetId = creditId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    fun reverseRecoveryCredit(
        scope: ClinicWaitlistScope,
        creditId: Long,
        expectedVersion: Long,
        actor: ActorRef,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        creditId.requirePositiveNumber("creditId")
        requireVersion(expectedVersion)
        lockClinic(scope)
        val updated = DisruptionRecoveryCredits.update({
            creditCondition(scope, creditId) and
                versionCondition(DisruptionRecoveryCredits.reversalVersion, expectedVersion) and
                DisruptionRecoveryCredits.consumedAt.isNull() and
                DisruptionRecoveryCredits.reversedAt.isNull()
        }) {
            it[reversedBy] = actor.value
            it[reversedAt] = now
            it[reversalVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            ensureCreditExists(scope, creditId)
            throw VersionConflict(creditId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_RECOVERY_CREDIT_REVERSED",
            actor = actor,
            decisionRef = decisionRef,
            targetId = creditId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    fun consumeBenefitGrant(
        scope: ClinicWaitlistScope,
        grantId: Long,
        expectedVersion: Long,
        requestedUnits: Int,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        grantId.requirePositiveNumber("grantId")
        requireVersion(expectedVersion)
        require(requestedUnits > 0) { "requestedUnits must be positive" }
        lockClinic(scope)
        val grant = BookingBenefitGrants
            .selectAll()
            .where { grantCondition(scope, grantId) }
            .forUpdate()
            .singleOrNull()
            ?: throw WaitlistAdjustmentNotFoundException("booking benefit grant not found")
        if (requestedUnits > grant[BookingBenefitGrants.benefitCap]) {
            throw WaitlistAdjustmentConflictException("requestedUnits exceeds benefitCap")
        }
        val updated = BookingBenefitGrants.update({
            grantCondition(scope, grantId) and
                versionCondition(BookingBenefitGrants.revokeVersion, expectedVersion) and
                BookingBenefitGrants.consumedAt.isNull() and
                BookingBenefitGrants.revokedAt.isNull() and
                (BookingBenefitGrants.startsAt lessEq now) and
                (BookingBenefitGrants.expiresAt.isNull() or (BookingBenefitGrants.expiresAt greater now))
        }) {
            it[consumedAt] = now
            it[revokeVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            throw VersionConflict(grantId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_BENEFIT_GRANT_CONSUMED",
            actor = SYSTEM_ACTOR,
            decisionRef = decisionRef,
            targetId = grantId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    fun revokeBenefitGrant(
        scope: ClinicWaitlistScope,
        grantId: Long,
        expectedVersion: Long,
        actor: ActorRef,
        decisionRef: String,
        now: Instant = Instant.now(),
    ): Long {
        grantId.requirePositiveNumber("grantId")
        requireVersion(expectedVersion)
        lockClinic(scope)
        val updated = BookingBenefitGrants.update({
            grantCondition(scope, grantId) and
                versionCondition(BookingBenefitGrants.revokeVersion, expectedVersion) and
                BookingBenefitGrants.consumedAt.isNull() and
                BookingBenefitGrants.revokedAt.isNull()
        }) {
            it[revokedBy] = actor.value
            it[revokedAt] = now
            it[revokeVersion] = expectedVersion + 1L
        }
        if (updated != 1) {
            ensureGrantExists(scope, grantId)
            throw VersionConflict(grantId)
        }
        appendAdjustmentEvent(
            scope = scope,
            eventType = "WAITLIST_BENEFIT_GRANT_REVOKED",
            actor = actor,
            decisionRef = decisionRef,
            targetId = grantId,
            nextVersion = expectedVersion + 1L,
            now = now,
        )
        return expectedVersion + 1L
    }

    private fun lockClinic(scope: ClinicWaitlistScope) {
        Clinics
            .selectAll()
            .where {
                (Clinics.id eq scope.clinicId) and
                    (Clinics.tenantGroupId eq scope.tenantGroupId)
            }
            .forUpdate()
            .singleOrNull()
            ?: throw WaitlistAdjustmentNotFoundException("clinic not found")
    }

    private fun appendAdjustmentEvent(
        scope: ClinicWaitlistScope,
        eventType: String,
        actor: ActorRef,
        decisionRef: String,
        targetId: Long,
        nextVersion: Long,
        now: Instant,
    ) {
        val payloadJson = """{"targetId":$targetId,"nextVersion":$nextVersion}"""
        val digest = sha256(
            listOf(
                scope.tenantGroupId,
                scope.clinicId,
                eventType,
                decisionRef,
                targetId,
                nextVersion,
                payloadJson,
                now.toString(),
            ).joinToString("|"),
        )
        WaitlistPolicyEvents.insert {
            it[tenantGroupId] = EntityID(scope.tenantGroupId, TenantGroups)
            it[clinicId] = EntityID(scope.clinicId, Clinics)
            it[policyVersion] = 0L
            it[WaitlistPolicyEvents.eventType] = eventType
            it[actorRef] = actor.value
            it[correlationId] = decisionRef
            it[fromGeneration] = null
            it[toGeneration] = nextVersion
            it[reasonCode] = "ADJUSTMENT"
            it[eventDigest] = digest
            it[WaitlistPolicyEvents.payloadJson] = payloadJson
            it[occurredAt] = now
        }
    }

    private fun ensureRestrictionExists(scope: ClinicWaitlistScope, restrictionId: Long) {
        if (BookingRestrictions.selectAll().where { restrictionCondition(scope, restrictionId) }.empty()) {
            throw WaitlistAdjustmentNotFoundException("booking restriction not found")
        }
    }

    private fun ensureCreditExists(scope: ClinicWaitlistScope, creditId: Long) {
        if (DisruptionRecoveryCredits.selectAll().where { creditCondition(scope, creditId) }.empty()) {
            throw WaitlistAdjustmentNotFoundException("disruption recovery credit not found")
        }
    }

    private fun ensureGrantExists(scope: ClinicWaitlistScope, grantId: Long) {
        if (BookingBenefitGrants.selectAll().where { grantCondition(scope, grantId) }.empty()) {
            throw WaitlistAdjustmentNotFoundException("booking benefit grant not found")
        }
    }

    private fun restrictionCondition(scope: ClinicWaitlistScope, restrictionId: Long): Op<Boolean> =
        (BookingRestrictions.tenantGroupId eq scope.tenantGroupId) and
            (BookingRestrictions.clinicId eq scope.clinicId) and
            (BookingRestrictions.id eq restrictionId)

    private fun creditCondition(scope: ClinicWaitlistScope, creditId: Long): Op<Boolean> =
        (DisruptionRecoveryCredits.tenantGroupId eq scope.tenantGroupId) and
            (DisruptionRecoveryCredits.clinicId eq scope.clinicId) and
            (DisruptionRecoveryCredits.id eq creditId)

    private fun grantCondition(scope: ClinicWaitlistScope, grantId: Long): Op<Boolean> =
        (BookingBenefitGrants.tenantGroupId eq scope.tenantGroupId) and
            (BookingBenefitGrants.clinicId eq scope.clinicId) and
            (BookingBenefitGrants.id eq grantId)

    private fun versionCondition(column: Column<Long?>, expectedVersion: Long): Op<Boolean> =
        if (expectedVersion == 0L) column.isNull() else column eq expectedVersion

    private fun requireVersion(expectedVersion: Long) {
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
    }

    private companion object {
        private val SYSTEM_ACTOR = ActorRef("SYSTEM")
    }
}

class WaitlistAdjustmentConflictException(
    message: String,
) : RuntimeException(message) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class WaitlistAdjustmentNotFoundException(
    message: String,
) : RuntimeException(message) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
