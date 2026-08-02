package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
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
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.ReconcileWaitlistHoldsCommand
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.CommitmentSeed
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.repository.withCommitmentTables
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistRecoveryService
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * recovery worker가 중간에 재시작되어도 durable terminal row와 history가 한 번만
 * 기록되는지 검증합니다. 별도 프로세스를 띄우는 대신 실제 H2 transaction을 commit한
 * 뒤 새 service 인스턴스로 두 번째 tick을 실행해 restart 경계를 재현합니다.
 */
class WaitlistRecoveryRestartTest {

    @Test
    fun `fresh recovery instance replays terminal state without duplicate history`() {
        withCommitmentTables { seed ->
            val fixture = insertExpiredOffer(seed)
            val first = recoveryService()
            val firstResult = first.reconcileWaitlistHolds(command())

            firstResult.count shouldBeEqualTo 1
            firstResult.lastId shouldBeEqualTo fixture.holdId
            commit()

            val restarted = recoveryService()
            val secondResult = restarted.reconcileWaitlistHolds(command())

            secondResult.count shouldBeEqualTo 0
            secondResult.lastId shouldBeEqualTo null
            WaitlistOffers.selectAll().where { WaitlistOffers.id eq fixture.offerId }.single()[WaitlistOffers.status] shouldBeEqualTo
                WaitlistOfferState.EXPIRED
            WaitlistCapacityHolds.selectAll().where { WaitlistCapacityHolds.id eq fixture.holdId }.single()[WaitlistCapacityHolds.status] shouldBeEqualTo
                WaitlistCapacityHoldState.EXPIRED
            WaitlistEntries.selectAll().where { WaitlistEntries.id eq fixture.entryId }.single()[WaitlistEntries.status] shouldBeEqualTo
                WaitlistEntryState.EXPIRED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    private fun recoveryService(): WaitlistRecoveryService =
        WaitlistRecoveryService(
            waitlistRepository = WaitlistRepository(),
            resourceAllocationRepository = ResourceAllocationRepository(),
            clock = Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC),
        )

    private fun command(): ReconcileWaitlistHoldsCommand =
        ReconcileWaitlistHoldsCommand(
            limit = 100,
            now = RECOVERY_TIME,
            correlationId = CorrelationId("recovery-restart-1"),
            actorRef = ActorRef("recovery:waitlist-expiry"),
        )

    private fun CommitmentSeed.doctorId(): Long =
        Doctors
            .selectAll()
            .where { Doctors.clinicId eq clinicId }
            .single()[Doctors.id]
            .value

    private fun CommitmentSeed.treatmentTypeId(): Long =
        TreatmentTypes
            .selectAll()
            .where { TreatmentTypes.clinicId eq clinicId }
            .single()[TreatmentTypes.id]
            .value

    private fun insertExpiredOffer(seed: CommitmentSeed): FixtureIds {
        val doctorId = seed.doctorId()
        val treatmentTypeId = seed.treatmentTypeId()
        val memberId = MemberId("waitlist-recovery-restart-member")
        val scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, seed.clinicId, memberId)
        val entryId = insertWaitingEntry(seed, doctorId, treatmentTypeId, memberId.value)
        val entry = WaitlistRepository().findEntryForUpdate(scope, entryId) ?: error("entry must exist")
        val ids = WaitlistRepository().insertOfferAndHold(
            scope = scope,
            entry = entry,
            offer = NewOffer(
                vacancyKey = "recovery-restart-vacancy",
                activeEntryKey = "recovery-restart-entry",
                activeVacancyKey = "recovery-restart-vacancy",
                doctorId = doctorId,
                treatmentTypeId = treatmentTypeId,
                startsAt = START,
                endsAt = END,
                expiresAt = EXPIRED_AT,
                decisionStamp = DecisionStamp(
                    scope = scope,
                    decisionId = 701L,
                    policyVersionId = 801L,
                    policyHash = "a".repeat(64),
                    evaluationDigest = "b".repeat(64),
                    expiresAt = RECOVERY_TIME.plusSeconds(600),
                ),
                candidateRank = 1,
                selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
            ),
            hold = NewHold(
                vacancyKey = "recovery-restart-vacancy",
                activeVacancyKey = "recovery-restart-vacancy",
                resourceType = ResourceType.PRACTITIONER,
                resourceId = "recovery-restart-doctor-$doctorId",
                startsAt = START,
                endsAt = END,
                capacityUnits = 1,
                maximumCapacity = 1,
                holdExpiresAt = EXPIRED_AT,
            ),
            now = RECOVERY_TIME.minusSeconds(600),
        )
        WaitlistRepository().casEntry(
            scope = scope,
            entryId = entryId,
            expectedVersion = 0L,
            from = WaitlistEntryState.WAITING,
            to = WaitlistEntryState.OFFERED,
        ).shouldBeTrue()
        return FixtureIds(entryId = entryId, offerId = ids.offerId, holdId = ids.holdId)
    }

    private fun insertWaitingEntry(
        seed: CommitmentSeed,
        doctorId: Long,
        treatmentTypeId: Long,
        memberId: String,
    ): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[WaitlistEntries.clinicId] = EntityID(seed.clinicId, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[WaitlistEntries.treatmentTypeId] = EntityID(treatmentTypeId, TreatmentTypes)
            it[WaitlistEntries.doctorId] = EntityID(doctorId, Doctors)
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

    private data class FixtureIds(
        val entryId: Long,
        val offerId: Long,
        val holdId: Long,
    )

    private companion object {
        private val RECOVERY_TIME: Instant = Instant.parse("2026-08-01T08:50:00Z")
        private val START: Instant = Instant.parse("2026-08-01T09:00:00Z")
        private val END: Instant = Instant.parse("2026-08-01T09:30:00Z")
        private val EXPIRED_AT: Instant = Instant.parse("2026-08-01T08:45:00Z")
    }
}
