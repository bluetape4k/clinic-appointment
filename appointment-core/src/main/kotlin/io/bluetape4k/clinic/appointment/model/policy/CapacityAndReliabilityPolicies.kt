package io.bluetape4k.clinic.appointment.model.policy

/**
 * Tenant capacity and deliberate overbooking limits for one scheduling bucket.
 *
 * A bucket is defined by the downstream allocator, for example a doctor,
 * treatment room, equipment unit, or combined time slot. This policy stores
 * counts only; it does not allocate appointments.
 *
 * @property nominalCapacity Positive number of appointments normally expected
 * to fit in the bucket without overbooking.
 * @property overbookingQuota Additional appointments the clinic may deliberately
 * accept to offset expected no-shows. It is non-negative.
 * @property absoluteBookingLimit Non-disableable hard ceiling. It must be at
 * least [nominalCapacity] and at least `nominalCapacity + overbookingQuota`.
 * Clinic overrides cannot raise a compiled value beyond this ceiling.
 * @property automaticReductionEnabled Whether runtime allocation may reduce the
 * overbooking allowance from objective reliability or disruption signals. It
 * never permits increasing [absoluteBookingLimit].
 */
data class CapacityAndOverbookingPolicy(
    val nominalCapacity: Int,
    val overbookingQuota: Int,
    val absoluteBookingLimit: Int,
    val automaticReductionEnabled: Boolean,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Clinic-level capacity adjustments within a tenant hard ceiling.
 *
 * @property nominalCapacity Clinic nominal count. `Set` must remain positive
 * and cannot produce a value above the tenant [CapacityAndOverbookingPolicy.absoluteBookingLimit].
 * `Disable` is invalid.
 * @property overbookingQuota Clinic overbooking count. `Set` must be
 * non-negative and the compiled nominal-plus-quota sum must stay within the
 * tenant hard ceiling. `Disable` is invalid.
 * @property automaticReductionEnabled Optional automatic-reduction feature.
 * `Disable` is permitted and compiles to `false`.
 */
data class CapacityAndOverbookingOverride(
    val nominalCapacity: OverrideValue<Int>,
    val overbookingQuota: OverrideValue<Int>,
    val automaticReductionEnabled: OverrideValue<Boolean>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Objective reliability inputs used to rank competing scheduling proposals.
 *
 * This contract deliberately avoids stigmatizing labels. It represents
 * observable history such as no-shows and same-day cancellations. The
 * downstream optimizer decides how the resulting score affects a proposal; an
 * existing confirmed appointment is never displaced solely by this score.
 *
 * @property priorityWeights Named, non-negative weights for configured
 * objective signals. Keys are stable machine identifiers, not free-form
 * customer classifications.
 * @property noShowPenalty Non-negative score deducted for a recorded no-show.
 * @property sameDayCancellationPenalty Non-negative score deducted for a
 * customer-initiated same-day cancellation.
 * @property minimumPriorityScore Non-disableable lower bound applied after all
 * weights and penalties. It must be non-negative.
 */
data class PriorityAndReliabilityPolicy(
    val priorityWeights: Map<String, Int>,
    val noShowPenalty: Int,
    val sameDayCancellationPenalty: Int,
    val minimumPriorityScore: Int,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.PRIORITY_AND_RELIABILITY

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Clinic adjustments to reliability weights and penalties.
 *
 * @property priorityWeights Replacement signal-weight map. Every key must be
 * non-blank and every weight non-negative; `Disable` is invalid.
 * @property noShowPenalty Replacement no-show penalty, non-negative;
 * `Disable` is invalid.
 * @property sameDayCancellationPenalty Replacement same-day cancellation
 * penalty, non-negative; `Disable` is invalid.
 *
 * The tenant [PriorityAndReliabilityPolicy.minimumPriorityScore] is a safety
 * bound and therefore is intentionally not overrideable.
 */
data class PriorityAndReliabilityOverride(
    val priorityWeights: OverrideValue<Map<String, Int>>,
    val noShowPenalty: OverrideValue<Int>,
    val sameDayCancellationPenalty: OverrideValue<Int>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.PRIORITY_AND_RELIABILITY

    companion object {
        private const val serialVersionUID = 1L
    }
}
