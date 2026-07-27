package io.bluetape4k.clinic.appointment.model.policy

/**
 * Consent-evidence rules applied while appointment capacity is held.
 *
 * @property consentEvidenceRequired Whether a held request must carry consent
 * evidence before it can be confirmed.
 * @property maximumConsentAgeSeconds Maximum evidence age at confirmation in
 * whole seconds. It is mandatory and strictly positive even when evidence is
 * currently optional, so enabling the feature cannot expose an undefined bound.
 */
data class HoldAndConsentPolicy(
    val consentEvidenceRequired: Boolean,
    val maximumConsentAgeSeconds: Long,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.HOLD_AND_CONSENT
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Clinic adjustments to hold-time consent requirements.
 *
 * @property consentEvidenceRequired Clinic requirement. `Disable` may turn the
 * optional feature off only when a stricter platform rule does not require it.
 * @property maximumConsentAgeSeconds Positive evidence-age bound in whole
 * seconds. `Disable` is invalid because an enabled requirement needs a bound.
 */
data class HoldAndConsentOverride(
    val consentEvidenceRequired: OverrideValue<Boolean>,
    val maximumConsentAgeSeconds: OverrideValue<Long>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.HOLD_AND_CONSENT
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Rules for asking a customer to reconfirm before service.
 *
 * @property required Whether the visit requires reconfirmation.
 * @property leadTimeSeconds Whole seconds before the service instant when the
 * first reconfirmation attempt becomes due; it must be strictly positive.
 * @property maximumAttempts Positive non-disableable attempt ceiling. A clinic
 * override may lower, but never raise, the tenant/platform ceiling.
 */
data class ReconfirmationPolicy(
    val required: Boolean,
    val leadTimeSeconds: Long,
    val maximumAttempts: Int,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.RECONFIRMATION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Clinic-level reconfirmation adjustments.
 *
 * @property required Clinic instruction for enabling reconfirmation. `Disable`
 * compiles to `false` only if no stricter platform rule forbids disabling it.
 * @property leadTimeSeconds Positive lead time in whole seconds; `Disable` is invalid.
 * @property maximumAttempts Positive retry ceiling. `Disable` is invalid and a
 * set value cannot exceed the inherited ceiling.
 */
data class ReconfirmationOverride(
    val required: OverrideValue<Boolean>,
    val leadTimeSeconds: OverrideValue<Long>,
    val maximumAttempts: OverrideValue<Int>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.RECONFIRMATION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Recovery behavior after an operational disruption.
 *
 * Disruptions include public-holiday schedule changes, clinician absence,
 * equipment failure, or inability to complete all treatment items in one
 * appointment. Recovery creates a proposal; it does not silently overwrite an
 * existing confirmed appointment.
 *
 * @property automaticProposalEnabled Whether the system may calculate and send
 * a replacement proposal automatically.
 * @property maximumProposalDelaySeconds Maximum elapsed time from disruption
 * detection to creating a proposal, in whole seconds; strictly positive.
 * @property preserveConfirmedAppointment Non-disableable invariant requiring
 * the current confirmed appointment to remain effective until the customer
 * accepts a replacement or another explicit cancellation occurs.
 */
data class DisruptionRecoveryPolicy(
    val automaticProposalEnabled: Boolean,
    val maximumProposalDelaySeconds: Long,
    val preserveConfirmedAppointment: Boolean,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.DISRUPTION_RECOVERY
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Clinic adjustments to disruption proposal behavior.
 *
 * @property automaticProposalEnabled Optional automatic-proposal feature.
 * `Disable` compiles to `false`.
 * @property maximumProposalDelaySeconds Positive response bound in whole
 * seconds. `Disable` is invalid.
 *
 * [DisruptionRecoveryPolicy.preserveConfirmedAppointment] is intentionally
 * absent because a clinic can never override that customer-consent boundary.
 */
data class DisruptionRecoveryOverride(
    val automaticProposalEnabled: OverrideValue<Boolean>,
    val maximumProposalDelaySeconds: OverrideValue<Long>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.DISRUPTION_RECOVERY
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Policy for extending clinical work beyond the normal operating schedule.
 *
 * @property extensionEnabled Whether a clinic may schedule controlled overtime.
 * @property maximumExtensionMinutes Maximum overtime the current tenant policy
 * permits, in whole minutes; non-negative.
 * @property legalSafetyCeilingMinutes Non-disableable legal, labor, or clinical
 * safety ceiling in whole minutes; non-negative. [maximumExtensionMinutes]
 * cannot exceed this value.
 */
data class OperatingExtensionPolicy(
    val extensionEnabled: Boolean,
    val maximumExtensionMinutes: Int,
    val legalSafetyCeilingMinutes: Int,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.OPERATING_EXTENSION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Clinic-level operating-extension adjustment.
 *
 * @property extensionEnabled Optional overtime feature; `Disable` compiles to
 * `false`.
 * @property maximumExtensionMinutes Non-negative whole-minute limit. `Disable`
 * is invalid and a set value cannot exceed the inherited maximum or legal
 * safety ceiling.
 */
data class OperatingExtensionOverride(
    val extensionEnabled: OverrideValue<Boolean>,
    val maximumExtensionMinutes: OverrideValue<Int>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.OPERATING_EXTENSION
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Customer notification and operational response-level policy.
 *
 * @property notificationChannels Non-empty set of stable channel identifiers
 * such as `SMS`, `PUSH`, or `EMAIL`; it stores routing choices, not addresses.
 * @property disruptionNoticeSeconds Strictly positive maximum elapsed seconds
 * from disruption detection to issuing a customer notice.
 * @property mandatoryResponseSeconds Strictly positive, non-disableable maximum
 * response time for the clinic's required operational action.
 */
data class NotificationAndSlaPolicy(
    val notificationChannels: Set<String>,
    val disruptionNoticeSeconds: Long,
    val mandatoryResponseSeconds: Long,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.NOTIFICATION_AND_SLA
    companion object { private const val serialVersionUID = 1L }
}

/**
 * Clinic-level notification adjustments.
 *
 * @property notificationChannels Replacement non-empty channel set.
 * `Disable` is invalid because affected customers must remain reachable.
 * @property disruptionNoticeSeconds Positive notice bound in whole seconds.
 * `Disable` is invalid.
 *
 * [NotificationAndSlaPolicy.mandatoryResponseSeconds] is intentionally absent
 * because a clinic cannot relax or disable the tenant/platform SLA.
 */
data class NotificationAndSlaOverride(
    val notificationChannels: OverrideValue<Set<String>>,
    val disruptionNoticeSeconds: OverrideValue<Long>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.NOTIFICATION_AND_SLA
    companion object { private const val serialVersionUID = 1L }
}
