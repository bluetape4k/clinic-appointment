package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentOverride
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingOverride
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingPolicy
import io.bluetape4k.clinic.appointment.model.policy.ConfirmedChangeMode
import io.bluetape4k.clinic.appointment.model.policy.ConsentEvidenceRequirement
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryOverride
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryPolicy
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentOverride
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentPolicy
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaOverride
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaPolicy
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionOverride
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionPolicy
import io.bluetape4k.clinic.appointment.model.policy.OverrideValue
import io.bluetape4k.clinic.appointment.model.policy.PatientBookingMode
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityOverride
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityPolicy
import io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationOverride
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyPayload
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Strict closed-world JSON decoder for persisted scheduling-policy payloads.
 *
 * Dispatch is selected only from the trusted envelope tuple `(kind, scope,
 * schemaVersion)`. JSON cannot supply a class name or polymorphic discriminator.
 * Unknown fields, malformed override states, unsupported schema versions, and
 * payloads above [MAX_PAYLOAD_BYTES] are rejected before domain compilation.
 *
 * Clinic override values use an explicit wire shape:
 *
 * ```json
 * { "mode": "SET", "value": 8 }
 * ```
 *
 * `INHERIT` and `DISABLE` must omit `value`; `SET` must provide it.
 *
 * @param objectMapper Mapper used only with the closed set of concrete wire
 * classes selected by the trusted envelope. An injected mapper must retain the
 * Kotlin module, `FAIL_ON_UNKNOWN_PROPERTIES`, and disabled default typing; it
 * must not enable polymorphic class-name dispatch. Violating those conditions
 * weakens the closed-world decoding and unknown-field rejection guarantees.
 */
class SchedulingPolicyPayloadCodec(
    private val objectMapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build(),
) {
    companion object {
        /** Maximum accepted UTF-8 JSON size: 256 KiB. */
        const val MAX_PAYLOAD_BYTES: Int = 256 * 1024
    }

    /**
     * Decodes and validates one schema-versioned policy payload.
     *
     * @param kind Trusted policy kind from the surrounding definition envelope.
     * @param scope Trusted tenant/clinic scope from the definition envelope.
     * @param schemaVersion Wire schema version. This implementation accepts `1`.
     * @param json Raw UTF-8 JSON text. It must not exceed [MAX_PAYLOAD_BYTES].
     * @return Typed tenant policy or clinic override whose kind and scope match
     * the dispatch tuple.
     * @throws IllegalArgumentException if the tuple is unsupported, JSON is
     * oversized or malformed, an unknown field is present, an override state is
     * inconsistent, or a business invariant fails.
     */
    fun decode(
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
        schemaVersion: Int,
        json: String,
    ): SchedulingPolicyPayload {
        require(schemaVersion == 1) { "unsupported schemaVersion($schemaVersion)" }
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "policy payload must not exceed $MAX_PAYLOAD_BYTES UTF-8 bytes"
        }
        val decoded = try {
            when (kind to scope) {
                SchedulingPolicyKind.BOOKING_COMMITMENT to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, BookingCommitmentWire::class.java).toPolicy()
                SchedulingPolicyKind.BOOKING_COMMITMENT to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, BookingCommitmentOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.HOLD_AND_CONSENT to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, HoldAndConsentPolicy::class.java)
                SchedulingPolicyKind.HOLD_AND_CONSENT to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, HoldAndConsentOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, CapacityAndOverbookingPolicy::class.java)
                SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, CapacityAndOverbookingOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.PRIORITY_AND_RELIABILITY to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, PriorityAndReliabilityPolicy::class.java)
                SchedulingPolicyKind.PRIORITY_AND_RELIABILITY to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, PriorityAndReliabilityOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.RECONFIRMATION to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, ReconfirmationPolicy::class.java)
                SchedulingPolicyKind.RECONFIRMATION to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, ReconfirmationOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.DISRUPTION_RECOVERY to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, DisruptionRecoveryPolicy::class.java)
                SchedulingPolicyKind.DISRUPTION_RECOVERY to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, DisruptionRecoveryOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.OPERATING_EXTENSION to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, OperatingExtensionPolicy::class.java)
                SchedulingPolicyKind.OPERATING_EXTENSION to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, OperatingExtensionOverrideWire::class.java).toPolicy()
                SchedulingPolicyKind.NOTIFICATION_AND_SLA to PolicyScope.TENANT_DEFAULT ->
                    objectMapper.readValue(json, NotificationAndSlaPolicy::class.java)
                SchedulingPolicyKind.NOTIFICATION_AND_SLA to PolicyScope.CLINIC_OVERRIDE ->
                    objectMapper.readValue(json, NotificationAndSlaOverrideWire::class.java).toPolicy()
                else -> error("unsupported policy kind and scope combination: $kind/$scope")
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("invalid $kind schema-one payload", error)
        }
        return SchedulingPolicyValidator.validatePayload(decoded, scope)
    }

    private data class BookingCommitmentWire(
        val adminBookingMode: AdminBookingMode,
        val patientBookingMode: PatientBookingMode,
        val provisionalCapacityMode: ProvisionalCapacityMode,
        val provisionalRequestTtlSeconds: Long,
        val resourceHoldTtlSeconds: Long?,
        val approvalRoles: Set<ActorRole>,
        val adminConsentEvidence: ConsentEvidenceWire,
        val confirmedChangeMode: ConfirmedChangeMode,
    )

    private data class ConsentEvidenceWire(
        val allowedEvidenceTypes: Set<String>,
        val maximumAgeSeconds: Long,
        val termsHashRequired: Boolean,
    )

    private enum class OverrideMode {
        INHERIT,
        SET,
        DISABLE,
    }

    private data class OverrideWire<T>(
        val mode: OverrideMode,
        val value: T? = null,
    ) {
        fun toDomain(fieldName: String): OverrideValue<T> = when (mode) {
            OverrideMode.INHERIT -> {
                require(value == null) { "$fieldName INHERIT must not contain value" }
                OverrideValue.Inherit
            }
            OverrideMode.SET -> OverrideValue.Set(
                requireNotNull(value) { "$fieldName SET requires value" },
            )
            OverrideMode.DISABLE -> {
                require(value == null) { "$fieldName DISABLE must not contain value" }
                OverrideValue.Disable
            }
        }
    }

    private data class BookingCommitmentOverrideWire(
        val adminBookingMode: OverrideWire<AdminBookingMode>,
        val patientBookingMode: OverrideWire<PatientBookingMode>,
        val provisionalCapacityMode: OverrideWire<ProvisionalCapacityMode>,
        val provisionalRequestTtlSeconds: OverrideWire<Long>,
        val resourceHoldTtlSeconds: OverrideWire<Long>,
        val approvalRoles: OverrideWire<Set<ActorRole>>,
        val adminConsentEvidence: OverrideWire<ConsentEvidenceWire>,
        val confirmedChangeMode: OverrideWire<ConfirmedChangeMode>,
    )

    private data class HoldAndConsentOverrideWire(
        val consentEvidenceRequired: OverrideWire<Boolean>,
        val maximumConsentAgeSeconds: OverrideWire<Long>,
    )

    private data class CapacityAndOverbookingOverrideWire(
        val nominalCapacity: OverrideWire<Int>,
        val overbookingQuota: OverrideWire<Int>,
        val automaticReductionEnabled: OverrideWire<Boolean>,
    )

    private data class PriorityAndReliabilityOverrideWire(
        val priorityWeights: OverrideWire<Map<String, Int>>,
        val noShowPenalty: OverrideWire<Int>,
        val sameDayCancellationPenalty: OverrideWire<Int>,
    )

    private data class ReconfirmationOverrideWire(
        val required: OverrideWire<Boolean>,
        val leadTimeSeconds: OverrideWire<Long>,
        val maximumAttempts: OverrideWire<Int>,
    )

    private data class DisruptionRecoveryOverrideWire(
        val automaticProposalEnabled: OverrideWire<Boolean>,
        val maximumProposalDelaySeconds: OverrideWire<Long>,
    )

    private data class OperatingExtensionOverrideWire(
        val extensionEnabled: OverrideWire<Boolean>,
        val maximumExtensionMinutes: OverrideWire<Int>,
    )

    private data class NotificationAndSlaOverrideWire(
        val notificationChannels: OverrideWire<Set<String>>,
        val disruptionNoticeSeconds: OverrideWire<Long>,
    )

    private fun BookingCommitmentWire.toPolicy() = BookingCommitmentPolicy(
        adminBookingMode = adminBookingMode,
        patientBookingMode = patientBookingMode,
        provisionalCapacityMode = provisionalCapacityMode,
        provisionalRequestTtl = Duration.ofSeconds(provisionalRequestTtlSeconds),
        resourceHoldTtl = resourceHoldTtlSeconds?.let(Duration::ofSeconds),
        approvalRoles = approvalRoles,
        adminConsentEvidence = ConsentEvidenceRequirement(
            allowedEvidenceTypes = adminConsentEvidence.allowedEvidenceTypes,
            maximumAge = Duration.ofSeconds(adminConsentEvidence.maximumAgeSeconds),
            termsHashRequired = adminConsentEvidence.termsHashRequired,
        ),
        confirmedChangeMode = confirmedChangeMode,
    )

    private fun ConsentEvidenceWire.toRequirement() = ConsentEvidenceRequirement(
        allowedEvidenceTypes = allowedEvidenceTypes,
        maximumAge = Duration.ofSeconds(maximumAgeSeconds),
        termsHashRequired = termsHashRequired,
    )

    private fun BookingCommitmentOverrideWire.toPolicy() = BookingCommitmentOverride(
        adminBookingMode = adminBookingMode.toDomain("adminBookingMode"),
        patientBookingMode = patientBookingMode.toDomain("patientBookingMode"),
        provisionalCapacityMode = provisionalCapacityMode.toDomain("provisionalCapacityMode"),
        provisionalRequestTtlSeconds =
            provisionalRequestTtlSeconds.toDomain("provisionalRequestTtlSeconds"),
        resourceHoldTtlSeconds = resourceHoldTtlSeconds.toDomain("resourceHoldTtlSeconds"),
        approvalRoles = approvalRoles.toDomain("approvalRoles"),
        adminConsentEvidence = adminConsentEvidence
            .toDomain("adminConsentEvidence")
            .mapSet { it.toRequirement() },
        confirmedChangeMode = confirmedChangeMode.toDomain("confirmedChangeMode"),
    )

    private fun HoldAndConsentOverrideWire.toPolicy() = HoldAndConsentOverride(
        consentEvidenceRequired = consentEvidenceRequired.toDomain("consentEvidenceRequired"),
        maximumConsentAgeSeconds = maximumConsentAgeSeconds.toDomain("maximumConsentAgeSeconds"),
    )

    private fun CapacityAndOverbookingOverrideWire.toPolicy() = CapacityAndOverbookingOverride(
        nominalCapacity = nominalCapacity.toDomain("nominalCapacity"),
        overbookingQuota = overbookingQuota.toDomain("overbookingQuota"),
        automaticReductionEnabled =
            automaticReductionEnabled.toDomain("automaticReductionEnabled"),
    )

    private fun PriorityAndReliabilityOverrideWire.toPolicy() = PriorityAndReliabilityOverride(
        priorityWeights = priorityWeights.toDomain("priorityWeights"),
        noShowPenalty = noShowPenalty.toDomain("noShowPenalty"),
        sameDayCancellationPenalty =
            sameDayCancellationPenalty.toDomain("sameDayCancellationPenalty"),
    )

    private fun ReconfirmationOverrideWire.toPolicy() = ReconfirmationOverride(
        required = required.toDomain("required"),
        leadTimeSeconds = leadTimeSeconds.toDomain("leadTimeSeconds"),
        maximumAttempts = maximumAttempts.toDomain("maximumAttempts"),
    )

    private fun DisruptionRecoveryOverrideWire.toPolicy() = DisruptionRecoveryOverride(
        automaticProposalEnabled =
            automaticProposalEnabled.toDomain("automaticProposalEnabled"),
        maximumProposalDelaySeconds =
            maximumProposalDelaySeconds.toDomain("maximumProposalDelaySeconds"),
    )

    private fun OperatingExtensionOverrideWire.toPolicy() = OperatingExtensionOverride(
        extensionEnabled = extensionEnabled.toDomain("extensionEnabled"),
        maximumExtensionMinutes =
            maximumExtensionMinutes.toDomain("maximumExtensionMinutes"),
    )

    private fun NotificationAndSlaOverrideWire.toPolicy() = NotificationAndSlaOverride(
        notificationChannels = notificationChannels.toDomain("notificationChannels"),
        disruptionNoticeSeconds = disruptionNoticeSeconds.toDomain("disruptionNoticeSeconds"),
    )

    private fun <T, R> OverrideValue<T>.mapSet(transform: (T) -> R): OverrideValue<R> =
        when (this) {
            OverrideValue.Inherit -> OverrideValue.Inherit
            is OverrideValue.Set -> OverrideValue.Set(transform(value))
            OverrideValue.Disable -> OverrideValue.Disable
        }
}
