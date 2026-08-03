package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyVersions
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyDocumentCodec
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistPolicyRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistPolicyRepositoryTest {
    private val repository = WaitlistPolicyRepository()
    private val codec = WaitlistPolicyDocumentCodec()

    @Test
    fun `clinic row lock 아래 최초 generation zero만 activation한다`() {
        withPolicyTables {
            val first = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(),
                effectiveFrom = BASE_TIME,
                effectiveUntil = BASE_TIME.plusSeconds(3_600),
                actor = ACTOR,
                now = BASE_TIME,
            )
            val second = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(waitingAgeWeight = 2),
                effectiveFrom = BASE_TIME.plusSeconds(7_200),
                effectiveUntil = null,
                actor = ACTOR,
                now = BASE_TIME,
            )

            val activated = repository.activate(
                scope = scope(),
                policyId = first.id,
                expectedGeneration = 0L,
                actor = ACTOR,
                now = BASE_TIME.plusSeconds(1),
            )

            activated.generation shouldBeEqualTo 1L
            activated.status shouldBeEqualTo WaitlistPolicyState.ACTIVE
            assertFailsWith<WaitlistPolicyConflict> {
                repository.activate(
                    scope = scope(),
                    policyId = second.id,
                    expectedGeneration = 0L,
                    actor = ACTOR,
                    now = BASE_TIME.plusSeconds(2),
                )
            }
            val events = policyEvents()
            events.map { it[WaitlistPolicyEvents.eventType] } shouldBeEqualTo listOf(
                "WAITLIST_POLICY_DRAFT_CREATED",
                "WAITLIST_POLICY_DRAFT_CREATED",
                "WAITLIST_POLICY_ACTIVATED",
            )
            events.map { it[WaitlistPolicyEvents.actorRef] } shouldBeEqualTo listOf(ACTOR.value, ACTOR.value, ACTOR.value)
            events.map { it[WaitlistPolicyEvents.policyVersion] } shouldBeEqualTo listOf(1L, 2L, 1L)
            events.map { it[WaitlistPolicyEvents.toGeneration] } shouldBeEqualTo listOf(-1L, -2L, 1L)
            events.map { it[WaitlistPolicyEvents.reasonCode] } shouldBeEqualTo listOf(
                "DRAFT_CREATED",
                "DRAFT_CREATED",
                "ACTIVATION",
            )
        }
    }

    @Test
    fun `overlapping effective window is rejected before publishing a draft`() {
        withPolicyTables {
            val active = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(),
                effectiveFrom = BASE_TIME,
                effectiveUntil = BASE_TIME.plusSeconds(3_600),
                actor = ACTOR,
                now = BASE_TIME,
            )
            repository.activate(scope(), active.id, expectedGeneration = 0L, actor = ACTOR, now = BASE_TIME)
            val overlapping = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(recoveryWeight = 5),
                effectiveFrom = BASE_TIME.plusSeconds(1_800),
                effectiveUntil = BASE_TIME.plusSeconds(5_400),
                actor = ACTOR,
                now = BASE_TIME,
            )

            assertFailsWith<WaitlistPolicyConflict> {
                repository.activate(scope(), overlapping.id, expectedGeneration = 1L, actor = ACTOR, now = BASE_TIME)
            }

            repository.findById(overlapping.id).shouldNotBeNull().status shouldBeEqualTo WaitlistPolicyState.DRAFT
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 3L
        }
    }

    @Test
    fun `stale generation rejects activation without retiring the active policy`() {
        withPolicyTables {
            val active = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(),
                effectiveFrom = BASE_TIME,
                effectiveUntil = BASE_TIME.plusSeconds(3_600),
                actor = ACTOR,
                now = BASE_TIME,
            )
            repository.activate(scope(), active.id, expectedGeneration = 0L, actor = ACTOR, now = BASE_TIME)
            val later = repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(benefitWeight = 7),
                effectiveFrom = BASE_TIME.plusSeconds(3_600),
                effectiveUntil = null,
                actor = ACTOR,
                now = BASE_TIME,
            )

            assertFailsWith<WaitlistPolicyConflict> {
                repository.activate(scope(), later.id, expectedGeneration = 0L, actor = ACTOR, now = BASE_TIME)
            }

            repository.findActive(scope()).shouldNotBeNull().id shouldBeEqualTo active.id
            repository.findById(later.id).shouldNotBeNull().status shouldBeEqualTo WaitlistPolicyState.DRAFT
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 3L
        }
    }

    @Test
    fun `draft row and creation event rollback together inside caller transaction`() {
        withPolicyTables {
            repository.insertDraft(
                scope = scope(),
                policy = decodedPolicy(),
                effectiveFrom = BASE_TIME,
                effectiveUntil = null,
                actor = ACTOR,
                now = BASE_TIME,
            )
            WaitlistPolicyVersions.selectAll().count() shouldBeEqualTo 1L
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 1L

            rollback()

            WaitlistPolicyVersions.selectAll().count() shouldBeEqualTo 0L
            WaitlistPolicyEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun withPolicyTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            WaitlistPolicyVersions,
            WaitlistPolicyEvents,
        ) {
            seedClinic()
            block()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.seedClinic() {
        Clinics.insert {
            it[id] = EntityID(CLINIC_ID, Clinics)
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Waitlist Policy Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }
    }

    private fun scope(): ClinicWaitlistScope =
        ClinicWaitlistScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, CLINIC_ID)

    private fun policyEvents() =
        WaitlistPolicyEvents
            .selectAll()
            .orderBy(WaitlistPolicyEvents.id to SortOrder.ASC)
            .toList()

    private fun decodedPolicy(
        urgencyWeight: Int = 1,
        recoveryWeight: Int = 1,
        benefitWeight: Int = 1,
        reliabilityWeight: Int = 1,
        waitingAgeWeight: Int = 1,
        slotFitWeight: Int = 1,
    ) = codec.decode(
        """
        {
          "urgencyWeight": $urgencyWeight,
          "recoveryWeight": $recoveryWeight,
          "benefitWeight": $benefitWeight,
          "reliabilityWeight": $reliabilityWeight,
          "waitingAgeWeight": $waitingAgeWeight,
          "slotFitWeight": $slotFitWeight
        }
        """.trimIndent(),
    )

    private companion object {
        const val CLINIC_ID = 41L
        val BASE_TIME: Instant = Instant.parse("2026-08-03T09:00:00Z")
        val ACTOR: ActorRef = ActorRef("staff:policy-admin")
    }
}
