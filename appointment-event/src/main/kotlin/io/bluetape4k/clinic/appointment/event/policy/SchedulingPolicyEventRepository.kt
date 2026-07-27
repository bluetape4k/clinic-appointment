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
 * Caller-transaction repository for scheduling-policy integration events.
 *
 * This repository deliberately owns neither transaction creation nor
 * activation state. The activation application service must update the policy
 * head, complete its durable command, and call [insertPolicyActivated] inside
 * one Exposed `transaction {}`. A failure in any step then rolls back every
 * state change and prevents a false activation notification.
 */
class SchedulingPolicyEventRepository {

    /**
     * Appends one deterministic, redacted `SchedulingPolicyActivated` event.
     *
     * Only an already persisted, `ACTIVE` definition may be published.
     * Definition identity, version, effective start, and the committed
     * generation vector form the deterministic event identity. Raw policy JSON,
     * change rationale, creator metadata, credentials, and command idempotency
     * keys are intentionally excluded from the wire payload.
     *
     * A duplicate deterministic ID violates the outbox unique constraint. The
     * activation command layer must return its stored completion result for an
     * idempotent retry instead of inserting a second event with potentially
     * different actor or correlation metadata.
     *
     * This method must run in the caller's existing Exposed transaction and
     * does not commit independently.
     *
     * @param definition Persisted active definition. Its ID, tenant, version,
     * schema, revision, and payload hash must satisfy the immutable-definition
     * contract; scope determines whether the outbox clinic ID is null.
     * @param generation Freshness counters committed by the same activation.
     * Tenant generation must be positive; clinic generation is non-negative.
     * @param actor Stable trusted Gateway subject and evaluated role. The actor
     * ID must be non-blank and at most 160 characters.
     * @param correlationId Non-secret request/workflow trace ID of 1..128 safe
     * ASCII characters. It is correlation only; command activation has no
     * upstream event causation.
     * @return Deterministic UUID string stored as the outbox event ID.
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
     * Produces stable schema-v1 JSON without serializing the source definition.
     *
     * Keeping this allow-list serializer next to the writer makes accidental
     * leakage of raw policy JSON or audit rationale a review-visible change.
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

    /** Appends one JSON string with control characters and metacharacters escaped. */
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
