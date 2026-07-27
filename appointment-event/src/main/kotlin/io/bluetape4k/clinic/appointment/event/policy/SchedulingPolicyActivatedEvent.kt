package io.bluetape4k.clinic.appointment.event.policy

import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import java.io.Serializable
import java.time.Instant

/**
 * Redacted integration contract emitted after one policy definition activates.
 *
 * The event describes policy identity and freshness, not the policy document
 * itself. Consumers retrieve or compile policy state through their designated
 * boundary and use [payloadHash] to verify that they observed the same immutable
 * definition. This keeps raw operational rules, change rationale, credentials,
 * idempotency keys, and patient data out of the outbox.
 *
 * @property eventId Deterministic UUID derived from definition identity,
 * version, effective start, and both committed generations. Retries of the same
 * completed activation therefore address the same event.
 * @property definitionId Positive immutable policy-definition database ID.
 * @property policyKind Closed policy area whose active definition changed.
 * @property policyVersion Positive immutable publication version within the
 * tenant/clinic scope and [policyKind].
 * @property policyScope Organizational boundary of the activated definition.
 * `TENANT_DEFAULT` requires a `null` outbox clinic ID; `CLINIC_OVERRIDE`
 * requires a positive clinic ID in the outbox envelope.
 * @property effectiveFrom Inclusive UTC instant at which the definition became
 * eligible for effective-policy compilation.
 * @property effectiveUntil Exclusive UTC eligibility boundary, or `null` for
 * an open-ended definition.
 * @property generation Committed tenant and clinic freshness counters. Tenant
 * generation is positive; clinic generation may be `0` when no clinic override
 * generation exists.
 * @property payloadHash Lowercase 64-character SHA-256 of canonical policy
 * payload JSON. The canonical payload itself is deliberately excluded.
 * @property actor Minimal trusted Gateway actor reference that authorized or
 * executed activation. It contains no display name, token, or mutable claims.
 * @property correlationId Bounded request/workflow trace ID. It is not an
 * upstream causation event and must not be used as one.
 * @property schemaVersion Positive wire-schema version of this redacted event.
 */
data class SchedulingPolicyActivatedEvent(
    val eventId: String,
    val definitionId: Long,
    val policyKind: SchedulingPolicyKind,
    val policyVersion: Long,
    val policyScope: PolicyScope,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val generation: PolicyGenerationVector,
    val payloadHash: String,
    val actor: ActorAuditRef,
    val correlationId: String,
    val schemaVersion: Int = 1,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}
