package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
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
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.OutboxWriteFailed
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.NewVacancyJob
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.waitlist.NoopWaitlistOfferNotificationPort
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCandidateMatcher
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDeliveryResult
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistDeliveryService
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationDraft
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationPort
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

class WaitlistDeliveryServiceTest {

    @Test
    fun `no candidate closes the claimed vacancy without notification`() {
        withDeliveryTables {
            val deliveryRepository = WaitlistDeliveryRepository()
            val job = deliveryRepository.insertVacancy(vacancy(vacancyGeneration = 1L))
            val claim = requireNotNull(deliveryRepository.claim(job.id, "worker-a", NOW, NOW.plusSeconds(30)))
            val notifications = mutableListOf<WaitlistOfferNotificationDraft>()
            val service = service(
                notificationPort = WaitlistOfferNotificationPort { notifications += it },
            )

            service.process(claim, NOW, CorrelationId("delivery-no-candidate")) shouldBeEqualTo
                WaitlistDeliveryResult.NoCandidate(job.id)
            deliveryRepository.findVacancy(job.id)?.status shouldBeEqualTo VacancyJobState.NO_CANDIDATE
            notifications shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `started vacancy is fenced as expired before candidate selection`() {
        withDeliveryTables {
            val deliveryRepository = WaitlistDeliveryRepository()
            val job = deliveryRepository.insertVacancy(
                vacancy(
                    vacancyGeneration = 1L,
                    startsAt = NOW.minusSeconds(1),
                    endsAt = NOW.plusSeconds(1_800),
                ),
            )
            val claim = requireNotNull(deliveryRepository.claim(job.id, "worker-a", NOW, NOW.plusSeconds(30)))
            val service = service()

            service.process(claim, NOW, CorrelationId("delivery-started")) shouldBeEqualTo
                WaitlistDeliveryResult.Expired(job.id)
            deliveryRepository.findVacancy(job.id)?.status shouldBeEqualTo VacancyJobState.EXPIRED
        }
    }

    @Test
    fun `outbox failure rolls back offer hold history and vacancy completion`() {
        withDeliveryTables {
            val deliveryRepository = WaitlistDeliveryRepository()
            val job = deliveryRepository.insertVacancy(vacancy(vacancyGeneration = 1L))
            val memberId = MemberId("member-delivery")
            insertWaitingEntry(memberId)
            val claim = requireNotNull(deliveryRepository.claim(job.id, "worker-a", NOW, NOW.plusSeconds(30)))
            commit()

            val service = service(
                notificationPort = WaitlistOfferNotificationPort { throw IllegalStateException("outbox down") },
                decisionStamps = mapOf(memberId to decisionStamp(memberId)),
            )
            assertFailsWith<OutboxWriteFailed> {
                service.process(claim, NOW, CorrelationId("delivery-outbox-failure"))
            }
            rollback()

            WaitlistOffers.selectAll().count() shouldBeEqualTo 0L
            WaitlistCapacityHolds.selectAll().count() shouldBeEqualTo 0L
            WaitlistOfferEvents.selectAll().count() shouldBeEqualTo 0L
            deliveryRepository.findVacancy(job.id)?.status shouldBeEqualTo VacancyJobState.PROCESSING
        }
    }

    @Test
    fun `notification draft contains only opaque identifiers and typed reason`() {
        val draft = WaitlistOfferNotificationDraft(
            tenantGroupId = 1L,
            clinicId = 10L,
            offerId = 20L,
            holdId = 30L,
            waitlistEntryId = 40L,
            reasonCode = WaitlistReasonCode("OFFER_CREATED"),
            correlationId = CorrelationId("delivery-draft"),
            occurredAt = NOW,
        )

        draft.offerId shouldBeEqualTo 20L
        draft.reasonCode shouldBeEqualTo WaitlistReasonCode("OFFER_CREATED")
        assertFailsWith<IllegalArgumentException> {
            WaitlistOfferNotificationDraft(
                tenantGroupId = 1L,
                clinicId = 10L,
                offerId = 20L,
                holdId = 30L,
                waitlistEntryId = 40L,
                reasonCode = WaitlistReasonCode("OFFER_CREATED"),
                correlationId = CorrelationId("delivery-draft"),
                occurredAt = NOW,
            ).copy(offerId = 0L)
        }
        (WaitlistOfferNotificationPort::class.java.declaredMethods.any { it.name == "enqueue" })
            .shouldBeEqualTo(true)
        OutboxWriteFailed().message shouldBeEqualTo "OUTBOX_WRITE_FAILED"
    }

    private fun service(
        notificationPort: WaitlistOfferNotificationPort = NoopWaitlistOfferNotificationPort,
        decisionStamps: Map<MemberId, DecisionStamp> = emptyMap(),
    ): WaitlistDeliveryService {
        val repository = WaitlistRepository()
        val matcher = WaitlistCandidateMatcher(
            repository = repository,
            decisionPort = { _, memberIds, _ -> decisionStamps.filterKeys { it in memberIds } },
        )
        return WaitlistDeliveryService(
            deliveryRepository = WaitlistDeliveryRepository(),
            offerService = WaitlistOfferService(
                matcher = matcher,
                waitlistRepository = repository,
                resourceAllocationRepository = ResourceAllocationRepository(),
            ),
            notificationPort = notificationPort,
        )
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.vacancy(
        vacancyGeneration: Long,
        startsAt: Instant = START,
        endsAt: Instant = END,
    ): NewVacancyJob =
        NewVacancyJob(
            tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            vacancyKey = "delivery-vacancy",
            vacancyGeneration = vacancyGeneration,
            activeVacancyKey = "delivery-vacancy",
            sourceAppointmentId = 100L,
            sourceTransitionId = "delivery-transition-$vacancyGeneration",
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            capacityUnits = 1,
            maximumCapacity = 1,
            treatmentTypeId = TREATMENT_TYPE_ID,
            doctorId = DOCTOR_ID,
            policyVersion = 1L,
            nextAttemptAt = NOW,
            vacancyStartsAt = startsAt,
            vacancyEndsAt = endsAt,
            now = NOW,
        )

    private fun withDeliveryTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
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
            WaitlistEntries,
            WaitlistOffers,
            WaitlistCapacityHolds,
            WaitlistOfferEvents,
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
            ResourceAllocations,
            ResourceCapacityBuckets,
            WaitlistVacancyJobs,
        ) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Delivery Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            Doctors.insert {
                it[id] = EntityID(DOCTOR_ID, Doctors)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Delivery Doctor"
            }
            TreatmentTypes.insert {
                it[id] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
                it[clinicId] = EntityID(CLINIC_ID, Clinics)
                it[name] = "Delivery Treatment"
                it[defaultDurationMinutes] = 30
            }
            block()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertWaitingEntry(memberId: MemberId): Long =
        WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[WaitlistEntries.memberId] = memberId.value
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

    private fun decisionStamp(memberId: MemberId): DecisionStamp =
        DecisionStamp(
            scope = WaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID, memberId),
            decisionId = 1L,
            policyVersionId = 1L,
            policyHash = "a".repeat(64),
            evaluationDigest = "b".repeat(64),
            expiresAt = NOW.plusSeconds(3_600),
        )

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private val NOW = Instant.parse("2026-08-01T08:00:00Z")
        private val START = Instant.parse("2026-08-01T09:00:00Z")
        private val END = Instant.parse("2026-08-01T09:30:00Z")
    }
}
