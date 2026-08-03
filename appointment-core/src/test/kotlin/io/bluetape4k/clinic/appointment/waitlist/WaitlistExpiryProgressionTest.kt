package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.NewVacancyJob
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.waitlist.NoopWaitlistOfferNotificationPort
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateMatcher
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDeliveryService
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferService
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistExpiryProgressionTest {

    @Test
    fun `expired offer creates one next generation and repeated expiry is idempotent`() {
        withExpiryTables {
            val waitlistRepository = WaitlistRepository()
            val deliveryRepository = WaitlistDeliveryRepository()
            val entryId = insertWaitingEntry()
            val entry = requireNotNull(waitlistRepository.findEntryForUpdate(scope, entryId))
            val ids = waitlistRepository.insertOfferAndHold(
                scope = scope,
                entry = entry,
                offer = newOffer,
                hold = newHold,
                now = NOW,
            )
            waitlistRepository.casEntry(
                scope = scope,
                entryId = entryId,
                expectedVersion = 0L,
                from = WaitlistEntryState.WAITING,
                to = WaitlistEntryState.OFFERED,
                now = NOW,
            ).shouldBeTrue()
            val firstJob = deliveryRepository.insertVacancy(vacancy)

            val service = deliveryService(waitlistRepository, deliveryRepository)
            val early = service.expireOffer(scope, ids.offerId, NOW.plusSeconds(60))
            early.previousGeneration shouldBeEqualTo 1L
            early.nextGeneration shouldBeEqualTo null
            deliveryRepository.findVacancy(firstJob.id)?.status shouldBeEqualTo VacancyJobState.READY
            waitlistRepository.findOfferForUpdate(scope, ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.OFFERED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 0L

            val expired = service.expireOffer(scope, ids.offerId, NOW.plusSeconds(900))
            expired.previousGeneration shouldBeEqualTo 1L
            expired.nextGeneration shouldBeEqualTo 2L
            expired.reasonCode shouldBeEqualTo WaitlistReasonCode("OFFER_EXPIRED")
            deliveryRepository.findVacancy(firstJob.id)?.status shouldBeEqualTo VacancyJobState.NO_CANDIDATE
            deliveryRepository.findVacancy(
                TenantGroups.DEFAULT_TENANT_GROUP_ID,
                CLINIC_ID,
                VACANCY_KEY,
                vacancyGeneration = 2L,
            )?.status shouldBeEqualTo VacancyJobState.READY
            waitlistRepository.findOfferForUpdate(scope, ids.offerId)?.status shouldBeEqualTo WaitlistOfferState.EXPIRED
            waitlistRepository.findHoldForUpdate(scope, ids.holdId)?.status shouldBeEqualTo WaitlistCapacityHoldState.EXPIRED
            waitlistRepository.findEntryForUpdate(scope, entryId)?.status shouldBeEqualTo WaitlistEntryState.EXPIRED
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L

            val replay = service.expireOffer(scope, ids.offerId, NOW.plusSeconds(1_200))
            replay.nextGeneration shouldBeEqualTo 2L
            deliveryRepository.findVacancy(firstJob.id)?.status shouldBeEqualTo VacancyJobState.NO_CANDIDATE
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    private fun deliveryService(
        waitlistRepository: WaitlistRepository,
        deliveryRepository: WaitlistDeliveryRepository,
    ): WaitlistDeliveryService {
        val matcher = WaitlistCandidateMatcher(
            repository = waitlistRepository,
            decisionPort = { _, _, _ -> emptyMap() },
        )
        return WaitlistDeliveryService(
            deliveryRepository = deliveryRepository,
            offerService = WaitlistOfferService(
                matcher = matcher,
                waitlistRepository = waitlistRepository,
                resourceAllocationRepository = ResourceAllocationRepository(),
            ),
            notificationPort = NoopWaitlistOfferNotificationPort,
        )
    }

    private fun withExpiryTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2_COMMITMENT,
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
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
            WaitlistVacancyJobs,
        ) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Expiry Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            Doctors.insert {
                it[id] = EntityID(DOCTOR_ID, Doctors)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Expiry Doctor"
            }
            TreatmentTypes.insert {
                it[id] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Expiry Treatment"
                it[defaultDurationMinutes] = 30
            }
            block()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertWaitingEntry(): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[WaitlistEntries.memberId] = scope.memberId.value
            it[treatmentTypeId] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
            it[doctorId] = EntityID(DOCTOR_ID, Doctors)
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[priorityRank] = 1
            it[status] = WaitlistEntryState.WAITING
            it[waitingSince] = NOW.minusSeconds(3_600)
            it[version] = 0L
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }.value

    private val newOffer: NewOffer
        get() = NewOffer(
            vacancyKey = VACANCY_KEY,
            activeEntryKey = "entry:expiry",
            activeVacancyKey = VACANCY_KEY,
            doctorId = DOCTOR_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            startsAt = START,
            endsAt = END,
            expiresAt = OFFER_EXPIRES,
            decisionStamp = DecisionStamp(
                scope = scope,
                decisionId = 1L,
                policyVersionId = 1L,
                policyHash = "a".repeat(64),
                evaluationDigest = "b".repeat(64),
                expiresAt = OFFER_EXPIRES,
            ),
            candidateRank = 1,
            selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
        )

    private val newHold: NewHold
        get() = NewHold(
            vacancyKey = VACANCY_KEY,
            activeVacancyKey = VACANCY_KEY,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            startsAt = START,
            endsAt = END,
            capacityUnits = 1,
            maximumCapacity = 1,
            holdExpiresAt = OFFER_EXPIRES,
        )

    private val vacancy: NewVacancyJob
        get() = NewVacancyJob(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            vacancyKey = VACANCY_KEY,
            vacancyGeneration = 1L,
            activeVacancyKey = VACANCY_KEY,
            sourceAppointmentId = 100L,
            sourceTransitionId = "expiry-transition-1",
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            capacityUnits = 1,
            maximumCapacity = 1,
            treatmentTypeId = TREATMENT_TYPE_ID,
            doctorId = DOCTOR_ID,
            policyVersion = 1L,
            nextAttemptAt = NOW,
            vacancyStartsAt = START,
            vacancyEndsAt = END,
            now = NOW,
        )

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private const val VACANCY_KEY = "expiry-vacancy"
        private val NOW = Instant.parse("2026-08-01T08:00:00Z")
        private val START = Instant.parse("2026-08-01T09:00:00Z")
        private val END = Instant.parse("2026-08-01T09:30:00Z")
        private val OFFER_EXPIRES = Instant.parse("2026-08-01T08:10:00Z")
        private val scope = WaitlistScope(
            TenantGroups.DEFAULT_TENANT_GROUP_ID,
            CLINIC_ID,
            MemberId("member-expiry"),
        )
    }
}
