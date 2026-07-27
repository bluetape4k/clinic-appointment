package io.bluetape4k.clinic.appointment.event.policy

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * scheduling-policy integration event를 다루는 caller-transaction repository이다.
 *
 * 이 repository는 transaction 생성도 activation state도 소유하지 않는다. activation
 * application service는 하나의 Exposed `transaction {}` 안에서 policy head를 갱신하고,
 * durable command를 완료하고, [insertPolicyActivated]를 호출해야 한다. 어느 단계에서든 실패하면
 * 모든 state change가 rollback되어 잘못된 activation notification이 발행되지 않는다.
 */
class SchedulingPolicyEventRepository {

    /**
     * deterministic하고 redacted된 `SchedulingPolicyActivated` event 하나를 추가한다.
     *
     * 이미 영속화된 `ACTIVE` definition만 publish할 수 있다. definition identity, version,
     * effective start, committed generation vector가 deterministic event identity를 구성한다.
     * raw policy JSON, change rationale, creator metadata, credential, command idempotency key는
     * wire payload에서 의도적으로 제외한다.
     *
     * 중복 deterministic ID는 outbox unique constraint를 위반한다. idempotent retry에서는
     * activation command layer가 두 번째 event를 삽입하지 않고 저장된 completion result를 반환해야 한다.
     * 그렇지 않으면 같은 activation에 대해 actor 또는 correlation metadata가 다른 event가 생길 수 있다.
     *
     * 이 메서드는 caller의 기존 Exposed transaction 안에서 실행되어야 하며 독립적으로 commit하지 않는다.
     *
     * @param definition 영속화된 active definition. ID, tenant, version, schema, revision,
     * payload hash가 immutable-definition contract를 만족해야 하며, scope가 outbox clinic ID의
     * null 여부를 결정한다.
     * @param generation 같은 activation에서 commit된 freshness counter. tenant generation은
     * 양수이고 clinic generation은 non-negative여야 한다.
     * @param actor 안정적인 trusted Gateway subject와 평가된 role. actor ID는 non-blank이고
     * 최대 160자여야 한다.
     * @param correlationId 1..128자의 safe ASCII로 제한된 비밀 없는 request/workflow trace ID.
     * correlation 전용이며 command activation에는 upstream event causation이 없다.
     * @return outbox event ID로 저장되는 deterministic UUID string.
     */
    fun insertPolicyActivated(
        definition: SchedulingPolicyDefinitionRecord,
        generation: PolicyGenerationVector,
        actor: ActorAuditRef,
        correlationId: String,
    ): String {
        val definitionId = requireNotNull(definition.id) {
            "definition.id is required for policy activation publication"
        }
        require(definitionId > 0) { "definition.id must be positive" }
        val scopeRef = PolicyScopeRef(definition.tenantGroupId, definition.scope, definition.clinicId)
        require(definition.clinicScopeKey == scopeRef.clinicScopeKey) {
            "definition.clinicScopeKey must match definition scope"
        }
        require(definition.version > 0) { "definition.version must be positive" }
        require(definition.schemaVersion > 0) { "definition.schemaVersion must be positive" }
        require(definition.revision > 0) { "definition.revision must be positive" }
        val effectiveUntil = definition.effectiveUntil
        require(effectiveUntil == null || effectiveUntil > definition.effectiveFrom) {
            "definition.effectiveUntil must be after effectiveFrom"
        }
        require(definition.lifecycle == PolicyLifecycle.ACTIVE) {
            "definition.lifecycle must be ACTIVE"
        }
        require(PAYLOAD_HASH_REGEX.matches(definition.payloadHash)) {
            "definition.payloadHash must be a lowercase SHA-256 hex value"
        }
        require(generation.tenantGeneration > 0) { "tenantGeneration must be positive" }
        require(generation.clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(actor.actorId.isNotBlank() && actor.actorId.length <= 160) {
            "actor.actorId must contain 1..160 non-blank characters"
        }
        require(CORRELATION_ID_REGEX.matches(correlationId)) {
            "correlationId must contain 1..128 safe ASCII characters"
        }

        val eventIdentity = (
            "$EVENT_TYPE:$definitionId:${definition.version}:${definition.effectiveFrom}:" +
                "${generation.tenantGeneration}:${generation.clinicGeneration}"
            )
        val eventId = UUID.nameUUIDFromBytes(
            eventIdentity.toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val event = SchedulingPolicyActivatedEvent(
            eventId = eventId,
            definitionId = definitionId,
            policyKind = definition.kind,
            policyVersion = definition.version,
            policyScope = definition.scope,
            effectiveFrom = definition.effectiveFrom,
            effectiveUntil = definition.effectiveUntil,
            generation = generation,
            payloadHash = definition.payloadHash,
            actor = actor,
            correlationId = correlationId,
        )

        SchedulingOutboxEvents.insertAndGetId {
            it[SchedulingOutboxEvents.eventId] = event.eventId
            it[causationEventId] = null
            it[SchedulingOutboxEvents.correlationId] = event.correlationId
            it[eventType] = EVENT_TYPE
            it[tenantGroupId] = definition.tenantGroupId
            it[clinicId] = definition.clinicId
            it[planId] = null
            it[aggregateType] = AGGREGATE_TYPE
            it[aggregateId] = definitionId.toString()
            it[schemaVersion] = event.schemaVersion
            it[payloadJson] = event.toRedactedJson()
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
        return eventId
    }

    /**
     * source definition을 그대로 serialize하지 않고 안정적인 schema-v1 JSON을 만든다.
     *
     * 이 allow-list serializer를 writer 옆에 두면 raw policy JSON 또는 audit rationale의
     * 우발적 유출이 review에서 보이는 변경으로 드러난다.
     */
    private fun SchedulingPolicyActivatedEvent.toRedactedJson(): String =
        buildString {
            append('{')
            append("\"eventId\":").appendJsonString(eventId)
            append(",\"definitionId\":").append(definitionId)
            append(",\"policyKind\":").appendJsonString(policyKind.name)
            append(",\"policyVersion\":").append(policyVersion)
            append(",\"policyScope\":").appendJsonString(policyScope.name)
            append(",\"effectiveFrom\":").appendJsonString(effectiveFrom.toString())
            append(",\"effectiveUntil\":")
            effectiveUntil?.let { appendJsonString(it.toString()) } ?: append("null")
            append(",\"tenantGeneration\":").append(generation.tenantGeneration)
            append(",\"clinicGeneration\":").append(generation.clinicGeneration)
            append(",\"payloadHash\":").appendJsonString(payloadHash)
            append(",\"actorId\":").appendJsonString(actor.actorId)
            append(",\"actorRole\":").appendJsonString(actor.actorRole.name)
            append(",\"correlationId\":").appendJsonString(correlationId)
            append(",\"schemaVersion\":").append(schemaVersion)
            append('}')
        }

    /** control character와 metacharacter를 escape하여 JSON string 하나를 추가한다. */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val EVENT_TYPE = "SchedulingPolicyActivated"
        const val AGGREGATE_TYPE = "SCHEDULING_POLICY"
        val PAYLOAD_HASH_REGEX = Regex("[0-9a-f]{64}")
        val CORRELATION_ID_REGEX = Regex("[A-Za-z0-9._:/-]{1,128}")
    }
}
