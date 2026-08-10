package io.bluetape4k.clinic.appointment

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * core의 장기 보존 data class와 policy wire 계약이 다시 일반 Kotlin data class로
 * 후퇴하지 않도록 유지하는 source-level guard입니다.
 */
class KotlinProductionPatternComplianceTest {

    @Test
    fun `durable core data classes declare Serializable and serialVersionUID`() {
        durableContracts.forEach { (relativePath, classNames) ->
            val source = source(relativePath)
            classNames.forEach { className ->
                val declaration = classDeclaration(source, className)
                (declaration.contains("Serializable") || inheritsSerializable(relativePath, className))
                    .shouldBeTrue()
                declaration.contains("serialVersionUID").shouldBeTrue()
            }
        }
    }

    private fun inheritsSerializable(relativePath: String, className: String): Boolean =
        (relativePath to className) in inheritedSerializableContracts

    private fun classDeclaration(source: String, className: String): String {
        val start = source.indexOf("data class $className")
        require(start >= 0) { "data class $className not found" }
        val next = source.indexOf("data class ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, next)
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("appointment-core/src/main/kotlin").resolve(relativePath),
            Path.of("src/main/kotlin").resolve(relativePath),
            Path.of("../appointment-core/src/main/kotlin").resolve(relativePath),
        ).firstOrNull(Files::exists) ?: error("Production source not found: $relativePath")
        return Files.readString(path)
    }

    private companion object {
        val inheritedSerializableContracts = setOf(
            "io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferNotificationPort.kt" to "Offered",
            "io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferNotificationPort.kt" to "NoCandidate",
            "io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferNotificationPort.kt" to "Expired",
            "io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt" to "Acquired",
            "io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt" to "InProgress",
            "io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt" to "ReplaySucceeded",
            "io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt" to "ReplayFailed",
        )

        val durableContracts = mapOf(
            "io/bluetape4k/clinic/appointment/model/policy/BookingCommitmentPolicy.kt" to
                listOf("BookingCommitmentPolicy", "BookingCommitmentOverride"),
            "io/bluetape4k/clinic/appointment/model/policy/CapacityAndReliabilityPolicies.kt" to
                listOf(
                    "CapacityAndOverbookingPolicy",
                    "CapacityAndOverbookingOverride",
                    "PriorityAndReliabilityPolicy",
                    "PriorityAndReliabilityOverride",
                ),
            "io/bluetape4k/clinic/appointment/model/policy/OperationalSchedulingPolicies.kt" to
                listOf(
                    "HoldAndConsentPolicy",
                    "HoldAndConsentOverride",
                    "ReconfirmationPolicy",
                    "ReconfirmationOverride",
                    "DisruptionRecoveryPolicy",
                    "DisruptionRecoveryOverride",
                    "OperatingExtensionPolicy",
                    "OperatingExtensionOverride",
                    "NotificationAndSlaPolicy",
                    "NotificationAndSlaOverride",
                ),
            "io/bluetape4k/clinic/appointment/model/plan/BookingPreferenceSnapshot.kt" to
                listOf("ExactDateTime", "DateRange", "PreferredWeekdaysAndWindows"),
            "io/bluetape4k/clinic/appointment/model/dto/SchedulingPolicyRecords.kt" to
                listOf(
                    "PolicyScopeRef",
                    "SchedulingPolicyDefinitionRecord",
                    "SchedulingPolicyApprovalRecord",
                    "SchedulingPolicyScopeHeadRecord",
                    "EffectiveSchedulingPolicySnapshotRecord",
                    "SchedulingPolicyActivationCommandRecord",
                    "PolicyPreviewCursor",
                    "PolicyPreviewProgress",
                    "SchedulingPolicyPreviewJobRecord",
                ),
            "io/bluetape4k/clinic/appointment/model/dto/ProfileReevaluationRecords.kt" to
                listOf(
                    "ProfileReevaluationScope",
                    "ProfileReevaluationHeadRecord",
                    "ProfileReevaluationJobRecord",
                    "ProfileReevaluationOutcomeCounts",
                    "ProfileReevaluationCursor",
                    "UpsertProfileChange",
                    "ClaimProfileReevaluationJobs",
                    "ProfileReevaluationClinicCursor",
                    "RedriveProfileReevaluationJob",
                    "ProfileReevaluationOutcomeRecord",
                ),
            "io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepository.kt" to
                listOf("BookingReliabilityOperationalSummary"),
            "io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt" to
                listOf("ProfileReevaluationRepositorySummary"),
            "io/bluetape4k/clinic/appointment/service/AppointmentPlanFactory.kt" to
                listOf("AppointmentPlanFactoryInput"),
            "io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt" to
                listOf("ProfileReevaluationAppointmentCandidate"),
            "io/bluetape4k/clinic/appointment/model/service/TenantClinicScope.kt" to
                listOf("TenantClinicScope"),
            "io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt" to
                listOf(
                    "BookingCommitmentWire",
                    "ConsentEvidenceWire",
                    "OverrideWire",
                    "BookingCommitmentOverrideWire",
                    "HoldAndConsentOverrideWire",
                    "CapacityAndOverbookingOverrideWire",
                    "PriorityAndReliabilityWire",
                    "PriorityAndReliabilityOverrideWire",
                    "ReconfirmationOverrideWire",
                    "DisruptionRecoveryOverrideWire",
                    "OperatingExtensionOverrideWire",
                    "NotificationAndSlaOverrideWire",
                ),
            "io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferNotificationPort.kt" to
                listOf(
                    "Offered",
                    "NoCandidate",
                    "Expired",
                    "WaitlistGenerationProgression",
                ),
            "io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt" to
                listOf("Acquired", "InProgress", "ReplaySucceeded", "ReplayFailed"),
        )
    }
}
