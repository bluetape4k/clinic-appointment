package io.bluetape4k.clinic.appointment.event.policy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * 정책 활성화 도메인 이벤트가 기존 outbox 계약으로 정확히 한 번 기록되는지 검증한다.
 *
 * tenant 기본 정책과 clinic 재정의의 nullable scope를 보존하고, actor audit ref·generation·
 * source version·correlation을 직렬화해 재현 가능한 감사 증거로 남기는지 확인한다. 같은
 * event ID 재시도는 멱등해야 하며 다른 payload로 기존 이벤트를 덮어쓰면 안 된다.
 */
class SchedulingPolicyEventRepositoryTest {

    private val repository = SchedulingPolicyEventRepository()

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:policy_event_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                ProductCatalogProjections,
                AppointmentPlans,
                SchedulingOutboxEvents,
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
        }
    }

    @Test
    fun `tenant policy activation writes deterministic redacted generic outbox event`() {
        val definition = definition()
        val generation = PolicyGenerationVector(tenantGeneration = 5L, clinicGeneration = 0L)
        val actor = ActorAuditRef(actorId = "admin-subject-7", actorRole = ActorRole.ADMIN)

        val eventId = transaction {
            repository.insertPolicyActivated(
                definition = definition,
                generation = generation,
                actor = actor,
                correlationId = "correlation-7",
            )
        }

        val expectedEventId = UUID.nameUUIDFromBytes(
            "SchedulingPolicyActivated:71:3:2026-07-27T03:00:00Z:5:0"
                .toByteArray(StandardCharsets.UTF_8)
        ).toString()
        eventId shouldBeEqualTo expectedEventId

        transaction {
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.eventId] shouldBeEqualTo expectedEventId
            outbox[SchedulingOutboxEvents.eventType] shouldBeEqualTo "SchedulingPolicyActivated"
            outbox[SchedulingOutboxEvents.aggregateType] shouldBeEqualTo "SCHEDULING_POLICY"
            outbox[SchedulingOutboxEvents.aggregateId] shouldBeEqualTo "71"
            outbox[SchedulingOutboxEvents.tenantGroupId].value shouldBeEqualTo 1L
            outbox[SchedulingOutboxEvents.clinicId].shouldBeNull()
            outbox[SchedulingOutboxEvents.planId].shouldBeNull()
            outbox[SchedulingOutboxEvents.causationEventId].shouldBeNull()
            outbox[SchedulingOutboxEvents.correlationId] shouldBeEqualTo "correlation-7"
            outbox[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.PENDING

            val payload = outbox[SchedulingOutboxEvents.payloadJson]
            payload.contains("\"definitionId\":71").shouldBeTrue()
            payload.contains("\"policyVersion\":3").shouldBeTrue()
            payload.contains("\"effectiveUntil\":null").shouldBeTrue()
            payload.contains("\"tenantGeneration\":5").shouldBeTrue()
            payload.contains("\"clinicGeneration\":0").shouldBeTrue()
            payload.contains("\"payloadHash\":\"${"a".repeat(64)}\"").shouldBeTrue()
            payload.contains("\"actorId\":\"admin-subject-7\"").shouldBeTrue()
            payload.contains("\"actorRole\":\"ADMIN\"").shouldBeTrue()
            payload.contains("Bearer super-secret").shouldBeFalse()
            payload.contains(definition.payloadJson).shouldBeFalse()
            payload.contains(definition.changeReason).shouldBeFalse()
        }
    }

    @Test
    fun `policy outbox insert participates in the caller transaction`() {
        assertFailsWith<IllegalStateException> {
            transaction {
                repository.insertPolicyActivated(
                    definition = definition().copy(id = 72L),
                    generation = PolicyGenerationVector(tenantGeneration = 6L, clinicGeneration = 0L),
                    actor = ActorAuditRef("system-activation", ActorRole.SYSTEM),
                    correlationId = "correlation-rollback",
                )
                error("force rollback")
            }
        }

        transaction {
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `clinic override activation preserves clinic scope without a legacy plan reference`() {
        val clinicId = transaction {
            Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Policy Clinic"
            }.value
        }
        val definition = definition().copy(
            id = 73L,
            scope = PolicyScope.CLINIC_OVERRIDE,
            clinicId = clinicId,
            clinicScopeKey = clinicId,
        )

        transaction {
            repository.insertPolicyActivated(
                definition = definition,
                generation = PolicyGenerationVector(tenantGeneration = 5L, clinicGeneration = 2L),
                actor = ActorAuditRef("clinic-admin", ActorRole.ADMIN),
                correlationId = "correlation-clinic",
            )
        }

        transaction {
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.clinicId]?.value shouldBeEqualTo clinicId
            outbox[SchedulingOutboxEvents.planId].shouldBeNull()
            outbox[SchedulingOutboxEvents.aggregateType] shouldBeEqualTo "SCHEDULING_POLICY"
        }
    }

    @Test
    fun `duplicate activation event identity is rejected by the outbox`() {
        val definition = definition().copy(id = 74L)
        val generation = PolicyGenerationVector(tenantGeneration = 7L, clinicGeneration = 0L)
        val actor = ActorAuditRef("admin-subject-7", ActorRole.ADMIN)

        transaction {
            repository.insertPolicyActivated(definition, generation, actor, "correlation-original")
        }

        assertFailsWith<ExposedSQLException> {
            transaction {
                repository.insertPolicyActivated(definition, generation, actor, "correlation-retry")
            }
        }
        transaction {
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    private fun definition() = SchedulingPolicyDefinitionRecord(
        id = 71L,
        tenantGroupId = 1L,
        scope = PolicyScope.TENANT_DEFAULT,
        clinicId = null,
        kind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
        version = 3L,
        schemaVersion = 1,
        lifecycle = PolicyLifecycle.ACTIVE,
        effectiveFrom = Instant.parse("2026-07-27T03:00:00Z"),
        effectiveUntil = null,
        revision = 4L,
        payloadHash = "a".repeat(64),
        payloadJson = """{"authorization":"Bearer super-secret","maximumConcurrentUnits":8}""",
        createdByActorId = "draft-author",
        createdByActorRole = ActorRole.STAFF,
        changeReason = "contains internal rollout details",
    )
}
