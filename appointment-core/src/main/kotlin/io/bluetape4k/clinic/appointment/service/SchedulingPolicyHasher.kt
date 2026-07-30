package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * policy payload와 effective snapshot에 대한 canonical SHA-256 계약입니다.
 *
 * canonical input은 schema version, stable field name, 명시적 null marker, 정렬된
 * map/set entry, 의미상 순서가 있는 list를 포함합니다. policy 값에 대해 data class
 * `toString()`에 의존하지 않습니다. payload hash에서는 database identity와 actor audit
 * metadata를 제외합니다. snapshot hash에는 evaluation instant, generation, source version,
 * source path, disabled path, warning, fully compiled payload를 추가합니다.
 */
object SchedulingPolicyHasher {

    /**
     * typed schema-one payload 하나에 대한 lowercase SHA-256을 계산합니다.
     *
     * @param payload 이미 [SchedulingPolicyValidator]를 통과했어야 하는 typed schema-one
     * payload입니다. 이 method는 field를 canonicalize하고 size bound를 적용하지만,
     * authorization, persistence, 모든 업무 규칙 적용을 수행하지는 않습니다.
     * @return canonical payload field에 대한 lowercase 64-character SHA-256입니다.
     * @throws IllegalArgumentException canonical byte stream이 JSON codec과 같은 256 KiB
     * safety bound보다 큰 경우 발생합니다.
     */
    fun payloadHash(payload: SchedulingPolicyPayload): String =
        digest(maxBytes = SchedulingPolicyPayloadCodec.MAX_PAYLOAD_BYTES) {
            updatePayload("payload", payload)
        }

    /**
     * immutable effective-policy snapshot의 identity를 계산합니다.
     *
     * map과 set의 insertion order는 결과에 영향을 주지 않습니다. warning order는 compiler의
     * deterministic decision order를 전달하므로 의도적으로 보존합니다.
     *
     * @param tenantGroupId identity에 포함되는 양수 tenant boundary입니다.
     * @param clinicId identity에 포함되는 양수 clinic identity입니다.
     * @param decisionAt policy decision이 이루어진 UTC instant입니다.
     * @param serviceAt future-effective policy를 선택할 UTC service instant입니다.
     * @param generation compiler가 관찰한 tenant/clinic head generation입니다.
     * @param sourceVersions kind별 정확한 contributing definition version입니다.
     * @param sourceByPath compiled leaf path마다 기록된 조직적 source입니다.
     * @param disabledFeatures clinic override가 명시적으로 disable한 semantic path입니다.
     * set order는 canonicalize됩니다.
     * @param warnings 순서가 있는 compiler warning입니다. list order는 hash에서 의미가 있습니다.
     * @param payload 포함된 모든 kind의 fully resolved policy value입니다.
     * @return lowercase 64-character SHA-256 string입니다.
     */
    @Suppress("LongParameterList")
    fun snapshotHash(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        generation: PolicyGenerationVector,
        sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
        sourceByPath: Map<String, PolicyValueSource>,
        disabledFeatures: Set<String>,
        warnings: List<String>,
        payload: CompiledSchedulingPolicy,
    ): String = digest {
        field("schemaVersion", 1)
        field("tenantGroupId", tenantGroupId)
        field("clinicId", clinicId)
        field("decisionAt", decisionAt)
        field("serviceAt", serviceAt)
        field("generation.tenant", generation.tenantGeneration)
        field("generation.clinic", generation.clinicGeneration)
        sourceVersions.toSortedMap(compareBy(SchedulingPolicyKind::name)).forEach { (kind, source) ->
            field("sourceVersions.${kind.name}.tenant", source.tenantVersion)
            field("sourceVersions.${kind.name}.clinic", source.clinicVersion)
        }
        sourceByPath.toSortedMap().forEach { (path, source) ->
            field("sourceByPath.$path", source)
        }
        sortedValues("disabledFeatures", disabledFeatures.map { it })
        orderedValues("warnings", warnings)
        updateCompiled(payload)
    }

    private fun digest(
        maxBytes: Int? = null,
        block: CanonicalDigest.() -> Unit,
    ): String = CanonicalDigest(maxBytes)
        .apply(block)
        .finish()

    private class CanonicalDigest(
        private val maxBytes: Int?,
    ) {
        private val delegate = MessageDigest.getInstance("SHA-256")
        private var byteCount: Int = 0

        fun update(bytes: ByteArray) {
            byteCount = Math.addExact(byteCount, bytes.size)
            require(maxBytes == null || byteCount <= maxBytes) {
                "canonical policy payload must not exceed $maxBytes UTF-8 bytes"
            }
            delegate.update(bytes)
        }

        fun update(value: Int) {
            update(byteArrayOf(value.toByte()))
        }

        fun finish(): String =
            delegate.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun CanonicalDigest.updatePayload(
        prefix: String,
        payload: SchedulingPolicyPayload,
    ) {
        field("$prefix.kind", payload.kind)
        when (payload) {
            is BookingCommitmentPolicy -> updateBooking(prefix, payload)
            is BookingCommitmentOverride -> updateBookingOverride(prefix, payload)
            is HoldAndConsentPolicy -> updateHoldAndConsent(prefix, payload)
            is HoldAndConsentOverride -> updateHoldAndConsentOverride(prefix, payload)
            is CapacityAndOverbookingPolicy -> updateCapacity(prefix, payload)
            is CapacityAndOverbookingOverride -> updateCapacityOverride(prefix, payload)
            is PriorityAndReliabilityPolicy -> updatePriority(prefix, payload)
            is PriorityAndReliabilityOverride -> updatePriorityOverride(prefix, payload)
            is ReconfirmationPolicy -> updateReconfirmation(prefix, payload)
            is ReconfirmationOverride -> updateReconfirmationOverride(prefix, payload)
            is DisruptionRecoveryPolicy -> updateDisruption(prefix, payload)
            is DisruptionRecoveryOverride -> updateDisruptionOverride(prefix, payload)
            is OperatingExtensionPolicy -> updateOperatingExtension(prefix, payload)
            is OperatingExtensionOverride -> updateOperatingExtensionOverride(prefix, payload)
            is NotificationAndSlaPolicy -> updateNotification(prefix, payload)
            is NotificationAndSlaOverride -> updateNotificationOverride(prefix, payload)
        }
    }

    private fun CanonicalDigest.updateBooking(
        prefix: String,
        policy: BookingCommitmentPolicy,
    ) {
        field("$prefix.adminBookingMode", policy.adminBookingMode)
        field("$prefix.patientBookingMode", policy.patientBookingMode)
        field("$prefix.provisionalCapacityMode", policy.provisionalCapacityMode)
        field("$prefix.provisionalRequestTtl", policy.provisionalRequestTtl.seconds)
        field("$prefix.resourceHoldTtl", policy.resourceHoldTtl?.seconds)
        sortedValues("$prefix.approvalRoles", policy.approvalRoles.map { it.name })
        sortedValues(
            "$prefix.adminConsentEvidence.allowedEvidenceTypes",
            policy.adminConsentEvidence.allowedEvidenceTypes,
        )
        field(
            "$prefix.adminConsentEvidence.maximumAge",
            policy.adminConsentEvidence.maximumAge.seconds,
        )
        field(
            "$prefix.adminConsentEvidence.termsHashRequired",
            policy.adminConsentEvidence.termsHashRequired,
        )
        field("$prefix.confirmedChangeMode", policy.confirmedChangeMode)
    }

    private fun CanonicalDigest.updateCapacity(
        prefix: String,
        policy: CapacityAndOverbookingPolicy,
    ) {
        field("$prefix.nominalCapacity", policy.nominalCapacity)
        field("$prefix.overbookingQuota", policy.overbookingQuota)
        field("$prefix.absoluteBookingLimit", policy.absoluteBookingLimit)
        field("$prefix.automaticReductionEnabled", policy.automaticReductionEnabled)
    }

    private fun CanonicalDigest.updateBookingOverride(
        prefix: String,
        policy: BookingCommitmentOverride,
    ) {
        updateScalarOverride("$prefix.adminBookingMode", policy.adminBookingMode)
        updateScalarOverride("$prefix.patientBookingMode", policy.patientBookingMode)
        updateScalarOverride("$prefix.provisionalCapacityMode", policy.provisionalCapacityMode)
        updateOverride(
            "$prefix.provisionalRequestTtlSeconds",
            policy.provisionalRequestTtlSeconds,
            { name, value -> field(name, value) },
        )
        updateScalarOverride("$prefix.resourceHoldTtlSeconds", policy.resourceHoldTtlSeconds)
        updateOverride("$prefix.approvalRoles", policy.approvalRoles) { name, roles ->
            sortedValues(name, roles.map(ActorRole::name))
        }
        updateOverride("$prefix.adminConsentEvidence", policy.adminConsentEvidence) { name, evidence ->
            sortedValues("$name.allowedEvidenceTypes", evidence.allowedEvidenceTypes)
            field("$name.maximumAgeSeconds", evidence.maximumAge.seconds)
            field("$name.termsHashRequired", evidence.termsHashRequired)
        }
        updateScalarOverride("$prefix.confirmedChangeMode", policy.confirmedChangeMode)
    }

    private fun CanonicalDigest.updateHoldAndConsent(
        prefix: String,
        policy: HoldAndConsentPolicy,
    ) {
        field("$prefix.consentEvidenceRequired", policy.consentEvidenceRequired)
        field("$prefix.maximumConsentAgeSeconds", policy.maximumConsentAgeSeconds)
    }

    private fun CanonicalDigest.updateHoldAndConsentOverride(
        prefix: String,
        policy: HoldAndConsentOverride,
    ) {
        updateScalarOverride("$prefix.consentEvidenceRequired", policy.consentEvidenceRequired)
        updateScalarOverride("$prefix.maximumConsentAgeSeconds", policy.maximumConsentAgeSeconds)
    }

    private fun CanonicalDigest.updateCapacityOverride(
        prefix: String,
        policy: CapacityAndOverbookingOverride,
    ) {
        updateScalarOverride("$prefix.nominalCapacity", policy.nominalCapacity)
        updateScalarOverride("$prefix.overbookingQuota", policy.overbookingQuota)
        updateOverride(
            "$prefix.automaticReductionEnabled",
            policy.automaticReductionEnabled,
            { name, value -> field(name, value) },
        )
    }

    private fun CanonicalDigest.updatePriority(
        prefix: String,
        policy: PriorityAndReliabilityPolicy,
    ) {
        sortedMap("$prefix.priorityWeights", policy.priorityWeights)
        field("$prefix.noShowPenalty", policy.noShowPenalty)
        field("$prefix.sameDayCancellationPenalty", policy.sameDayCancellationPenalty)
        field("$prefix.minimumPriorityScore", policy.minimumPriorityScore)
    }

    private fun CanonicalDigest.updatePriorityOverride(
        prefix: String,
        policy: PriorityAndReliabilityOverride,
    ) {
        updateOverride("$prefix.priorityWeights", policy.priorityWeights) { name, value ->
            sortedMap(name, value)
        }
        updateScalarOverride("$prefix.noShowPenalty", policy.noShowPenalty)
        updateOverride(
            "$prefix.sameDayCancellationPenalty",
            policy.sameDayCancellationPenalty,
            { name, value -> field(name, value) },
        )
    }

    private fun CanonicalDigest.updateReconfirmation(
        prefix: String,
        policy: ReconfirmationPolicy,
    ) {
        field("$prefix.required", policy.required)
        field("$prefix.leadTimeSeconds", policy.leadTimeSeconds)
        field("$prefix.maximumAttempts", policy.maximumAttempts)
    }

    private fun CanonicalDigest.updateReconfirmationOverride(
        prefix: String,
        policy: ReconfirmationOverride,
    ) {
        updateScalarOverride("$prefix.required", policy.required)
        updateScalarOverride("$prefix.leadTimeSeconds", policy.leadTimeSeconds)
        updateScalarOverride("$prefix.maximumAttempts", policy.maximumAttempts)
    }

    private fun CanonicalDigest.updateDisruption(
        prefix: String,
        policy: DisruptionRecoveryPolicy,
    ) {
        field("$prefix.automaticProposalEnabled", policy.automaticProposalEnabled)
        field("$prefix.maximumProposalDelaySeconds", policy.maximumProposalDelaySeconds)
        field("$prefix.preserveConfirmedAppointment", policy.preserveConfirmedAppointment)
    }

    private fun CanonicalDigest.updateDisruptionOverride(
        prefix: String,
        policy: DisruptionRecoveryOverride,
    ) {
        updateOverride(
            "$prefix.automaticProposalEnabled",
            policy.automaticProposalEnabled,
            { name, value -> field(name, value) },
        )
        updateOverride(
            "$prefix.maximumProposalDelaySeconds",
            policy.maximumProposalDelaySeconds,
            { name, value -> field(name, value) },
        )
    }

    private fun CanonicalDigest.updateOperatingExtension(
        prefix: String,
        policy: OperatingExtensionPolicy,
    ) {
        field("$prefix.extensionEnabled", policy.extensionEnabled)
        field("$prefix.maximumExtensionMinutes", policy.maximumExtensionMinutes)
        field("$prefix.legalSafetyCeilingMinutes", policy.legalSafetyCeilingMinutes)
    }

    private fun CanonicalDigest.updateOperatingExtensionOverride(
        prefix: String,
        policy: OperatingExtensionOverride,
    ) {
        updateScalarOverride("$prefix.extensionEnabled", policy.extensionEnabled)
        updateScalarOverride("$prefix.maximumExtensionMinutes", policy.maximumExtensionMinutes)
    }

    private fun CanonicalDigest.updateNotification(
        prefix: String,
        policy: NotificationAndSlaPolicy,
    ) {
        sortedValues("$prefix.notificationChannels", policy.notificationChannels)
        field("$prefix.disruptionNoticeSeconds", policy.disruptionNoticeSeconds)
        field("$prefix.mandatoryResponseSeconds", policy.mandatoryResponseSeconds)
        field(
            "$prefix.profileReevaluationHeldTargetSeconds",
            policy.profileReevaluationHeldTargetSeconds,
        )
        field(
            "$prefix.profileReevaluationProposedTargetSeconds",
            policy.profileReevaluationProposedTargetSeconds,
        )
    }

    private fun CanonicalDigest.updateNotificationOverride(
        prefix: String,
        policy: NotificationAndSlaOverride,
    ) {
        updateOverride("$prefix.notificationChannels", policy.notificationChannels) { name, channels ->
            sortedValues(name, channels)
        }
        updateOverride(
            "$prefix.disruptionNoticeSeconds",
            policy.disruptionNoticeSeconds,
            { name, value -> field(name, value) },
        )
        updateOverride(
            "$prefix.profileReevaluationHeldTargetSeconds",
            policy.profileReevaluationHeldTargetSeconds,
            { name, value -> field(name, value) },
        )
        updateOverride(
            "$prefix.profileReevaluationProposedTargetSeconds",
            policy.profileReevaluationProposedTargetSeconds,
            { name, value -> field(name, value) },
        )
    }

    private fun CanonicalDigest.updateCompiled(payload: CompiledSchedulingPolicy) {
        payload.bookingCommitment?.let { updateBooking("compiled.bookingCommitment", it) }
            ?: field("compiled.bookingCommitment", null)
        payload.capacityAndOverbooking?.let {
            updateCapacity("compiled.capacityAndOverbooking", it)
        } ?: field("compiled.capacityAndOverbooking", null)
        payload.holdAndConsent?.let { updateHoldAndConsent("compiled.holdAndConsent", it) }
            ?: field("compiled.holdAndConsent", null)
        payload.priorityAndReliability?.let {
            updatePriority("compiled.priorityAndReliability", it)
        } ?: field("compiled.priorityAndReliability", null)
        payload.reconfirmation?.let { updateReconfirmation("compiled.reconfirmation", it) }
            ?: field("compiled.reconfirmation", null)
        payload.disruptionRecovery?.let { updateDisruption("compiled.disruptionRecovery", it) }
            ?: field("compiled.disruptionRecovery", null)
        payload.operatingExtension?.let {
            updateOperatingExtension("compiled.operatingExtension", it)
        } ?: field("compiled.operatingExtension", null)
        payload.notificationAndSla?.let { updateNotification("compiled.notificationAndSla", it) }
            ?: field("compiled.notificationAndSla", null)
    }

    private fun CanonicalDigest.sortedMap(
        name: String,
        values: Map<String, Int>,
    ) {
        field("$name.size", values.size)
        values.toSortedMap().forEach { (key, value) -> field("$name.$key", value) }
    }

    private inline fun <T> CanonicalDigest.updateOverride(
        name: String,
        override: OverrideValue<T>,
        writeValue: CanonicalDigest.(String, T) -> Unit,
    ) {
        when (override) {
            OverrideValue.Inherit -> {
                field("$name.mode", "INHERIT")
                field("$name.value", null)
            }
            is OverrideValue.Set -> {
                field("$name.mode", "SET")
                writeValue("$name.value", override.value)
            }
            OverrideValue.Disable -> {
                field("$name.mode", "DISABLE")
                field("$name.value", null)
            }
        }
    }

    private fun <T> CanonicalDigest.updateScalarOverride(
        name: String,
        override: OverrideValue<T>,
    ) = updateOverride(name, override) { fieldName, value -> field(fieldName, value) }

    private fun CanonicalDigest.sortedValues(
        name: String,
        values: Collection<String>,
    ) = orderedValues(name, values.sorted())

    private fun CanonicalDigest.orderedValues(
        name: String,
        values: Collection<String>,
    ) {
        field("$name.size", values.size)
        values.forEachIndexed { index, value -> field("$name[$index]", value) }
    }

    private fun CanonicalDigest.field(
        name: String,
        value: Any?,
    ) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(0)
        if (value == null) {
            update(-1)
        } else {
            val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            update(0)
            update(bytes)
        }
        update(0)
    }
}
