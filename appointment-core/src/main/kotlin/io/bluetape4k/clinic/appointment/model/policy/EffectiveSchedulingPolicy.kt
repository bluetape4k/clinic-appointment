package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Instant

/**
 * Generation counters used to detect concurrent policy changes during compilation.
 *
 * @property tenantGeneration Positive monotonic generation of the tenant policy
 * head. It changes whenever an active tenant definition changes.
 * @property clinicGeneration Monotonic generation of the clinic override head.
 * It starts at `0` when no clinic override has ever been activated and changes
 * whenever an active clinic override changes.
 */
data class PolicyGenerationVector(
    val tenantGeneration: Long,
    val clinicGeneration: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Exact source versions that contributed one policy kind to a snapshot.
 *
 * @property tenantVersion Positive active tenant version.
 * @property clinicVersion Positive active clinic-override version, or `null`
 * when the clinic inherits the tenant policy for this kind.
 */
data class SourceVersion(
    val tenantVersion: Long,
    val clinicVersion: Long?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Organizational source that supplied one compiled leaf value. */
enum class PolicyValueSource {
    /** Immutable platform safety/default value used because no tenant value replaced it. */
    PLATFORM,

    /** Active tenant baseline value. */
    TENANT,

    /** Active clinic override value that passed all non-relaxation checks. */
    CLINIC,
}

/**
 * Fully resolved policy values consumed by downstream scheduling decisions.
 *
 * Each nullable property corresponds to one independently activated policy
 * kind. A `null` kind has no compiled contract and must be handled as
 * unavailable, not as an implicit zero or permissive default.
 *
 * @property bookingCommitment Resolved booking-origin, approval, hold, and
 * confirmed-change contract.
 * @property holdAndConsent Resolved hold-time consent requirement.
 * @property capacityAndOverbooking Resolved capacity counts and hard ceiling.
 * @property priorityAndReliability Resolved objective reliability scoring inputs.
 * @property reconfirmation Resolved reconfirmation schedule and retry ceiling.
 * @property disruptionRecovery Resolved disruption proposal behavior.
 * @property operatingExtension Resolved overtime and safety ceiling.
 * @property notificationAndSla Resolved channels and mandatory response bounds.
 */
data class CompiledSchedulingPolicy(
    val bookingCommitment: BookingCommitmentPolicy? = null,
    val holdAndConsent: HoldAndConsentPolicy? = null,
    val capacityAndOverbooking: CapacityAndOverbookingPolicy? = null,
    val priorityAndReliability: PriorityAndReliabilityPolicy? = null,
    val reconfirmation: ReconfirmationPolicy? = null,
    val disruptionRecovery: DisruptionRecoveryPolicy? = null,
    val operatingExtension: OperatingExtensionPolicy? = null,
    val notificationAndSla: NotificationAndSlaPolicy? = null,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Typed clinic overrides supplied to full effective-policy compilation.
 *
 * A `null` property means the clinic has no active override definition for that
 * policy kind. Non-null values still contain per-field [OverrideValue] states,
 * so absence of a definition is distinct from inheriting each field.
 *
 * @property bookingCommitment Active clinic booking override, if any.
 * @property holdAndConsent Active clinic hold/consent override, if any.
 * @property capacityAndOverbooking Active clinic capacity override, if any.
 * @property priorityAndReliability Active clinic reliability override, if any.
 * @property reconfirmation Active clinic reconfirmation override, if any.
 * @property disruptionRecovery Active clinic disruption-recovery override, if any.
 * @property operatingExtension Active clinic operating-extension override, if any.
 * @property notificationAndSla Active clinic notification/SLA override, if any.
 */
data class ClinicSchedulingPolicyOverrides(
    val bookingCommitment: BookingCommitmentOverride? = null,
    val holdAndConsent: HoldAndConsentOverride? = null,
    val capacityAndOverbooking: CapacityAndOverbookingOverride? = null,
    val priorityAndReliability: PriorityAndReliabilityOverride? = null,
    val reconfirmation: ReconfirmationOverride? = null,
    val disruptionRecovery: DisruptionRecoveryOverride? = null,
    val operatingExtension: OperatingExtensionOverride? = null,
    val notificationAndSla: NotificationAndSlaOverride? = null,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Immutable, reproducible scheduling-policy snapshot for one clinic and decision.
 *
 * A snapshot is evaluated twice in time: [decisionAt] selects definitions that
 * govern the present command, while [serviceAt] allows future-effective rules
 * to be considered for the appointment date. The generation vector and source
 * versions make stale compilation detectable and the canonical hash makes the
 * result reproducible.
 *
 * @property id Stable snapshot identity. Schema 1 uses the same lowercase
 * SHA-256 value as [snapshotHash].
 * @property tenantGroupId Positive tenant boundary.
 * @property clinicId Positive clinic for which the policy was compiled.
 * @property decisionAt UTC instant at which the command evaluates policy.
 * @property serviceAt UTC instant of the planned service; it may select a
 * scheduled future policy version.
 * @property generation Tenant/clinic generations observed by the compiler.
 * Persistence must verify them again before publishing this snapshot.
 * @property sourceVersions Version pair per included policy kind.
 * @property sourceByPath Source of every compiled leaf path. Absence is not
 * equivalent to platform inheritance and indicates an incomplete compiler.
 * @property disabledFeatures Canonically sorted semantic paths explicitly
 * disabled by a valid clinic override.
 * @property warnings Ordered, customer-safe diagnostic codes or messages
 * produced during compilation. Order is semantically significant in the hash.
 * @property payload Fully resolved policy values.
 * @property snapshotHash Lowercase SHA-256 over schema version, tenant/clinic,
 * evaluation instants, generations, source metadata, disabled paths, warnings,
 * and the compiled payload.
 */
data class EffectiveSchedulingPolicy(
    val id: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val generation: PolicyGenerationVector,
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val sourceByPath: Map<String, PolicyValueSource>,
    val disabledFeatures: Set<String>,
    val warnings: List<String>,
    val payload: CompiledSchedulingPolicy,
    val snapshotHash: String,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}
