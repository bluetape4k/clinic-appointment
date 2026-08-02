package io.bluetape4k.clinic.appointment.waitlist

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
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
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

    private companion object {
        private const val CLINIC_ID = 10L
        private const val DOCTOR_ID = 20L
        private const val TREATMENT_TYPE_ID = 30L
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
    }
}
