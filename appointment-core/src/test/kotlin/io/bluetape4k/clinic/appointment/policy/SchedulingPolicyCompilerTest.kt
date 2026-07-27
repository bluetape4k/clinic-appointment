package io.bluetape4k.clinic.appointment.policy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.policy.*
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyCompiler
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 테넌트 기본값과 병원 재정의를 결정적으로 합성하는 예약 정책 컴파일러 계약을 검증한다.
 *
 * `INHERIT`, `SET`, `DISABLE`의 우선순위와 source path 추적, generation·평가 시각 보존,
 * booking origin 규칙을 함께 확인한다. 같은 논리 입력은 순서와 무관하게 같은 불변 snapshot을
 * 만들고, 이미 진행된 예약이 아니라 향후 의사결정에만 소비될 수 있는 증거를 남겨야 한다.
 */
class SchedulingPolicyCompilerTest {

    @Test
    fun `merges tenant capacity with a clinic override and records every source path`() {
        val result = SchedulingPolicyCompiler.compileCapacity(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
            serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
            generation = PolicyGenerationVector(tenantGeneration = 7L, clinicGeneration = 3L),
            tenantVersion = 5L,
            clinicVersion = 2L,
            tenant = CapacityAndOverbookingPolicy(
                nominalCapacity = 10,
                overbookingQuota = 2,
                absoluteBookingLimit = 12,
                automaticReductionEnabled = true,
            ),
            clinic = CapacityAndOverbookingOverride(
                nominalCapacity = OverrideValue.Set(8),
                overbookingQuota = OverrideValue.Inherit,
                automaticReductionEnabled = OverrideValue.Disable,
            ),
        )

        result.payload.capacityAndOverbooking shouldBeEqualTo CapacityAndOverbookingPolicy(
            nominalCapacity = 8,
            overbookingQuota = 2,
            absoluteBookingLimit = 12,
            automaticReductionEnabled = false,
        )
        result.sourceVersions shouldBeEqualTo mapOf(
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to SourceVersion(
                tenantVersion = 5L,
                clinicVersion = 2L,
            ),
        )
        result.sourceByPath shouldBeEqualTo mapOf(
            "capacityAndOverbooking.nominalCapacity" to PolicyValueSource.CLINIC,
            "capacityAndOverbooking.overbookingQuota" to PolicyValueSource.TENANT,
            "capacityAndOverbooking.absoluteBookingLimit" to PolicyValueSource.TENANT,
            "capacityAndOverbooking.automaticReductionEnabled" to PolicyValueSource.CLINIC,
        )
        result.disabledFeatures shouldBeEqualTo setOf(
            "capacityAndOverbooking.automaticReductionEnabled",
        )
    }

    @Test
    fun `rejects a clinic capacity that relaxes the tenant hard ceiling`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyCompiler.compileCapacity(
                tenantGroupId = 1L,
                clinicId = 2L,
                decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
                serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
                generation = PolicyGenerationVector(1L, 1L),
                tenantVersion = 1L,
                clinicVersion = 1L,
                tenant = CapacityAndOverbookingPolicy(10, 0, 10, true),
                clinic = CapacityAndOverbookingOverride(
                    nominalCapacity = OverrideValue.Set(11),
                    overbookingQuota = OverrideValue.Inherit,
                    automaticReductionEnabled = OverrideValue.Inherit,
                ),
            )
        }
    }

    @Test
    fun `compiles all policy kinds and records every leaf source`() {
        val clinic = ClinicSchedulingPolicyOverrides(
            capacityAndOverbooking = CapacityAndOverbookingOverride(
                nominalCapacity = OverrideValue.Set(8),
                overbookingQuota = OverrideValue.Inherit,
                automaticReductionEnabled = OverrideValue.Disable,
            ),
            reconfirmation = ReconfirmationOverride(
                required = OverrideValue.Inherit,
                leadTimeSeconds = OverrideValue.Inherit,
                maximumAttempts = OverrideValue.Set(2),
            ),
            operatingExtension = OperatingExtensionOverride(
                extensionEnabled = OverrideValue.Inherit,
                maximumExtensionMinutes = OverrideValue.Set(30),
            ),
        )

        val result = SchedulingPolicyCompiler.compile(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
            serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
            generation = PolicyGenerationVector(7L, 3L),
            sourceVersions = sourceVersions(
                SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
                SchedulingPolicyKind.RECONFIRMATION,
                SchedulingPolicyKind.OPERATING_EXTENSION,
            ),
            tenant = fullTenantPolicy(),
            clinic = clinic,
        )

        result.payload.capacityAndOverbooking?.nominalCapacity shouldBeEqualTo 8
        result.payload.capacityAndOverbooking?.automaticReductionEnabled shouldBeEqualTo false
        result.payload.reconfirmation?.maximumAttempts shouldBeEqualTo 2
        result.payload.operatingExtension?.maximumExtensionMinutes shouldBeEqualTo 30
        result.sourceByPath.size shouldBeEqualTo 30
        result.sourceByPath["bookingCommitment.confirmedChangeMode"] shouldBeEqualTo
            PolicyValueSource.TENANT
        result.sourceByPath["reconfirmation.maximumAttempts"] shouldBeEqualTo
            PolicyValueSource.CLINIC
        result.disabledFeatures shouldBeEqualTo
            setOf("capacityAndOverbooking.automaticReductionEnabled")
    }

    @Test
    fun `rejects clinic retry extension and delay values above tenant ceilings`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyCompiler.compile(
                tenantGroupId = 1L,
                clinicId = 2L,
                decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
                serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
                generation = PolicyGenerationVector(1L, 1L),
                sourceVersions = sourceVersions(SchedulingPolicyKind.RECONFIRMATION),
                tenant = fullTenantPolicy(),
                clinic = ClinicSchedulingPolicyOverrides(
                    reconfirmation = ReconfirmationOverride(
                        required = OverrideValue.Inherit,
                        leadTimeSeconds = OverrideValue.Inherit,
                        maximumAttempts = OverrideValue.Set(4),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyCompiler.compile(
                tenantGroupId = 1L,
                clinicId = 2L,
                decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
                serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
                generation = PolicyGenerationVector(1L, 1L),
                sourceVersions = sourceVersions(SchedulingPolicyKind.OPERATING_EXTENSION),
                tenant = fullTenantPolicy(),
                clinic = ClinicSchedulingPolicyOverrides(
                    operatingExtension = OperatingExtensionOverride(
                        extensionEnabled = OverrideValue.Inherit,
                        maximumExtensionMinutes = OverrideValue.Set(61),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyCompiler.compile(
                tenantGroupId = 1L,
                clinicId = 2L,
                decisionAt = Instant.parse("2026-08-01T00:00:00Z"),
                serviceAt = Instant.parse("2026-08-20T00:00:00Z"),
                generation = PolicyGenerationVector(1L, 1L),
                sourceVersions = sourceVersions(SchedulingPolicyKind.DISRUPTION_RECOVERY),
                tenant = fullTenantPolicy(),
                clinic = ClinicSchedulingPolicyOverrides(
                    disruptionRecovery = DisruptionRecoveryOverride(
                        automaticProposalEnabled = OverrideValue.Inherit,
                        maximumProposalDelaySeconds = OverrideValue.Set(3_601L),
                    ),
                ),
            )
        }
    }

    private fun sourceVersions(
        vararg clinicKinds: SchedulingPolicyKind,
    ): Map<SchedulingPolicyKind, SourceVersion> =
        SchedulingPolicyKind.entries.associateWith { kind ->
            SourceVersion(
                tenantVersion = 1L,
                clinicVersion = 1L.takeIf { kind in clinicKinds },
            )
        }

    private fun fullTenantPolicy() = CompiledSchedulingPolicy(
        bookingCommitment = BookingCommitmentPolicy(
            adminBookingMode = AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
            patientBookingMode = PatientBookingMode.PROVISIONAL_APPROVAL_REQUIRED,
            provisionalCapacityMode = ProvisionalCapacityMode.NO_HOLD,
            provisionalRequestTtl = Duration.ofHours(24),
            resourceHoldTtl = null,
            approvalRoles = setOf(ActorRole.ADMIN, ActorRole.STAFF),
            adminConsentEvidence = ConsentEvidenceRequirement(
                allowedEvidenceTypes = setOf("SIGNED_FORM"),
                maximumAge = Duration.ofHours(24),
                termsHashRequired = true,
            ),
            confirmedChangeMode = ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
        ),
        holdAndConsent = HoldAndConsentPolicy(
            consentEvidenceRequired = true,
            maximumConsentAgeSeconds = 86_400L,
        ),
        capacityAndOverbooking = CapacityAndOverbookingPolicy(10, 2, 12, true),
        priorityAndReliability = PriorityAndReliabilityPolicy(
            priorityWeights = mapOf("RETURN_VISIT" to 2),
            noShowPenalty = 5,
            sameDayCancellationPenalty = 2,
            minimumPriorityScore = 0,
        ),
        reconfirmation = ReconfirmationPolicy(true, 86_400L, 3),
        disruptionRecovery = DisruptionRecoveryPolicy(true, 3_600L, true),
        operatingExtension = OperatingExtensionPolicy(true, 60, 120),
        notificationAndSla = NotificationAndSlaPolicy(setOf("SMS"), 900L, 3_600L),
    )
}
