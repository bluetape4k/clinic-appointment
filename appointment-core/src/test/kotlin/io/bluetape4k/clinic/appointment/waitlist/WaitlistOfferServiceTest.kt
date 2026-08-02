package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.CommitmentSeed
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.repository.withCommitmentTables
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateMatcher
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistOfferServiceTest {

    @Test
    fun `select and offer creates one active offer hold and history row in caller transaction`() {
        withCommitmentTables { seed ->
            val ids = seed.insertWaitingEntry("member-offer")
            val service = serviceFor(
                decisions = mapOf(MemberId("member-offer") to decision(seed, "member-offer", "a")),
                offerTtl = Duration.ofHours(2),
            )

            val result = service.selectAndOffer(vacancy(seed), CORRELATION, ActorRef("SYSTEM"))

            result.rank shouldBeEqualTo 1
            WaitlistOffers.selectAll().where { WaitlistOffers.id eq result.offerId }.single().let { row ->
                row[WaitlistOffers.status] shouldBeEqualTo WaitlistOfferState.OFFERED
                row[WaitlistOffers.expiresAt] shouldBeEqualTo START
            }
            WaitlistCapacityHolds.selectAll().where { WaitlistCapacityHolds.id eq result.holdId }.single().let { row ->
                row[WaitlistCapacityHolds.status] shouldBeEqualTo WaitlistCapacityHoldState.OFFERED
                row[WaitlistCapacityHolds.holdExpiresAt] shouldBeEqualTo START
            }
            WaitlistEntries.selectAll().where { WaitlistEntries.id eq ids.entryId }.single()[WaitlistEntries.status] shouldBeEqualTo
                WaitlistEntryState.OFFERED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `occupied active hold returns SlotOccupied and writes no new offer hold or history`() {
        withCommitmentTables { seed ->
            seed.insertWaitingEntry("member-waiting")
            seed.insertBlockingHold("member-blocking")
            val service = serviceFor(mapOf(MemberId("member-waiting") to decision(seed, "member-waiting", "b")))

            assertFailsWith<SlotOccupied> {
                service.selectAndOffer(vacancy(seed), CORRELATION, ActorRef("SYSTEM"))
            }

            WaitlistOffers.selectAll().count() shouldBeEqualTo 1L
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 1L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun serviceFor(
        decisions: Map<MemberId, DecisionStamp>,
        offerTtl: Duration = Duration.ofMinutes(15),
    ): WaitlistOfferService {
        val waitlistRepository = WaitlistRepository()
        val matcher = WaitlistCandidateMatcher(
            repository = waitlistRepository,
            decisionPort = { _, memberIds, _ -> decisions.filterKeys { it in memberIds } },
        )
        return WaitlistOfferService(
            matcher = matcher,
            waitlistRepository = waitlistRepository,
            resourceAllocationRepository = ResourceAllocationRepository(),
            offerTtl = offerTtl,
        )
    }

    private fun CommitmentSeed.insertWaitingEntry(memberId: String): EntryIds {
        val seedClinicId = clinicId
        val doctorId = doctorId()
        val treatmentTypeId = treatmentTypeId()
        val entryId = WaitlistEntries.insertAndGetId {
            it[WaitlistEntries.tenantGroupId] = EntityID<Long>(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[WaitlistEntries.clinicId] = EntityID<Long>(seedClinicId, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[WaitlistEntries.treatmentTypeId] = EntityID<Long>(treatmentTypeId, TreatmentTypes)
            it[WaitlistEntries.doctorId] = EntityID<Long>(doctorId, Doctors)
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[priorityRank] = 1
            it[status] = WaitlistEntryState.WAITING
            it[waitingSince] = NOW
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }.value
        return EntryIds(entryId = entryId, doctorId = doctorId, treatmentTypeId = treatmentTypeId)
    }

    private fun CommitmentSeed.insertBlockingHold(memberId: String) {
        val entry = insertWaitingEntry(memberId)
        val scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, clinicId, MemberId(memberId))
        val offerId = WaitlistOffers.insertAndGetId {
            it[WaitlistOffers.tenantGroupId] = EntityID<Long>(scope.tenantGroupId, TenantGroups)
            it[WaitlistOffers.clinicId] = EntityID<Long>(scope.clinicId, Clinics)
            it[WaitlistOffers.memberId] = scope.memberId.value
            it[WaitlistOffers.waitlistEntryId] = EntityID<Long>(entry.entryId, WaitlistEntries)
            it[WaitlistOffers.vacancyKey] = "blocking-vacancy"
            it[WaitlistOffers.activeEntryKey] = "blocking-entry"
            it[WaitlistOffers.activeVacancyKey] = "blocking-vacancy"
            it[WaitlistOffers.resourceType] = ResourceType.PRACTITIONER
            it[WaitlistOffers.resourceId] = "doctor-${entry.doctorId}"
            it[WaitlistOffers.capacityUnits] = 1
            it[WaitlistOffers.maximumCapacity] = 1
            it[WaitlistOffers.doctorId] = entry.doctorId
            it[WaitlistOffers.treatmentTypeId] = entry.treatmentTypeId
            it[WaitlistOffers.startsAt] = START
            it[WaitlistOffers.endsAt] = END
            it[WaitlistOffers.expiresAt] = NOW.plusSeconds(900)
            it[WaitlistOffers.status] = WaitlistOfferState.OFFERED
            it[WaitlistOffers.bookingReliabilityDecisionId] = 70L
            it[WaitlistOffers.bookingReliabilityPolicyVersionId] = 80L
            it[WaitlistOffers.bookingReliabilityPolicyHash] = "a".repeat(64)
            it[WaitlistOffers.bookingReliabilityEvaluationDigest] = "b".repeat(64)
            it[WaitlistOffers.candidateRank] = 1
            it[WaitlistOffers.selectionReasonCode] = WaitlistReasonCode("AUTO_SELECTED").code
            it[WaitlistOffers.version] = 0L
            it[WaitlistOffers.createdAt] = NOW
            it[WaitlistOffers.updatedAt] = NOW
        }.value
        ResourceAllocationRepository().reserveWaitlistCapacityHold(
            scope = scope,
            offerId = offerId,
            hold = NewHold(
                vacancyKey = "blocking-vacancy",
                activeVacancyKey = "blocking-vacancy",
                resourceType = ResourceType.PRACTITIONER,
                resourceId = "doctor-${entry.doctorId}",
                startsAt = START,
                endsAt = END,
                capacityUnits = 1,
                maximumCapacity = 1,
                holdExpiresAt = NOW.plusSeconds(900),
            ),
            now = NOW,
        )
    }

    private fun CommitmentSeed.doctorId(): Long =
        Doctors
            .selectAll()
            .where { Doctors.clinicId eq clinicId }
            .orderBy(Doctors.id to SortOrder.ASC)
            .limit(1)
            .single()[Doctors.id].value

    private fun CommitmentSeed.treatmentTypeId(): Long =
        TreatmentTypes
            .selectAll()
            .where { TreatmentTypes.clinicId eq clinicId }
            .orderBy(TreatmentTypes.id to SortOrder.ASC)
            .limit(1)
            .single()[TreatmentTypes.id].value

    private fun vacancy(seed: CommitmentSeed): VacancyDescriptor {
        val doctorId = seed.doctorId()
        return VacancyDescriptor(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = seed.clinicId,
            treatmentTypeId = seed.treatmentTypeId(),
            doctorId = doctorId,
            startsAt = START,
            endsAt = END,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$doctorId",
            capacityUnits = 1,
            maximumCapacity = 1,
            now = NOW,
        )
    }

    private fun decision(seed: CommitmentSeed, memberId: String, marker: String): DecisionStamp =
        DecisionStamp(
            scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, seed.clinicId, MemberId(memberId)),
            decisionId = marker.first().code.toLong(),
            policyVersionId = marker.last().code.toLong() + 100,
            policyHash = marker.repeat(64),
            evaluationDigest = marker.repeat(64),
            expiresAt = NOW.plusSeconds(3600),
        )

    private data class EntryIds(
        val entryId: Long,
        val doctorId: Long,
        val treatmentTypeId: Long,
    )

    private companion object {
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
        private val START: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val END: Instant = Instant.parse("2026-08-01T09:30:00Z")
        private val CORRELATION = CorrelationId("corr-offer-1")
    }
}
