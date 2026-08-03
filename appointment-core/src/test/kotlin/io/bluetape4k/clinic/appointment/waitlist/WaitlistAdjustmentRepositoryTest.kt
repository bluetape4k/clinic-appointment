package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.VersionConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistAdjustmentConflictException
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistAdjustmentNotFoundException
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistException
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistAdjustmentRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistAdjustmentRepositoryTest {
    private val repository = WaitlistAdjustmentRepository()

    @Test
    fun `restriction release and expiry use version CAS and append audit events`() {
        withAdjustmentTables {
            val releaseId = insertRestriction("member-release", expiresAt = BASE_TIME.plusSeconds(3_600))
            val expiryId = insertRestriction("member-expiry", expiresAt = BASE_TIME.minusSeconds(1))

            repository.releaseRestriction(
                scope = scope(),
                restrictionId = releaseId,
                expectedVersion = 0L,
                actor = ACTOR,
                decisionRef = "decision:manual-release",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L
            repository.expireRestriction(
                scope = scope(),
                restrictionId = expiryId,
                expectedVersion = 0L,
                actor = ActorRef("recovery:restriction-expiry"),
                decisionRef = "decision:expiry-scan",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L

            assertFailsWith<VersionConflict> {
                repository.releaseRestriction(scope(), releaseId, expectedVersion = 0L, ACTOR, "decision:stale", BASE_TIME)
            }
            BookingRestrictions.selectAll()
                .where { BookingRestrictions.id eq releaseId }
                .single()[BookingRestrictions.releasedBy] shouldBeEqualTo ACTOR.value
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `recovery credit consume and reverse are single-writer decisions`() {
        withAdjustmentTables {
            val consumeId = insertCredit("member-consume", digest = "a".repeat(64))
            val reverseId = insertCredit("member-reverse", digest = "b".repeat(64))
            val expiredId = insertCredit(
                "member-expired",
                digest = "f".repeat(64),
                expiresAt = BASE_TIME.minusSeconds(1),
            )

            assertFailsWith<VersionConflict> {
                repository.consumeRecoveryCredit(scope(), expiredId, expectedVersion = 0L, "decision:expired", BASE_TIME)
            }

            repository.consumeRecoveryCredit(
                scope = scope(),
                creditId = consumeId,
                expectedVersion = 0L,
                decisionRef = "decision:offer-77",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L
            repository.reverseRecoveryCredit(
                scope = scope(),
                creditId = reverseId,
                expectedVersion = 0L,
                actor = ACTOR,
                decisionRef = "decision:operator-reversal",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L

            assertFailsWith<VersionConflict> {
                repository.consumeRecoveryCredit(scope(), consumeId, expectedVersion = 0L, "decision:stale", BASE_TIME)
            }
            DisruptionRecoveryCredits.selectAll()
                .where { DisruptionRecoveryCredits.id eq consumeId }
                .single()[DisruptionRecoveryCredits.consumedAt].shouldNotBeNull()
            DisruptionRecoveryCredits.selectAll()
                .where { DisruptionRecoveryCredits.id eq reverseId }
                .single()[DisruptionRecoveryCredits.reversedBy] shouldBeEqualTo ACTOR.value
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `benefit grant cap and revoke use CAS without last writer wins`() {
        withAdjustmentTables {
            val cappedId = insertBenefitGrant("member-cap", digest = "c".repeat(64), benefitCap = 2)
            val revokedId = insertBenefitGrant("member-revoke", digest = "d".repeat(64), benefitCap = 1)

            val capFailure = assertFailsWith<WaitlistAdjustmentConflictException> {
                repository.consumeBenefitGrant(
                    scope = scope(),
                    grantId = cappedId,
                    expectedVersion = 0L,
                    requestedUnits = 3,
                    decisionRef = "decision:too-large",
                    now = BASE_TIME,
                )
            }
            val stableConflict: WaitlistException = capFailure
            stableConflict.reason.code shouldBeEqualTo "WAITLIST_ADJUSTMENT_CONFLICT"
            repository.consumeBenefitGrant(
                scope = scope(),
                grantId = cappedId,
                expectedVersion = 0L,
                requestedUnits = 2,
                decisionRef = "decision:within-cap",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L
            repository.revokeBenefitGrant(
                scope = scope(),
                grantId = revokedId,
                expectedVersion = 0L,
                actor = ACTOR,
                decisionRef = "decision:revoke",
                now = BASE_TIME,
            ) shouldBeEqualTo 1L

            assertFailsWith<VersionConflict> {
                repository.revokeBenefitGrant(scope(), revokedId, expectedVersion = 0L, ACTOR, "decision:stale", BASE_TIME)
            }
            BookingBenefitGrants.selectAll()
                .where { BookingBenefitGrants.id eq cappedId }
                .single()[BookingBenefitGrants.consumedAt].shouldNotBeNull()
            BookingBenefitGrants.selectAll()
                .where { BookingBenefitGrants.id eq revokedId }
                .single()[BookingBenefitGrants.revokedBy] shouldBeEqualTo ACTOR.value
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `adjustment not found uses stable waitlist exception reason`() {
        withAdjustmentTables {
            val failure = assertFailsWith<WaitlistAdjustmentNotFoundException> {
                repository.revokeBenefitGrant(
                    scope = scope(),
                    grantId = 9_999L,
                    expectedVersion = 0L,
                    actor = ACTOR,
                    decisionRef = "decision:not-found",
                    now = BASE_TIME,
                )
            }

            val stableNotFound: WaitlistException = failure
            stableNotFound.reason.code shouldBeEqualTo "WAITLIST_ADJUSTMENT_NOT_FOUND"
        }
    }

    private fun withAdjustmentTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
            WaitlistPolicyEvents,
        ) {
            seedClinic()
            block()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.seedClinic() {
        Clinics.insert {
            it[id] = EntityID(CLINIC_ID, Clinics)
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Waitlist Adjustment Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }
    }

    private fun insertRestriction(memberId: String, expiresAt: Instant): Long =
        BookingRestrictions.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[BookingRestrictions.memberId] = memberId
            it[evidenceDigest] = "e".repeat(64)
            it[reasonCode] = "NO_SHOW"
            it[policyVersion] = 1L
            it[restrictionMode] = "WAITLIST_BLOCK"
            it[actorRef] = ACTOR.value
            it[startsAt] = BASE_TIME.minusSeconds(3_600)
            it[BookingRestrictions.expiresAt] = expiresAt
        }.value

    private fun insertCredit(
        memberId: String,
        digest: String,
        expiresAt: Instant = BASE_TIME.plusSeconds(86_400),
    ): Long =
        DisruptionRecoveryCredits.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[DisruptionRecoveryCredits.memberId] = memberId
            it[sourceAppointmentId] = 11L
            it[creditDigest] = digest
            it[priorityBoost] = 10
            it[reasonCode] = "DISRUPTION_RECOVERY"
            it[grantedBy] = ACTOR.value
            it[DisruptionRecoveryCredits.expiresAt] = expiresAt
        }.value

    private fun insertBenefitGrant(memberId: String, digest: String, benefitCap: Int): Long =
        BookingBenefitGrants.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[BookingBenefitGrants.memberId] = memberId
            it[approvalReference] = "approval:$memberId"
            it[benefitType] = "PRIORITY_GRANT"
            it[BookingBenefitGrants.benefitCap] = benefitCap
            it[grantDigest] = digest
            it[policyVersion] = 1L
            it[startsAt] = BASE_TIME.minusSeconds(3_600)
            it[expiresAt] = BASE_TIME.plusSeconds(86_400)
        }.value

    private fun scope(): ClinicWaitlistScope =
        ClinicWaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID)

    private companion object {
        const val CLINIC_ID = 41L
        val BASE_TIME: Instant = Instant.parse("2026-08-03T09:00:00Z")
        val ACTOR: ActorRef = ActorRef("staff:adjustment-admin")
    }
}
