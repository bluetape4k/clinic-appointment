package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.waitlist.ClinicWaitlistPolicyRecord
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateMatcher
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateRequest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistCandidateMatcherTest {

    @Test
    fun `첫 page 첫 row가 전체 eligible 후보의 deterministic winner다`() {
        withWaitlistTables {
            val decisions = mutableMapOf<MemberId, DecisionStamp>()
            var expectedWinner = 0L
            repeat(450) { index ->
                val memberId = "member-$index"
                val entryId = insertEntry(
                    memberId = memberId,
                    doctorId = DOCTOR_ID,
                    priorityRank = if (index == 449) 1 else 100,
                    waitingSince = NOW.minusSeconds(index.toLong()),
                )
                decisions[MemberId(memberId)] = decision(memberId, marker = "a")
                if (index == 449) {
                    insertBenefitGrant(memberId = memberId)
                    expectedWinner = entryId
                }
            }
            val matcher = matcher(decisions)

            val page = matcher.findCandidates(
                vacancy = vacancy(),
                policy = policyRecord(benefitWeight = 10_000),
                request = WaitlistCandidateRequest(pageSize = 100, maxPages = 4),
            )

            page.candidates.first().entry.id shouldBeEqualTo expectedWinner
            page.candidates.first().ranked.shouldNotBeNull().scoreTuple shouldBeEqualTo
                listOf(0L, 0L, 10_000L, 0L, 0L, 0L)
        }
    }

    @Test
    fun `candidate scan은 네 page 사백 row에서 멈춘다`() {
        withWaitlistTables {
            val decisions = mutableMapOf<MemberId, DecisionStamp>()
            repeat(450) { index ->
                val memberId = "member-cutoff-$index"
                insertEntry(
                    memberId = memberId,
                    doctorId = DOCTOR_ID,
                    priorityRank = 100,
                    waitingSince = NOW.minusSeconds(index.toLong()),
                )
                decisions[MemberId(memberId)] = decision(memberId, marker = "b")
            }
            val matcher = matcher(decisions)

            val page = matcher.findCandidates(
                vacancy = vacancy(),
                policy = policyRecord(waitingAgeWeight = 1),
                request = WaitlistCandidateRequest(pageSize = 100, maxPages = 4),
            )

            page.candidates.size shouldBeEqualTo 400
            page.scannedPages shouldBeEqualTo 4
            page.decisionBatchCalls shouldBeEqualTo 4
            page.exhausted shouldBeEqualTo false
        }
    }

    @Test
    fun `active offer와 restriction 후보는 ranking 전에 제외된다`() {
        withWaitlistTables {
            val decisions = mutableMapOf<MemberId, DecisionStamp>()
            val restrictedMemberId = "member-restricted"
            val activeOfferMemberId = "member-active-offer"
            val allowedMemberId = "member-allowed"
            val restricted = insertEntry(restrictedMemberId, doctorId = DOCTOR_ID, priorityRank = 1)
            val activeOffer = insertEntry(activeOfferMemberId, doctorId = DOCTOR_ID, priorityRank = 1)
            val allowed = insertEntry(allowedMemberId, doctorId = DOCTOR_ID, priorityRank = 1)
            listOf(restrictedMemberId, activeOfferMemberId, allowedMemberId).forEach { memberId ->
                decisions[MemberId(memberId)] = decision(memberId, marker = "c")
                insertBenefitGrant(memberId)
            }
            insertRestriction(restrictedMemberId)
            insertActiveOffer(entryId = activeOffer, memberId = activeOfferMemberId)
            val matcher = matcher(decisions)

            val page = matcher.findCandidates(
                vacancy = vacancy(),
                policy = policyRecord(benefitWeight = 10_000),
                request = WaitlistCandidateRequest(pageSize = 100, maxPages = 1),
            )

            page.candidates.map { it.entry.id } shouldBeEqualTo listOf(allowed)
            page.candidates.map { it.entry.id }.contains(restricted) shouldBeEqualTo false
            page.candidates.map { it.entry.id }.contains(activeOffer) shouldBeEqualTo false
        }
    }

    @Test
    fun `matcher preserves repository order and calls decision batch once per page`() {
        withWaitlistTables {
            val doctorSpecific = insertEntry(memberId = "member-doctor", doctorId = DOCTOR_ID, priorityRank = 1)
            val unspecified = insertEntry(
                memberId = "member-unspecified",
                doctorId = null,
                priorityRank = 50,
                waitingSince = NOW.minusSeconds(600),
            )
            val matcher = matcher(
                decisions = mapOf(
                    MemberId("member-doctor") to decision("member-doctor", marker = "a"),
                    MemberId("member-unspecified") to decision("member-unspecified", marker = "b"),
                ),
            )

            val page = matcher.findCandidates(vacancy(), WaitlistCandidateRequest(pageSize = 10))

            page.candidates.map { it.entry.id } shouldBeEqualTo listOf(doctorSpecific, unspecified)
            page.candidates.map { it.rank } shouldBeEqualTo listOf(1, 2)
            page.decisionBatchCalls shouldBeEqualTo 1
            page.scannedPages shouldBeEqualTo 1
        }
    }

    @Test
    fun `matcher skips candidates without usable decision and returns cursor for next tick`() {
        withWaitlistTables {
            val skipped = insertEntry(memberId = "member-stale", doctorId = DOCTOR_ID, waitingSince = NOW.minusSeconds(100))
            val selected = insertEntry(memberId = "member-next", doctorId = null, waitingSince = NOW.minusSeconds(90))
            val matcher = matcher(
                decisions = mapOf(
                    MemberId("member-stale") to decision("member-stale", marker = "c", expiresAt = NOW.minusSeconds(1)),
                    MemberId("member-next") to decision("member-next", marker = "d"),
                ),
            )

            val first = matcher.findCandidates(vacancy(), WaitlistCandidateRequest(pageSize = 1, maxPages = 1))
            first.candidates shouldBeEqualTo emptyList()
            first.nextCursor?.entryId shouldBeEqualTo skipped
            first.exhausted shouldBeEqualTo false

            val second = matcher.findCandidates(vacancy(), WaitlistCandidateRequest(cursor = first.nextCursor, pageSize = 10))
            second.candidates.map { it.entry.id } shouldBeEqualTo listOf(selected)
            second.candidates.single().decisionStamp.scope.memberId shouldBeEqualTo MemberId("member-next")
        }
    }

    private fun matcher(decisions: Map<MemberId, DecisionStamp>): WaitlistCandidateMatcher =
        WaitlistCandidateMatcher(
            repository = WaitlistRepository(),
            decisionPort = { _, memberIds, _ -> decisions.filterKeys { it in memberIds } },
        )

    private fun withWaitlistTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            Doctors,
            TreatmentTypes,
            WaitlistEntries,
            WaitlistOffers,
            WaitlistCapacityHolds,
            WaitlistOfferEvents,
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
        ) {
            seedReferences()
            block()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.seedReferences() {
        Clinics.insert {
            it[id] = EntityID(CLINIC_ID, Clinics)
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Waitlist Clinic"
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
    }

    private fun insertEntry(
        memberId: String,
        doctorId: Long?,
        priorityRank: Int = 1,
        waitingSince: Instant = NOW,
    ): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[treatmentTypeId] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
            it[WaitlistEntries.doctorId] = doctorId?.let { id -> EntityID(id, Doctors) }
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[WaitlistEntries.priorityRank] = priorityRank
            it[status] = WaitlistEntryState.WAITING
            it[WaitlistEntries.waitingSince] = waitingSince
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }.value

    private fun insertBenefitGrant(memberId: String): Long =
        BookingBenefitGrants.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[BookingBenefitGrants.memberId] = memberId
            it[approvalReference] = "approval:$memberId"
            it[benefitType] = "PRIORITY_GRANT"
            it[benefitCap] = 1
            it[grantDigest] = memberId.padEnd(64, '0').take(64)
            it[policyVersion] = 1L
            it[startsAt] = NOW.minusSeconds(60)
            it[expiresAt] = NOW.plusSeconds(3_600)
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

    private fun insertActiveOffer(entryId: Long, memberId: String): Long =
        WaitlistOffers.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[WaitlistOffers.memberId] = memberId
            it[waitlistEntryId] = EntityID(entryId, WaitlistEntries)
            it[vacancyKey] = "vacancy:$entryId"
            it[activeEntryKey] = "entry:$entryId"
            it[activeVacancyKey] = "vacancy:$entryId"
            it[resourceType] = ResourceType.PRACTITIONER
            it[resourceId] = "doctor-$DOCTOR_ID"
            it[capacityUnits] = 1
            it[maximumCapacity] = 1
            it[doctorId] = DOCTOR_ID
            it[treatmentTypeId] = TREATMENT_TYPE_ID
            it[startsAt] = Instant.parse("2026-08-01T09:00:00Z")
            it[endsAt] = Instant.parse("2026-08-01T09:30:00Z")
            it[expiresAt] = Instant.parse("2026-08-01T09:15:00Z")
            it[status] = WaitlistOfferState.OFFERED
            it[bookingReliabilityDecisionId] = 1L
            it[bookingReliabilityPolicyVersionId] = 1L
            it[bookingReliabilityPolicyHash] = "d".repeat(64)
            it[bookingReliabilityEvaluationDigest] = "f".repeat(64)
            it[bookingReliabilityExpiresAt] = NOW.plusSeconds(3_600)
            it[candidateRank] = 1
            it[selectionReasonCode] = "AUTO_SELECTED"
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
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

    private fun decision(memberId: String, marker: String, expiresAt: Instant = NOW.plusSeconds(3600)): DecisionStamp =
        DecisionStamp(
            scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, MemberId(memberId)),
            decisionId = marker.first().code.toLong(),
            policyVersionId = marker.last().code.toLong() + 100,
            policyHash = marker.repeat(64),
            evaluationDigest = marker.repeat(64),
            expiresAt = expiresAt,
        )

    private fun policyRecord(
        urgencyWeight: Int = 0,
        recoveryWeight: Int = 0,
        benefitWeight: Int = 0,
        reliabilityWeight: Int = 0,
        waitingAgeWeight: Int = 0,
        slotFitWeight: Int = 0,
    ): ClinicWaitlistPolicyRecord =
        ClinicWaitlistPolicyRecord(
            id = 1L,
            scope = ClinicWaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID),
            generation = 1L,
            policyVersion = 1L,
            policyDigest = "c".repeat(64),
            status = WaitlistPolicyState.ACTIVE,
            effectiveFrom = NOW.minusSeconds(60),
            effectiveUntil = null,
            canonicalPolicyJson = """
                {
                  "urgencyWeight":$urgencyWeight,
                  "recoveryWeight":$recoveryWeight,
                  "benefitWeight":$benefitWeight,
                  "reliabilityWeight":$reliabilityWeight,
                  "waitingAgeWeight":$waitingAgeWeight,
                  "slotFitWeight":$slotFitWeight
                }
            """.trimIndent(),
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
