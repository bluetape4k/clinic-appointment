package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
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
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.ReconcileWaitlistHoldsCommand
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistRecoveryService
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class WaitlistRecoveryServiceTest {
    private val repository = WaitlistRepository()
    private val resourceRepository = ResourceAllocationRepository()

    @Test
    fun `reconcile expires bounded active holds and preserves audit rows`() {
        withWaitlistTables {
            val expired = offeredRows(memberId = "member-expired", suffix = "expired", holdExpiresAt = EXPIRES_AT)
            val future = offeredRows(
                memberId = "member-future",
                suffix = "future",
                holdExpiresAt = Instant.parse("2026-08-01T09:20:00Z"),
            )
            val offeredPastStart = offeredRows(
                memberId = "member-offered-started",
                suffix = "offered-started",
                startsAt = Instant.parse("2026-08-01T08:40:00Z"),
                holdExpiresAt = Instant.parse("2026-08-01T09:20:00Z"),
            )
            val acceptedPastStart = offeredRows(
                memberId = "member-accepted",
                suffix = "accepted",
                startsAt = Instant.parse("2026-08-01T08:40:00Z"),
                holdExpiresAt = Instant.parse("2026-08-01T09:20:00Z"),
                accepted = true,
            )
            val service = WaitlistRecoveryService(
                waitlistRepository = repository,
                resourceAllocationRepository = resourceRepository,
                clock = Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
            )

            val result = service.reconcileWaitlistHolds(
                ReconcileWaitlistHoldsCommand(
                    limit = 500,
                    now = RECOVERY_TIME,
                    correlationId = CorrelationId("recovery-correlation-1"),
                    actorRef = ActorRef("recovery:waitlist-expiry"),
                ),
            )

            result.count shouldBeEqualTo 3
            (result.lastId != null).shouldBeTrue()
            repository.findOfferForUpdate(scope("member-expired"), expired.offerId)?.status shouldBeEqualTo
                WaitlistOfferState.EXPIRED
            repository.findHoldForUpdate(scope("member-expired"), expired.holdId)?.status shouldBeEqualTo
                WaitlistCapacityHoldState.EXPIRED
            repository.findEntryForUpdate(scope("member-expired"), expired.entryId)?.status shouldBeEqualTo
                WaitlistEntryState.EXPIRED
            repository.findHoldForUpdate(scope("member-accepted"), acceptedPastStart.holdId)?.status shouldBeEqualTo
                WaitlistCapacityHoldState.EXPIRED
            repository.findOfferForUpdate(scope("member-offered-started"), offeredPastStart.offerId)?.status shouldBeEqualTo
                WaitlistOfferState.EXPIRED
            repository.findHoldForUpdate(scope("member-offered-started"), offeredPastStart.holdId)?.status shouldBeEqualTo
                WaitlistCapacityHoldState.EXPIRED
            repository.findOfferForUpdate(scope("member-future"), future.offerId)?.status shouldBeEqualTo
                WaitlistOfferState.OFFERED
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 4L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 3L
        }
    }

    private fun offeredRows(
        memberId: String,
        suffix: String,
        startsAt: Instant = STARTS_AT,
        holdExpiresAt: Instant,
        accepted: Boolean = false,
    ): OfferFixtureIds {
        val entryId = insertEntry(memberId = memberId)
        val entry = repository.findEntryForUpdate(scope(memberId), entryId) ?: error("entry must exist")
        val ids = repository.insertOfferAndHold(
            scope = scope(memberId),
            entry = entry,
            offer = newOffer(memberId, suffix, startsAt, holdExpiresAt),
            hold = newHold(suffix, startsAt, holdExpiresAt),
            now = RECOVERY_TIME.minusSeconds(600),
        )
        repository.casEntry(scope(memberId), entryId, expectedVersion = 0L, WaitlistEntryState.WAITING, WaitlistEntryState.OFFERED)
            .shouldBeTrue()
        if (accepted) {
            repository.casHold(
                scope = scope(memberId),
                holdId = ids.holdId,
                expectedVersion = 0L,
                from = WaitlistCapacityHoldState.OFFERED,
                to = WaitlistCapacityHoldState.ACCEPTED,
            ).shouldBeTrue()
            repository.casOffer(
                scope = scope(memberId),
                offerId = ids.offerId,
                expectedVersion = 0L,
                from = WaitlistOfferState.OFFERED,
                to = WaitlistOfferState.ACCEPTED,
            ).shouldBeTrue()
            repository.casEntry(
                scope = scope(memberId),
                entryId = entryId,
                expectedVersion = 1L,
                from = WaitlistEntryState.OFFERED,
                to = WaitlistEntryState.ACCEPTED,
            ).shouldBeTrue()
        }
        return OfferFixtureIds(entryId = entryId, offerId = ids.offerId, holdId = ids.holdId)
    }

    private fun withWaitlistTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            Doctors,
            TreatmentTypes,
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

    private fun insertEntry(memberId: String): Long =
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
            it[priorityRank] = 1
            it[status] = WaitlistEntryState.WAITING
            it[waitingSince] = RECOVERY_TIME.minusSeconds(600)
            it[version] = 0L
            it[createdAt] = RECOVERY_TIME.minusSeconds(600)
            it[updatedAt] = RECOVERY_TIME.minusSeconds(600)
        }.value

    private fun newOffer(
        memberId: String,
        suffix: String,
        startsAt: Instant,
        expiresAt: Instant,
    ): NewOffer =
        NewOffer(
            vacancyKey = "vacancy-key-$suffix",
            activeEntryKey = "entry-$suffix",
            activeVacancyKey = "vacancy-$suffix",
            doctorId = DOCTOR_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            startsAt = startsAt,
            endsAt = ENDS_AT,
            expiresAt = expiresAt,
            decisionStamp = decisionStamp(memberId),
            candidateRank = 1,
            selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
        )

    private fun newHold(suffix: String, startsAt: Instant, holdExpiresAt: Instant): NewHold =
        NewHold(
            vacancyKey = "vacancy-key-$suffix",
            activeVacancyKey = "vacancy-$suffix",
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID-$suffix",
            startsAt = startsAt,
            endsAt = ENDS_AT,
            capacityUnits = 1,
            maximumCapacity = 1,
            holdExpiresAt = holdExpiresAt,
        )

    private fun scope(memberId: String): WaitlistScope =
        WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, MemberId(memberId))

    private fun decisionStamp(memberId: String): DecisionStamp =
        DecisionStamp(
            scope = scope(memberId),
            decisionId = 70L,
            policyVersionId = 80L,
            policyHash = DIGEST_A,
            evaluationDigest = DIGEST_B,
            expiresAt = Instant.parse("2026-08-01T10:00:00Z"),
        )

    private data class OfferFixtureIds(
        val entryId: Long,
        val offerId: Long,
        val holdId: Long,
    )

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private val RECOVERY_TIME: Instant = Instant.parse("2026-08-01T08:50:00Z")
        private val STARTS_AT: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val ENDS_AT: Instant = Instant.parse("2026-08-01T09:30:00Z")
        private val EXPIRES_AT: Instant = Instant.parse("2026-08-01T08:45:00Z")
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
