package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.waitlist.ClinicWaitlistPolicyRecord
import io.bluetape4k.clinic.appointment.repository.waitlist.RankedWaitlistCandidateRow
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.rankedWaitlistEligibilityDigest
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
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
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
                vacancy = vacancy(),
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
                vacancy = vacancy(),
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
    fun `override는 repository evidence 없는 fabricated candidate를 거부한다`() {
        withDecisionTables {
            assertFailsWith<WaitlistOverrideRejected> {
                service.override(
                    vacancy = vacancy(),
                    defaultWinner = rankedCandidate(entryId = 1L),
                    requestedCandidate = rankedCandidate(entryId = 2L, eligibilityDigest = "a".repeat(64)),
                    actor = WaitlistDecisionActor(ActorRef("staff:waitlist-manager"), canOverrideWaitlist = true),
                    policy = policyRecord(),
                    reasonCode = "MANUAL_PRIORITY",
                    correlationId = "decision:fabricated-candidate",
                    now = NOW,
                )
            }
        }
    }

    @Test
    fun `override rechecks an active restriction after preview`() {
        withDecisionTables {
            val defaultId = insertEntry(memberId = "member-default", priorityRank = 2)
            val requestedId = insertEntry(memberId = "member-override", priorityRank = 1)
            val policy = policyRecord()
            val rankedRows = WaitlistRepository().findRankedCandidatePage(vacancy(), policy, null, 100)
            insertRestriction("member-override")

            assertFailsWith<WaitlistOverrideRejected> {
                service.override(
                    vacancy = vacancy(),
                    defaultWinner = rankedRows.single { it.entry.id == defaultId }
                        .withDecisionStamp(decision("member-default")),
                    requestedCandidate = rankedRows.single { it.entry.id == requestedId }
                        .withDecisionStamp(decision("member-override")),
                    actor = WaitlistDecisionActor(ActorRef("staff:waitlist-manager"), canOverrideWaitlist = true),
                    policy = policy,
                    reasonCode = "MANUAL_PRIORITY",
                    correlationId = "decision:restriction-race",
                    now = NOW,
                )
            }
        }
    }

    @Test
    fun `valid override는 후보 교체와 typed audit을 남긴다`() {
        withDecisionTables {
            val defaultId = insertEntry(memberId = "member-default", priorityRank = 1)
            val requestedId = insertEntry(memberId = "member-override", priorityRank = 2)
            val policy = policyRecord()
            val rankedRows = WaitlistRepository().findRankedCandidatePage(
                vacancy = vacancy(),
                policy = policy,
                cursor = null,
                limit = 100,
            )
            val result = service.override(
                vacancy = vacancy(),
                defaultWinner = rankedRows.single { it.entry.id == defaultId }
                    .withDecisionStamp(decision("member-default")),
                requestedCandidate = rankedRows.single { it.entry.id == requestedId }
                    .withDecisionStamp(decision("member-override")),
                actor = WaitlistDecisionActor(ActorRef("staff:waitlist-manager"), canOverrideWaitlist = true),
                policy = policy,
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
        withTables(
            TestDB.H2,
            Clinics,
            Doctors,
            TreatmentTypes,
            WaitlistEntries,
            WaitlistOffers,
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
            WaitlistPolicyEvents,
        ) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Waitlist Decision Clinic"
            }
            Doctors.insert {
                it[id] = EntityID(DOCTOR_ID, Doctors)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Dr. Waitlist"
            }
            TreatmentTypes.insert {
                it[id] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Waitlist Care"
                it[defaultDurationMinutes] = 30
            }
            block()
        }
    }

    private fun insertEntry(memberId: String, priorityRank: Int): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[treatmentTypeId] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
            it[doctorId] = EntityID(DOCTOR_ID, Doctors)
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[WaitlistEntries.priorityRank] = priorityRank
            it[status] = WaitlistEntryState.WAITING
            it[WaitlistEntries.waitingSince] = NOW
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }.value

    private fun insertRestriction(memberId: String): Long =
        BookingRestrictions.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[BookingRestrictions.memberId] = memberId
            it[evidenceDigest] = "e".repeat(64)
            it[reasonCode] = "NO_SHOW"
            it[policyVersion] = 1L
            it[restrictionMode] = "WAITLIST_BLOCK"
            it[actorRef] = "staff:policy-admin"
            it[startsAt] = NOW.minusSeconds(60)
            it[BookingRestrictions.expiresAt] = NOW.plusSeconds(3_600)
        }.value

    private fun vacancy(): VacancyDescriptor =
        VacancyDescriptor(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            doctorId = DOCTOR_ID,
            startsAt = Instant.parse("2026-08-01T09:00:00Z"),
            endsAt = Instant.parse("2026-08-01T09:30:00Z"),
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            capacityUnits = 1,
            maximumCapacity = 1,
            now = NOW,
        )

    private fun rankedCandidate(
        entryId: Long = 1L,
        memberId: String = "member-$entryId",
        policyVersion: Long = 1L,
        policyDigest: String = "c".repeat(64),
        scoreTuple: List<Long> = listOf(10L, 0L, 0L, 0L, 0L, 0L),
        eligibilityDigest: String? = null,
    ): RankedWaitlistCandidateRow {
        val entry = entry(entryId, memberId)
        return RankedWaitlistCandidateRow(
            entry = entry,
            eligibilityDigest = eligibilityDigest ?: rankedWaitlistEligibilityDigest(
                entry = entry,
                policyVersion = policyVersion,
                policyDigest = policyDigest,
                scoreTuple = scoreTuple,
            ),
            scoreTuple = scoreTuple,
            policyVersion = policyVersion,
            policyDigest = policyDigest,
            decisionStamp = decision(memberId),
        )
    }

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
