package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentOverride
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingOverride
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingPolicy
import io.bluetape4k.clinic.appointment.model.policy.ConfirmedChangeMode
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryOverride
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryPolicy
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentOverride
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentPolicy
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaOverride
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaPolicy
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionOverride
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityOverride
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityPolicy
import io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationOverride
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyDefinition
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyPayload
import java.time.Duration

/**
 * 모든 trust boundary에서 schema-one scheduling-policy invariant를 강제합니다.
 *
 * strict JSON decoding 이후, canonical hashing 이전, persistence 이전, tenant/clinic
 * compilation 이후에 이 validator를 호출합니다. data class는 의도적으로 copy 가능하게
 * 남겨 두므로, 중앙 validation이 `copy()` 또는 향후 persistence adapter가 invariant
 * 우회 경로가 되는 것을 막습니다.
 */
object SchedulingPolicyValidator {
    private val minimumRequestTtl: Duration = Duration.ofMinutes(5)
    private val maximumRequestTtl: Duration = Duration.ofDays(7)
    private val minimumResourceHoldTtl: Duration = Duration.ofMinutes(1)
    private val maximumResourceHoldTtl: Duration = Duration.ofMinutes(30)
    private val heldReevaluationTargetSeconds = 60L..900L
    private val proposedReevaluationTargetSeconds = 300L..7_200L
    private val sha256 = Regex("[0-9a-f]{64}")

    /**
     * 완전한 definition envelope와 payload를 validate합니다.
     *
     * 검사 범위는 tenant/clinic identity coupling, 양수 version/revision, schema 지원,
     * effective interval, canonical SHA-256 syntax, envelope/payload kind equality,
     * audit reason bounds, payload별 safety rule입니다.
     *
     * @return 같은 [definition] instance입니다. 호출자는 validated expression으로 이어서 사용할 수 있습니다.
     * @throws IllegalArgumentException caller가 제공한 계약 중 하나라도 유효하지 않을 때 발생합니다.
     */
    fun validate(definition: SchedulingPolicyDefinition): SchedulingPolicyDefinition {
        require(definition.tenantGroupId > 0L) { "tenantGroupId must be positive" }
        when (definition.scope) {
            PolicyScope.TENANT_DEFAULT ->
                require(definition.clinicId == null) {
                    "tenant default policy must not identify a clinic"
                }
            PolicyScope.CLINIC_OVERRIDE ->
                require(definition.clinicId != null && definition.clinicId > 0L) {
                    "clinic override policy requires a positive clinicId"
                }
        }
        require(definition.version >= 1L) { "version must be at least one" }
        require(definition.revision >= 1L) { "revision must be at least one" }
        require(definition.schemaVersion == 1) { "unsupported schemaVersion(${definition.schemaVersion})" }
        require(definition.changeReason.isNotBlank()) { "changeReason must not be blank" }
        require(definition.changeReason.length <= 1_000) { "changeReason must not exceed 1000 characters" }
        require(definition.effectiveUntil == null || definition.effectiveUntil > definition.effectiveFrom) {
            "effectiveUntil must be after effectiveFrom"
        }
        require(sha256.matches(definition.payloadHash)) { "payloadHash must be a lowercase SHA-256 value" }
        require(definition.kind == definition.payload.kind) {
            "policy kind(${definition.kind}) does not match payload kind(${definition.payload.kind})"
        }
        validatePayload(definition.payload, definition.scope)
        return definition
    }

    /**
     * typed payload 하나를 선언된 organizational scope에 대해 validate합니다.
     *
     * complete policy는 tenant scope에서만 유효합니다. override payload는 clinic scope에서만
     * 유효합니다. 필수 field와 safety field는
     * [io.bluetape4k.clinic.appointment.model.policy.OverrideValue.Disable]을 거부합니다.
     * `Set` 값은 각 속성 KDoc에 적힌 단위와 범위를 만족해야 합니다.
     *
     * @return 같은 [payload] instance입니다.
     * @throws IllegalArgumentException scope, override mode, range, cross-field
     * invariant를 위반할 때 발생합니다.
     */
    fun validatePayload(
        payload: SchedulingPolicyPayload,
        scope: PolicyScope,
    ): SchedulingPolicyPayload {
        when (payload) {
            is BookingCommitmentPolicy -> {
                requireTenantDefault(scope)
                validateBooking(payload)
            }
            is BookingCommitmentOverride -> {
                requireClinicOverride(scope)
                validateBookingOverride(payload)
            }
            is CapacityAndOverbookingPolicy -> {
                requireTenantDefault(scope)
                validateCapacity(payload)
            }
            is CapacityAndOverbookingOverride -> {
                requireClinicOverride(scope)
                validateCapacityOverride(payload)
            }
            is HoldAndConsentPolicy -> {
                requireTenantDefault(scope)
                require(payload.maximumConsentAgeSeconds > 0L)
            }
            is HoldAndConsentOverride -> {
                requireClinicOverride(scope)
                payload.maximumConsentAgeSeconds.requireNotDisabled("maximumConsentAgeSeconds")
                payload.maximumConsentAgeSeconds.setValueOrNull()?.let {
                    require(it > 0L) { "maximumConsentAgeSeconds must be positive" }
                }
            }
            is PriorityAndReliabilityPolicy -> {
                requireTenantDefault(scope)
                validatePriorityWeights(payload.priorityWeights)
                require(payload.minimumPriorityScore >= 0)
                require(payload.noShowPenalty >= 0)
                require(payload.sameDayCancellationPenalty >= 0)
            }
            is PriorityAndReliabilityOverride -> {
                requireClinicOverride(scope)
                payload.priorityWeights.requireNotDisabled("priorityWeights")
                payload.noShowPenalty.requireNotDisabled("noShowPenalty")
                payload.sameDayCancellationPenalty.requireNotDisabled("sameDayCancellationPenalty")
                payload.priorityWeights.setValueOrNull()?.let(::validatePriorityWeights)
                payload.noShowPenalty.setValueOrNull()?.let {
                    require(it >= 0) { "noShowPenalty must not be negative" }
                }
                payload.sameDayCancellationPenalty.setValueOrNull()?.let {
                    require(it >= 0) { "sameDayCancellationPenalty must not be negative" }
                }
            }
            is ReconfirmationPolicy -> {
                requireTenantDefault(scope)
                require(payload.leadTimeSeconds > 0L)
                require(payload.maximumAttempts > 0)
            }
            is ReconfirmationOverride -> {
                requireClinicOverride(scope)
                payload.leadTimeSeconds.requireNotDisabled("leadTimeSeconds")
                payload.maximumAttempts.requireNotDisabled("maximumAttempts")
                payload.leadTimeSeconds.setValueOrNull()?.let {
                    require(it > 0L) { "leadTimeSeconds must be positive" }
                }
                payload.maximumAttempts.setValueOrNull()?.let {
                    require(it > 0) { "maximumAttempts must be positive" }
                }
            }
            is DisruptionRecoveryPolicy -> {
                requireTenantDefault(scope)
                require(payload.maximumProposalDelaySeconds > 0L)
                require(payload.preserveConfirmedAppointment) {
                    "disruption policy must preserve the existing confirmed appointment"
                }
            }
            is DisruptionRecoveryOverride -> {
                requireClinicOverride(scope)
                payload.maximumProposalDelaySeconds.requireNotDisabled("maximumProposalDelaySeconds")
                payload.maximumProposalDelaySeconds.setValueOrNull()?.let {
                    require(it > 0L) { "maximumProposalDelaySeconds must be positive" }
                }
            }
            is OperatingExtensionPolicy -> {
                requireTenantDefault(scope)
                require(payload.maximumExtensionMinutes >= 0)
                require(payload.legalSafetyCeilingMinutes >= 0)
                require(payload.maximumExtensionMinutes <= payload.legalSafetyCeilingMinutes) {
                    "maximum extension must not exceed the legal safety ceiling"
                }
            }
            is OperatingExtensionOverride -> {
                requireClinicOverride(scope)
                payload.maximumExtensionMinutes.requireNotDisabled("maximumExtensionMinutes")
                payload.maximumExtensionMinutes.setValueOrNull()?.let {
                    require(it >= 0) { "maximumExtensionMinutes must not be negative" }
                }
            }
            is NotificationAndSlaPolicy -> {
                requireTenantDefault(scope)
                require(payload.notificationChannels.isNotEmpty())
                require(payload.disruptionNoticeSeconds > 0L)
                require(payload.mandatoryResponseSeconds > 0L)
                payload.profileReevaluationHeldTargetSeconds?.let {
                    require(it in heldReevaluationTargetSeconds) {
                        "profileReevaluationHeldTargetSeconds must be between 60 and 900 seconds"
                    }
                }
                payload.profileReevaluationProposedTargetSeconds?.let {
                    require(it in proposedReevaluationTargetSeconds) {
                        "profileReevaluationProposedTargetSeconds must be between 300 and 7200 seconds"
                    }
                }
            }
            is NotificationAndSlaOverride -> {
                requireClinicOverride(scope)
                payload.notificationChannels.requireNotDisabled("notificationChannels")
                payload.disruptionNoticeSeconds.requireNotDisabled("disruptionNoticeSeconds")
                payload.profileReevaluationHeldTargetSeconds
                    .requireNotDisabled("profileReevaluationHeldTargetSeconds")
                payload.profileReevaluationProposedTargetSeconds
                    .requireNotDisabled("profileReevaluationProposedTargetSeconds")
                payload.notificationChannels.setValueOrNull()?.let {
                    require(it.isNotEmpty()) { "notificationChannels must not be empty" }
                }
                payload.disruptionNoticeSeconds.setValueOrNull()?.let {
                    require(it > 0L) { "disruptionNoticeSeconds must be positive" }
                }
                payload.profileReevaluationHeldTargetSeconds.setValueOrNull()?.let {
                    require(it in heldReevaluationTargetSeconds) {
                        "profileReevaluationHeldTargetSeconds must be between 60 and 900 seconds"
                    }
                }
                payload.profileReevaluationProposedTargetSeconds.setValueOrNull()?.let {
                    require(it in proposedReevaluationTargetSeconds) {
                        "profileReevaluationProposedTargetSeconds must be between 300 and 7200 seconds"
                    }
                }
            }
        }
        return payload
    }

    private fun validateBooking(policy: BookingCommitmentPolicy) {
        require(policy.provisionalRequestTtl in minimumRequestTtl..maximumRequestTtl) {
            "provisionalRequestTtl must be between 5 minutes and 7 days"
        }
        when (policy.provisionalCapacityMode) {
            ProvisionalCapacityMode.NO_HOLD,
            ProvisionalCapacityMode.SOFT_HOLD,
            -> require(policy.resourceHoldTtl == null) {
                "${policy.provisionalCapacityMode} forbids resourceHoldTtl"
            }
            ProvisionalCapacityMode.HARD_HOLD -> {
                val holdTtl = requireNotNull(policy.resourceHoldTtl) {
                    "HARD_HOLD requires resourceHoldTtl"
                }
                require(holdTtl in minimumResourceHoldTtl..maximumResourceHoldTtl) {
                    "resourceHoldTtl must be between 1 and 30 minutes"
                }
                require(holdTtl <= policy.provisionalRequestTtl) {
                    "resourceHoldTtl must not exceed provisionalRequestTtl"
                }
            }
        }
        require(policy.approvalRoles.isNotEmpty()) { "approvalRoles must not be empty" }
        require(policy.adminConsentEvidence.allowedEvidenceTypes.isNotEmpty()) {
            "consent evidence types must not be empty"
        }
        require(!policy.adminConsentEvidence.maximumAge.isNegative &&
                !policy.adminConsentEvidence.maximumAge.isZero) {
            "consent evidence maximumAge must be positive"
        }
        require(policy.confirmedChangeMode == ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT) {
            "confirmed changes require a new proposal and customer consent"
        }
    }

    private fun validateCapacity(policy: CapacityAndOverbookingPolicy) {
        require(policy.nominalCapacity > 0) { "nominalCapacity must be positive" }
        require(policy.overbookingQuota >= 0) { "overbookingQuota must not be negative" }
        require(policy.absoluteBookingLimit >= policy.nominalCapacity) {
            "absoluteBookingLimit must not be below nominalCapacity"
        }
        require(policy.nominalCapacity + policy.overbookingQuota <= policy.absoluteBookingLimit) {
            "nominal capacity and overbooking quota exceed the absolute booking limit"
        }
    }

    private fun validateBookingOverride(policy: BookingCommitmentOverride) {
        policy.adminBookingMode.requireNotDisabled("adminBookingMode")
        policy.patientBookingMode.requireNotDisabled("patientBookingMode")
        policy.provisionalCapacityMode.requireNotDisabled("provisionalCapacityMode")
        policy.provisionalRequestTtlSeconds.requireNotDisabled("provisionalRequestTtlSeconds")
        policy.approvalRoles.requireNotDisabled("approvalRoles")
        policy.adminConsentEvidence.requireNotDisabled("adminConsentEvidence")
        policy.confirmedChangeMode.requireNotDisabled("confirmedChangeMode")
        policy.provisionalRequestTtlSeconds.setValueOrNull()?.let {
            require(Duration.ofSeconds(it) in minimumRequestTtl..maximumRequestTtl) {
                "provisionalRequestTtlSeconds must be between 300 and 604800"
            }
        }
        policy.resourceHoldTtlSeconds.setValueOrNull()?.let {
            require(Duration.ofSeconds(it) in minimumResourceHoldTtl..maximumResourceHoldTtl) {
                "resourceHoldTtlSeconds must be between 60 and 1800"
            }
        }
        policy.approvalRoles.setValueOrNull()?.let {
            require(it.isNotEmpty()) { "approvalRoles must not be empty" }
        }
        policy.adminConsentEvidence.setValueOrNull()?.let {
            require(it.allowedEvidenceTypes.isNotEmpty()) {
                "consent evidence types must not be empty"
            }
            require(!it.maximumAge.isNegative && !it.maximumAge.isZero) {
                "consent evidence maximumAge must be positive"
            }
        }
        policy.confirmedChangeMode.setValueOrNull()?.let {
            require(it == ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT) {
                "confirmed changes require a new proposal and customer consent"
            }
        }
    }

    private fun validateCapacityOverride(policy: CapacityAndOverbookingOverride) {
        policy.nominalCapacity.requireNotDisabled("nominalCapacity")
        policy.overbookingQuota.requireNotDisabled("overbookingQuota")
        policy.nominalCapacity.setValueOrNull()?.let {
            require(it > 0) { "nominalCapacity must be positive" }
        }
        policy.overbookingQuota.setValueOrNull()?.let {
            require(it >= 0) { "overbookingQuota must not be negative" }
        }
    }

    private fun validatePriorityWeights(weights: Map<String, Int>) {
        require(weights.keys.all(String::isNotBlank)) { "priority weight names must not be blank" }
        require(weights.values.all { it >= 0 }) { "priority weights must not be negative" }
    }

    private fun io.bluetape4k.clinic.appointment.model.policy.OverrideValue<*>.requireNotDisabled(
        fieldName: String,
    ) {
        require(this != io.bluetape4k.clinic.appointment.model.policy.OverrideValue.Disable) {
            "$fieldName cannot be disabled"
        }
    }

    private fun <T> io.bluetape4k.clinic.appointment.model.policy.OverrideValue<T>.setValueOrNull(): T? =
        when (this) {
            io.bluetape4k.clinic.appointment.model.policy.OverrideValue.Inherit,
            io.bluetape4k.clinic.appointment.model.policy.OverrideValue.Disable,
            -> null
            is io.bluetape4k.clinic.appointment.model.policy.OverrideValue.Set -> value
        }

    private fun requireTenantDefault(scope: PolicyScope) {
        require(scope == PolicyScope.TENANT_DEFAULT) { "tenant payload requires tenant-default scope" }
    }

    private fun requireClinicOverride(scope: PolicyScope) {
        require(scope == PolicyScope.CLINIC_OVERRIDE) { "override payload requires clinic scope" }
    }
}
