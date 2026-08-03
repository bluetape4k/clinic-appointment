package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.waitlist.ClinicWaitlistPolicyRecord
import io.bluetape4k.clinic.appointment.repository.waitlist.RankedWaitlistCandidateRow
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDecisionActor
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDecisionPreview
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDecisionService
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOverridePermissionDenied
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOverrideRejected
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistDecisionServiceTest {
    private val service = WaitlistDecisionService()

    @Test
    fun `stale policy version or digest preview is rejected`() {
        val preview = WaitlistDecisionPreview(
            defaultWinner = rankedCandidate(policyVersion = 1L, policyDigest = "a".repeat(64)),
            policyVersion = 1L,
            policyDigest = "a".repeat(64),
        )

        assertFailsWith<WaitlistPolicyConflict> {
            service.ensureFreshPreview(preview, currentPolicy = policyRecord(policyVersion = 2L, digest = "b".repeat(64)))
        }
        assertFailsWith<WaitlistPolicyConflict> {
            service.ensureFreshPreview(preview, currentPolicy = policyRecord(policyVersion = 1L, digest = "b".repeat(64)))
        }
    }

    @Test
    fun `override permission이 없으면 기본 후보를 교체하지 않는다`() {
        assertFailsWith<WaitlistOverridePermissionDenied> {
            service.override(
                defaultWinner = rankedCandidate(entryId = 1L),
                requestedCandidate = rankedCandidate(entryId = 2L),
                actor = WaitlistDecisionActor(ActorRef("staff:frontdesk"), canOverrideWaitlist = false),
                policy = policyRecord(),
                reasonCode = "MANUAL_PRIORITY",
                correlationId = "decision:permission-denied",
                now = NOW,
            )
        }
    }

    @Test
    fun `override는 hard eligibility를 우회하지 않는다`() {
        assertFailsWith<WaitlistOverrideRejected> {
            service.override(
                defaultWinner = rankedCandidate(entryId = 1L),
                requestedCandidate = rankedCandidate(entryId = 2L, scoreTuple = emptyList()),
                actor = WaitlistDecisionActor(ActorRef("staff:waitlist-manager"), canOverrideWaitlist = true),
                policy = policyRecord(),
                reasonCode = "MANUAL_PRIORITY",
                correlationId = "decision:hard-eligibility",
                now = NOW,
            )
        }
    }

    @Test
    fun `valid override는 후보 교체와 typed audit을 남긴다`() {
        withDecisionTables {
            val result = service.override(
                defaultWinner = rankedCandidate(entryId = 1L, memberId = "member-default"),
                requestedCandidate = rankedCandidate(entryId = 2L, memberId = "member-override"),
                actor = WaitlistDecisionActor(ActorRef("staff:waitlist-manager"), canOverrideWaitlist = true),
                policy = policyRecord(),
                reasonCode = "MANUAL_PRIORITY",
                correlationId = "decision:valid-override",
                now = NOW,
            )

            result.selected.entry.id shouldBeEqualTo 2L
            val events = WaitlistPolicyEvents
                .selectAll()
                .orderBy(WaitlistPolicyEvents.id to SortOrder.ASC)
                .toList()
            events.size shouldBeEqualTo 1
            events.single()[WaitlistPolicyEvents.eventType] shouldBeEqualTo "WAITLIST_CANDIDATE_OVERRIDE_SELECTED"
            events.single()[WaitlistPolicyEvents.actorRef] shouldBeEqualTo "staff:waitlist-manager"
            events.single()[WaitlistPolicyEvents.reasonCode] shouldBeEqualTo "MANUAL_PRIORITY"
            events.single()[WaitlistPolicyEvents.correlationId] shouldBeEqualTo "decision:valid-override"
        }
    }

    private fun withDecisionTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(TestDB.H2, Clinics, WaitlistPolicyEvents) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Waitlist Decision Clinic"
            }
            block()
        }
    }

    private fun rankedCandidate(
        entryId: Long = 1L,
        memberId: String = "member-$entryId",
        policyVersion: Long = 1L,
        policyDigest: String = "c".repeat(64),
        scoreTuple: List<Long> = listOf(10L, 0L, 0L, 0L, 0L, 0L),
    ): RankedWaitlistCandidateRow =
        RankedWaitlistCandidateRow(
            entry = entry(entryId, memberId),
            eligibilityDigest = "e".repeat(64),
            scoreTuple = scoreTuple,
            policyVersion = policyVersion,
            policyDigest = policyDigest,
            decisionStamp = decision(memberId),
        )

    private fun entry(id: Long, memberId: String): WaitlistEntryRecord =
        WaitlistEntryRecord(
            id = id,
            scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, MemberId(memberId)),
            treatmentTypeId = TREATMENT_TYPE_ID,
            doctorId = DOCTOR_ID,
            preferredDateFrom = LocalDate.of(2026, 8, 1),
            preferredDateTo = LocalDate.of(2026, 8, 1),
            preferredStartTime = LocalTime.of(8, 0),
            preferredEndTime = LocalTime.of(12, 0),
            priorityRank = 1,
            status = WaitlistEntryState.WAITING,
            waitingSince = NOW.minusSeconds(id),
            version = 0L,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun decision(memberId: String): DecisionStamp =
        DecisionStamp(
            scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, MemberId(memberId)),
            decisionId = 100L,
            policyVersionId = 7L,
            policyHash = "d".repeat(64),
            evaluationDigest = "f".repeat(64),
            expiresAt = NOW.plusSeconds(3_600),
        )

    private fun policyRecord(
        policyVersion: Long = 1L,
        digest: String = "c".repeat(64),
    ): ClinicWaitlistPolicyRecord =
        ClinicWaitlistPolicyRecord(
            id = 1L,
            scope = ClinicWaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID),
            generation = 1L,
            policyVersion = policyVersion,
            policyDigest = digest,
            status = WaitlistPolicyState.ACTIVE,
            effectiveFrom = NOW.minusSeconds(60),
            effectiveUntil = null,
            canonicalPolicyJson = """{"urgencyWeight":1,"recoveryWeight":0,"benefitWeight":0,"reliabilityWeight":0,"waitingAgeWeight":0,"slotFitWeight":0}""",
            createdBy = "staff:policy-admin",
            createdAt = NOW.minusSeconds(60),
            retiredBy = null,
            retiredAt = null,
        )

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
    }
}
