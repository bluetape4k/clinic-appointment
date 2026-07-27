package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable
import java.time.Duration

/** How an authenticated administrator may create an appointment. */
enum class AdminBookingMode {
    /**
     * Create a confirmed appointment only when auditable customer-consent
     * evidence accompanies the administrative command.
     */
    DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
}

/** How a customer-originated booking request enters the appointment workflow. */
enum class PatientBookingMode {
    /**
     * Create a provisional request that becomes confirmed only after an
     * authorized clinic actor approves it.
     */
    PROVISIONAL_APPROVAL_REQUIRED,
}

/** Capacity reservation behavior while a customer request is provisional. */
enum class ProvisionalCapacityMode {
    /** Do not reserve staff, room, or equipment capacity before approval. */
    NO_HOLD,

    /** Express scheduling preference without excluding competing allocations. */
    SOFT_HOLD,

    /** Exclusively reserve the required capacity for a bounded short interval. */
    HARD_HOLD,
}

/** Mandatory workflow for changing an already confirmed appointment. */
enum class ConfirmedChangeMode {
    /**
     * Preserve the confirmed appointment, create a new proposal, and apply the
     * change only after the customer provides fresh consent.
     */
    NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
}

/**
 * Evidence contract required for an administrator to confirm on behalf of a customer.
 *
 * @property allowedEvidenceTypes Non-empty closed-by-configuration identifiers
 * such as `SIGNED_FORM` or `VERBAL_RECORDING`. Values identify evidence stored
 * by the external consent service; raw evidence is never embedded here.
 * @property maximumAge Maximum age of evidence at the booking decision instant.
 * It must be strictly positive; callers compare instants in UTC.
 * @property termsHashRequired Whether evidence must reference the exact hash of
 * the terms the customer accepted. This should remain `true` for mutable terms.
 */
data class ConsentEvidenceRequirement(
    val allowedEvidenceTypes: Set<String>,
    val maximumAge: Duration,
    val termsHashRequired: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Complete tenant booking-commitment policy for administrator and customer requests.
 *
 * A customer request is always provisional and requires clinic approval. An
 * administrator may confirm directly only with consent evidence. A confirmed
 * appointment can never be silently moved: it remains valid while a replacement
 * proposal awaits fresh customer consent.
 *
 * @property adminBookingMode Administrative booking contract. Schema 1 permits
 * only direct confirmation backed by consent evidence.
 * @property patientBookingMode Customer booking contract. Schema 1 always
 * creates a provisional request that requires approval.
 * @property provisionalCapacityMode Whether provisional requests reserve no,
 * soft, or hard capacity.
 * @property provisionalRequestTtl Lifetime of an unapproved request, measured
 * from creation. Valid range is inclusive `5 minutes..7 days`.
 * @property resourceHoldTtl Exclusive resource-hold lifetime. It must be `null`
 * for [ProvisionalCapacityMode.NO_HOLD] and [ProvisionalCapacityMode.SOFT_HOLD].
 * For [ProvisionalCapacityMode.HARD_HOLD] it is required, must be within
 * inclusive `1..30 minutes`, and cannot exceed [provisionalRequestTtl].
 * @property approvalRoles Non-empty set of trusted gateway roles allowed to
 * approve a customer-originated provisional request.
 * @property adminConsentEvidence Evidence freshness and integrity requirements
 * for administrator-originated direct confirmation.
 * @property confirmedChangeMode Non-disableable rule for modifying a confirmed
 * appointment. Schema 1 requires a new proposal and customer consent.
 */
data class BookingCommitmentPolicy(
    val adminBookingMode: AdminBookingMode,
    val patientBookingMode: PatientBookingMode,
    val provisionalCapacityMode: ProvisionalCapacityMode,
    val provisionalRequestTtl: Duration,
    val resourceHoldTtl: Duration?,
    val approvalRoles: Set<ActorRole>,
    val adminConsentEvidence: ConsentEvidenceRequirement,
    val confirmedChangeMode: ConfirmedChangeMode,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Clinic-specific overrides for [BookingCommitmentPolicy].
 *
 * Every property explicitly chooses inherit, set, or disable. Required booking
 * modes, approval roles, consent evidence, and confirmed-change protection
 * cannot be disabled. [resourceHoldTtlSeconds] may be disabled to represent no
 * exclusive hold, but the compiled result must remain consistent with the
 * selected provisional-capacity mode.
 *
 * @property adminBookingMode Clinic instruction for the administrator-origin
 * workflow; `Disable` is invalid.
 * @property patientBookingMode Clinic instruction for the customer-origin
 * workflow; `Disable` is invalid.
 * @property provisionalCapacityMode Clinic hold strategy; use an explicit
 * [ProvisionalCapacityMode.NO_HOLD] instead of `Disable`.
 * @property provisionalRequestTtlSeconds Request lifetime in whole seconds.
 * A set value must compile to inclusive `300..604800`.
 * @property resourceHoldTtlSeconds Exclusive hold lifetime in whole seconds.
 * A set value must compile to inclusive `60..1800`; `Disable` compiles to
 * `null` only when the resulting mode is not `HARD_HOLD`.
 * @property approvalRoles Non-empty approver-role set; `Disable` is invalid.
 * @property adminConsentEvidence Consent-evidence contract; `Disable` is invalid.
 * @property confirmedChangeMode Non-disableable confirmed-change protection.
 */
data class BookingCommitmentOverride(
    val adminBookingMode: OverrideValue<AdminBookingMode>,
    val patientBookingMode: OverrideValue<PatientBookingMode>,
    val provisionalCapacityMode: OverrideValue<ProvisionalCapacityMode>,
    val provisionalRequestTtlSeconds: OverrideValue<Long>,
    val resourceHoldTtlSeconds: OverrideValue<Long>,
    val approvalRoles: OverrideValue<Set<ActorRole>>,
    val adminConsentEvidence: OverrideValue<ConsentEvidenceRequirement>,
    val confirmedChangeMode: OverrideValue<ConfirmedChangeMode>,
) : SchedulingPolicyPayload {
    override val kind: SchedulingPolicyKind = SchedulingPolicyKind.BOOKING_COMMITMENT

    companion object {
        private const val serialVersionUID = 1L
    }
}
