package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClaimWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStale
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.OfferExpired
import io.bluetape4k.clinic.appointment.model.waitlist.ReleaseWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.SlotOccupied
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.reliability.BookingReliabilityDecisionBatchPort
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferClaimService
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class WaitlistOfferClaimServiceTest {
    private val repository = WaitlistRepository()
    private val resourceRepository = ResourceAllocationRepository()

    @Test
    fun `offered claim accepts existing hold and appends bounded history`() {
        withWaitlistTables {
            val ids = offeredRows()
            val decisionPort = StaticDecisionPort(decisionStamp())
            val service = claimService(decisionPort, CLAIM_TIME)

            val claimed = service.claim(claimCommand(ids.offerId))

            claimed.offerId shouldBeEqualTo ids.offerId
            claimed.holdId shouldBeEqualTo ids.holdId
            claimed.memberId shouldBeEqualTo scope().memberId
            claimed.holdExpiresAt shouldBeEqualTo EXPIRES_AT
            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.ACCEPTED
            repository.findHoldForUpdate(scope(), ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.ACCEPTED
            repository.findEntryForUpdate(scope(), ids.entryId)?.status shouldBeEqualTo WaitlistEntryState.ACCEPTED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
            decisionPort.evaluatedAt shouldBeEqualTo CLAIM_TIME
        }
    }

    @Test
    fun `accepted offer replay returns existing hold without creating rows`() {
        withWaitlistTables {
            val ids = offeredRows()
            val service = claimService(StaticDecisionPort(decisionStamp()), CLAIM_TIME)

            service.claim(claimCommand(ids.offerId))
            val replayed = service.claim(claimCommand(ids.offerId))

            replayed.holdId shouldBeEqualTo ids.holdId
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 1L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `accepted offer replay after slot start expires the handoff instead of returning it`() {
        withWaitlistTables {
            val ids = offeredRows()
            val service = claimService(StaticDecisionPort(decisionStamp()), CLAIM_TIME)

            service.claim(claimCommand(ids.offerId))

            assertFailsWith<OfferExpired> {
                claimService(StaticDecisionPort(decisionStamp()), STARTS_AT)
                    .claim(claimCommand(ids.offerId))
            }

            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.EXPIRED
            repository.findHoldForUpdate(scope(), ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.EXPIRED
            repository.findEntryForUpdate(scope(), ids.entryId)?.status shouldBeEqualTo WaitlistEntryState.EXPIRED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `offered claim repairs a missing hold from the immutable offer resource snapshot`() {
        withWaitlistTables {
            val ids = offeredRows()
            WaitlistCapacityHolds.deleteWhere { WaitlistCapacityHolds.id eq ids.holdId }

            val claimed = claimService(StaticDecisionPort(decisionStamp()), CLAIM_TIME)
                .claim(claimCommand(ids.offerId))

            claimed.offerId shouldBeEqualTo ids.offerId
            (claimed.holdId != ids.holdId).shouldBeTrue()
            repository.findHoldByOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo
                WaitlistCapacityHoldState.ACCEPTED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `missing offered hold repair fails closed when the resource became occupied`() {
        withWaitlistTables {
            val ids = offeredRows()
            val blockerEntryId = insertEntry()
            val blockerEntry = repository.findEntryForUpdate(scope(), blockerEntryId) ?: error("entry must exist")
            repository.insertOfferAndHold(
                scope = scope(),
                entry = blockerEntry,
                offer = newOffer(blockerEntryId),
                hold = newHold(blockerEntryId),
                now = CLAIM_TIME,
            )
            WaitlistCapacityHolds.deleteWhere { WaitlistCapacityHolds.id eq ids.holdId }

            assertFailsWith<SlotOccupied> {
                claimService(StaticDecisionPort(decisionStamp()), CLAIM_TIME)
                    .claim(claimCommand(ids.offerId))
            }

            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.OFFERED
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 1L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `expired claim terminals hold offer and entry in the same transaction`() {
        withWaitlistTables {
            val ids = offeredRows()
            val service = claimService(StaticDecisionPort(decisionStamp()), Instant.parse("2026-08-01T08:50:00Z"))

            assertFailsWith<OfferExpired> {
                service.claim(claimCommand(ids.offerId))
            }

            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.EXPIRED
            repository.findHoldForUpdate(scope(), ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.EXPIRED
            repository.findEntryForUpdate(scope(), ids.entryId)?.status shouldBeEqualTo WaitlistEntryState.EXPIRED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `stale decision does not accept offer or hold`() {
        withWaitlistTables {
            val ids = offeredRows()
            val stale = decisionStamp(evaluationDigest = DIGEST_C)
            val service = claimService(StaticDecisionPort(stale), CLAIM_TIME)

            assertFailsWith<DecisionStale> {
                service.claim(claimCommand(ids.offerId))
            }

            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.OFFERED
            repository.findHoldForUpdate(scope(), ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.OFFERED
            repository.findEntryForUpdate(scope(), ids.entryId)?.status shouldBeEqualTo WaitlistEntryState.OFFERED
        }
    }

    @Test
    fun `release closes active hold offer and entry with audit row preserved`() {
        withWaitlistTables {
            val ids = offeredRows()
            val service = claimService(StaticDecisionPort(decisionStamp()), CLAIM_TIME)

            val withdrawn = service.release(
                ReleaseWaitlistOfferCommand(
                    offerId = ids.offerId,
                    scope = scope(),
                    expectedVersion = 0L,
                    correlationId = CorrelationId("claim-release-1"),
                    actorRef = ActorRef("staff:waitlist-operator"),
                    reason = WaitlistReasonCode("STAFF_WITHDRAWN"),
                    now = CLAIM_TIME,
                ),
            )

            withdrawn.holdId shouldBeEqualTo ids.holdId
            repository.findOfferForUpdate(scope(), ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.WITHDRAWN
            repository.findHoldForUpdate(scope(), ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.RELEASED
            repository.findEntryForUpdate(scope(), ids.entryId)?.status shouldBeEqualTo WaitlistEntryState.WITHDRAWN
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 1L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    private fun claimService(
        decisionPort: BookingReliabilityDecisionBatchPort,
        now: Instant,
    ): WaitlistOfferClaimService =
        WaitlistOfferClaimService(
            waitlistRepository = repository,
            resourceAllocationRepository = resourceRepository,
            decisionBatchPort = decisionPort,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun claimCommand(offerId: Long): ClaimWaitlistOfferCommand =
        ClaimWaitlistOfferCommand(
            offerId = offerId,
            scope = scope(),
            expectedVersion = 0L,
            correlationId = CorrelationId("claim-correlation-1"),
            actorRef = ActorRef("SYSTEM"),
        )

    private fun offeredRows(): OfferFixtureIds {
        val entryId = insertEntry()
        val entry = repository.findEntryForUpdate(scope(), entryId) ?: error("entry must exist")
        val ids = repository.insertOfferAndHold(
            scope = scope(),
            entry = entry,
            offer = newOffer(entryId),
            hold = newHold(entryId),
            now = CLAIM_TIME.minusSeconds(600),
        )
        repository.casEntry(scope(), entryId, expectedVersion = 0L, WaitlistEntryState.WAITING, WaitlistEntryState.OFFERED)
            .shouldBeTrue()
        return OfferFixtureIds(entryId = entryId, offerId = ids.offerId, holdId = ids.holdId)
    }

    private fun withWaitlistTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.POSTGRESQL,
            Clinics,
            Doctors,
            TreatmentTypes,
            Equipments,
            ConsultationTopics,
            Appointments,
            AppointmentCommitments,
            AppointmentProposals,
            ResourceAllocations,
            ResourceCapacityBuckets,
            WaitlistEntries,
            WaitlistOffers,
            WaitlistCapacityHolds,
            WaitlistOfferEvents,
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
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
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

    private fun insertEntry(): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[memberId] = MEMBER_ID
            it[treatmentTypeId] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
            it[doctorId] = EntityID(DOCTOR_ID, Doctors)
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[priorityRank] = 1
            it[status] = WaitlistEntryState.WAITING
            it[waitingSince] = CLAIM_TIME.minusSeconds(600)
            it[version] = 0L
            it[createdAt] = CLAIM_TIME.minusSeconds(600)
            it[updatedAt] = CLAIM_TIME.minusSeconds(600)
        }.value

    private fun newOffer(entryId: Long): NewOffer =
        NewOffer(
            vacancyKey = "vacancy-key-$entryId",
            activeEntryKey = "entry-$entryId",
            activeVacancyKey = "vacancy-$entryId",
            doctorId = DOCTOR_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            startsAt = STARTS_AT,
            endsAt = ENDS_AT,
            expiresAt = EXPIRES_AT,
            decisionStamp = decisionStamp(),
            candidateRank = 1,
            selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
        )

    private fun newHold(entryId: Long): NewHold =
        NewHold(
            vacancyKey = "vacancy-key-hold",
            activeVacancyKey = "vacancy-$entryId",
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            startsAt = STARTS_AT,
            endsAt = ENDS_AT,
            capacityUnits = 1,
            maximumCapacity = 1,
            holdExpiresAt = EXPIRES_AT,
        )

    private fun scope(): WaitlistScope =
        WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, MemberId(MEMBER_ID))

    private fun decisionStamp(
        evaluationDigest: String = DIGEST_B,
    ): DecisionStamp =
        DecisionStamp(
            scope = scope(),
            decisionId = 70L,
            policyVersionId = 80L,
            policyHash = DIGEST_A,
            evaluationDigest = evaluationDigest,
            expiresAt = Instant.parse("2026-08-01T08:40:00Z"),
        )

    private data class OfferFixtureIds(
        val entryId: Long,
        val offerId: Long,
        val holdId: Long,
    )

    private class StaticDecisionPort(
        private val stamp: DecisionStamp,
    ) : BookingReliabilityDecisionBatchPort {
        var evaluatedAt: Instant? = null

        override fun findLatestDecisionStamps(
            scope: WaitlistScope,
            memberIds: Collection<MemberId>,
            evaluatedAt: Instant,
        ): Map<MemberId, DecisionStamp> {
            this.evaluatedAt = evaluatedAt
            return memberIds.associateWith { stamp }
        }
    }

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private const val MEMBER_ID = "member-claim"
        private val CLAIM_TIME: Instant = Instant.parse("2026-08-01T08:10:00Z")
        private val STARTS_AT: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val ENDS_AT: Instant = Instant.parse("2026-08-01T09:30:00Z")
        private val EXPIRES_AT: Instant = Instant.parse("2026-08-01T08:45:00Z")
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
