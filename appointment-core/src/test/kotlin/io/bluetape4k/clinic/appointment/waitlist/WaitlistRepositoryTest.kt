package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
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
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.OfferAlreadyExists
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCursor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistRepositoryTest {
    private val repository = WaitlistRepository()

    @Test
    fun `candidate page keeps tenant clinic scope and deterministic keyset order`() {
        withWaitlistTables {
            val earliestUnspecified = insertEntry(
                memberId = "member-unspecified",
                doctorId = null,
                priorityRank = 50,
                waitingSince = BASE_TIME.minusSeconds(600),
            )
            val doctorMatch = insertEntry(
                memberId = "member-doctor",
                doctorId = DOCTOR_ID,
                priorityRank = 1,
                waitingSince = BASE_TIME.minusSeconds(60),
            )
            insertEntry(memberId = "member-other-clinic", clinicId = OTHER_CLINIC_ID)

            val firstPage = repository.findCandidatePage(vacancy(), cursor = null, limit = 10)

            firstPage.map { it.id } shouldBeEqualTo listOf(doctorMatch, earliestUnspecified)

            val cursor = WaitlistCursor(
                slotFit = 1,
                priorityRank = 1,
                waitingSince = BASE_TIME.minusSeconds(60),
                entryId = doctorMatch,
            )
            repository.findCandidatePage(vacancy(), cursor = cursor, limit = 10)
                .map { it.id } shouldBeEqualTo listOf(earliestUnspecified)
        }
    }

    @Test
    fun `insert offer and hold is atomic and direct reads require matching member scope`() {
        withWaitlistTables {
            val entryId = insertEntry(memberId = "member-1")
            val entry = repository.findCandidatePage(vacancy(), cursor = null, limit = 1).single()
            val ids = repository.insertOfferAndHold(
                scope = scope("member-1"),
                entry = entry,
                offer = newOffer(entryId = entryId, activeEntryKey = "entry-$entryId", activeVacancyKey = "vacancy-1"),
                hold = newHold(activeVacancyKey = "vacancy-1"),
            )

            repository.findOfferForUpdate(scope("member-1"), ids.offerId)?.waitlistEntryId shouldBeEqualTo entryId
            repository.findHoldForUpdate(scope("member-1"), ids.holdId)?.offerId shouldBeEqualTo ids.offerId
            repository.findOfferForUpdate(scope("wrong-member"), ids.offerId).shouldBeNull()
            repository.findHoldForUpdate(scope("wrong-member"), ids.holdId).shouldBeNull()

            assertFailsWith<OfferAlreadyExists> {
                repository.insertOfferAndHold(
                    scope = scope("member-1"),
                    entry = entry,
                    offer = newOffer(entryId = entryId, activeEntryKey = "entry-$entryId", activeVacancyKey = "vacancy-1"),
                    hold = newHold(activeVacancyKey = "vacancy-duplicate"),
                )
            }
        }
    }

    @Test
    fun `hold insert failure rolls back the offer row in caller transaction`() {
        withWaitlistTables {
            val existingEntry = repository.findCandidatePage(vacancy(), cursor = null, limit = 1).singleOrNull()
                ?: insertEntry(memberId = "member-existing").let {
                    repository.findCandidatePage(vacancy(), cursor = null, limit = 1).single()
                }
            repository.insertOfferAndHold(
                scope = scope(existingEntry.scope.memberId.value),
                entry = existingEntry,
                offer = newOffer(existingEntry.id, activeEntryKey = "entry-existing", activeVacancyKey = "offer-existing"),
                hold = newHold(activeVacancyKey = "hold-conflict"),
            )

            val newEntryId = insertEntry(memberId = "member-new", waitingSince = BASE_TIME.plusSeconds(10))
            val newEntry = repository.findCandidatePage(vacancy(), cursor = null, limit = 10).single { it.id == newEntryId }

            assertFailsWith<ExposedSQLException> {
                repository.insertOfferAndHold(
                    scope = scope("member-new"),
                    entry = newEntry,
                    offer = newOffer(newEntryId, activeEntryKey = "entry-new", activeVacancyKey = "offer-new"),
                    hold = newHold(activeVacancyKey = "hold-conflict"),
                )
            }

            WaitlistOffers
                .selectAll()
                .where { WaitlistOffers.activeEntryKey eq "entry-new" }
                .count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `cas methods update only matching scope version and active expired holds stay bounded`() {
        withWaitlistTables {
            val entryId = insertEntry(memberId = "member-cas")
            val entry = repository.findCandidatePage(vacancy(), cursor = null, limit = 1).single()
            val ids = repository.insertOfferAndHold(
                scope = scope("member-cas"),
                entry = entry,
                offer = newOffer(entryId, activeEntryKey = "entry-cas", activeVacancyKey = "vacancy-cas"),
                hold = newHold(activeVacancyKey = "vacancy-cas", holdExpiresAt = BASE_TIME.plusSeconds(30)),
            )

            repository.casEntry(scope("member-cas"), entryId, expectedVersion = 0L, WaitlistEntryState.WAITING, WaitlistEntryState.OFFERED)
                .shouldBeTrue()
            repository.casOffer(scope("member-cas"), ids.offerId, expectedVersion = 0L, WaitlistOfferState.OFFERED, WaitlistOfferState.ACCEPTED)
                .shouldBeTrue()
            repository.casHold(
                scope = scope("member-cas"),
                holdId = ids.holdId,
                expectedVersion = 0L,
                from = WaitlistCapacityHoldState.OFFERED,
                to = WaitlistCapacityHoldState.ACCEPTED,
            ).shouldBeTrue()

            repository.casOffer(scope("member-cas"), ids.offerId, expectedVersion = 0L, WaitlistOfferState.OFFERED, WaitlistOfferState.DECLINED) shouldBeEqualTo false

            repository.findExpiredHolds(limit = 10, now = BASE_TIME.plusSeconds(31)).map { it.id } shouldBeEqualTo listOf(ids.holdId)
            repository.casHold(
                scope = scope("member-cas"),
                holdId = ids.holdId,
                expectedVersion = 1L,
                from = WaitlistCapacityHoldState.ACCEPTED,
                to = WaitlistCapacityHoldState.RELEASED,
            ).shouldBeTrue()
            repository.findExpiredHolds(limit = 10, now = BASE_TIME.plusSeconds(31)) shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `hold scope mismatch preserves rows before mutation`() {
        withWaitlistTables {
            val entryId = insertEntry(memberId = "member-hold")
            val entry = repository.findCandidatePage(vacancy(), cursor = null, limit = 1).single()
            val ids = repository.insertOfferAndHold(
                scope = scope("member-hold"),
                entry = entry,
                offer = newOffer(entryId, activeEntryKey = "entry-hold", activeVacancyKey = "vacancy-hold"),
                hold = newHold(activeVacancyKey = "vacancy-hold"),
            )

            assertFailsWith<HoldScopeMismatch> {
                repository.requireHoldForMutation(scope("other-member"), ids.holdId)
            }

            repository.findHoldForUpdate(scope("member-hold"), ids.holdId)?.status shouldBeEqualTo
                WaitlistCapacityHoldState.OFFERED
        }
    }

    @Test
    fun `append event stores bounded history`() {
        withWaitlistTables {
            val entryId = insertEntry(memberId = "member-event")
            val eventId = repository.appendEvent(
                WaitlistOfferEventRecord(
                    waitlistEntryId = entryId,
                    offerId = null,
                    holdId = null,
                    fromState = WaitlistEntryState.WAITING,
                    toState = WaitlistEntryState.OFFERED,
                    reasonCode = WaitlistReasonCode("OFFER_SELECTED"),
                    actorRef = ActorRef("SYSTEM"),
                    correlationId = CorrelationId("corr-event-1"),
                    occurredAt = BASE_TIME,
                    eventVersion = 1L,
                ),
            )

            eventId shouldBeEqualTo 1L
        }
    }

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
        Clinics.insert {
            it[id] = EntityID(OTHER_CLINIC_ID, Clinics)
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Other Clinic"
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
        TreatmentTypes.insert {
            it[id] = EntityID(OTHER_TREATMENT_TYPE_ID, TreatmentTypes)
            it[clinicId] = EntityID(OTHER_CLINIC_ID, Clinics)
            it[name] = "Other Care"
            it[defaultDurationMinutes] = 30
        }
    }

    private fun insertEntry(
        memberId: String,
        tenantGroupId: Long = TenantGroups.DEFAULT_TENANT_GROUP_ID,
        clinicId: Long = CLINIC_ID,
        treatmentTypeId: Long = TREATMENT_TYPE_ID,
        doctorId: Long? = null,
        priorityRank: Int = 1,
        waitingSince: Instant = BASE_TIME,
    ): Long =
        WaitlistEntries.insertAndGetId {
            it[WaitlistEntries.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[WaitlistEntries.clinicId] = EntityID(clinicId, Clinics)
            it[WaitlistEntries.memberId] = memberId
            it[WaitlistEntries.treatmentTypeId] = EntityID(treatmentTypeId, TreatmentTypes)
            it[WaitlistEntries.doctorId] = doctorId?.let { id -> EntityID(id, Doctors) }
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(8, 0)
            it[preferredEndTime] = LocalTime.of(12, 0)
            it[WaitlistEntries.priorityRank] = priorityRank
            it[status] = WaitlistEntryState.WAITING
            it[WaitlistEntries.waitingSince] = waitingSince
            it[version] = 0L
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
            now = BASE_TIME,
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
            expiresAt = BASE_TIME.plusSeconds(3600),
        )

    private fun newOffer(
        entryId: Long,
        activeEntryKey: String,
        activeVacancyKey: String,
        memberId: String = "member-1",
    ): NewOffer =
        NewOffer(
            vacancyKey = "vacancy-key-$entryId",
            activeEntryKey = activeEntryKey,
            activeVacancyKey = activeVacancyKey,
            doctorId = DOCTOR_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            startsAt = Instant.parse("2026-08-01T09:00:00Z"),
            endsAt = Instant.parse("2026-08-01T09:30:00Z"),
            expiresAt = Instant.parse("2026-08-01T08:45:00Z"),
            decisionStamp = decisionStamp(memberId),
            candidateRank = 1,
            selectionReasonCode = WaitlistReasonCode("AUTO_SELECTED"),
        )

    private fun newHold(
        activeVacancyKey: String,
        holdExpiresAt: Instant = Instant.parse("2026-08-01T08:45:00Z"),
    ): NewHold =
        NewHold(
            vacancyKey = "hold-$activeVacancyKey",
            activeVacancyKey = activeVacancyKey,
            resourceType = ResourceType.PRACTITIONER,
            resourceId = "doctor-$DOCTOR_ID",
            startsAt = Instant.parse("2026-08-01T09:00:00Z"),
            endsAt = Instant.parse("2026-08-01T09:30:00Z"),
            capacityUnits = 1,
            maximumCapacity = 1,
            holdExpiresAt = holdExpiresAt,
        )

    private companion object {
        private const val CLINIC_ID = 10L
        private const val OTHER_CLINIC_ID = 11L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private const val OTHER_TREATMENT_TYPE_ID = 31L
        private val BASE_TIME: Instant = Instant.parse("2026-08-01T08:00:00Z")
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
