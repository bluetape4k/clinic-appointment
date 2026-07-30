package io.bluetape4k.clinic.appointment.policy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.policy.ActorAuditRef
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentOverride
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingOverride
import io.bluetape4k.clinic.appointment.model.policy.ConfirmedChangeMode
import io.bluetape4k.clinic.appointment.model.policy.ConsentEvidenceRequirement
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaOverride
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaPolicy
import io.bluetape4k.clinic.appointment.model.policy.OverrideValue
import io.bluetape4k.clinic.appointment.model.policy.PatientBookingMode
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyDefinition
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyValidator
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 예약 정책 정의가 활성화 가능한 업무 규칙을 모두 만족하는지 검증한다.
 *
 * 관리자 직접 확정과 환자 가예약 승인 경로, capacity/overbooking 상한, 신뢰도 가중치,
 * 재확인·장애복구·연장 운영의 시간 범위를 교차 검증한다. 오류는 저장 이후가 아니라 draft
 * 검증 단계에서 안정된 경로와 사유로 반환되어야 한다.
 */
class SchedulingPolicyValidatorTest {

    @Test
    fun `accepts the approved administrator and patient booking contract`() {
        val definition = definition()

        SchedulingPolicyValidator.validate(definition) shouldBeEqualTo definition
    }

    @Test
    fun `rejects invalid scope identity revision and effective interval`() {
        val valid = definition()

        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(valid.copy(clinicId = 10L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(scope = PolicyScope.CLINIC_OVERRIDE, clinicId = null),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(valid.copy(version = 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(valid.copy(revision = 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(valid.copy(changeReason = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(valid.copy(schemaVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(effectiveUntil = valid.effectiveFrom),
            )
        }
    }

    @Test
    fun `rejects a payload whose kind differs from the envelope`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                definition().copy(kind = SchedulingPolicyKind.RECONFIRMATION),
            )
        }
    }

    @Test
    fun `enforces provisional request and resource hold TTL boundaries`() {
        val valid = definition()
        val booking = valid.payload as BookingCommitmentPolicy

        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(payload = booking.copy(provisionalRequestTtl = Duration.ofMinutes(4))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(payload = booking.copy(provisionalRequestTtl = Duration.ofDays(8))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(payload = booking.copy(resourceHoldTtl = null)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(payload = booking.copy(resourceHoldTtl = Duration.ofMinutes(31))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(
                    payload = booking.copy(
                        provisionalRequestTtl = Duration.ofMinutes(5),
                        resourceHoldTtl = Duration.ofMinutes(6),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validate(
                valid.copy(
                    payload = booking.copy(
                        provisionalCapacityMode = ProvisionalCapacityMode.NO_HOLD,
                        resourceHoldTtl = Duration.ofMinutes(1),
                    ),
                ),
            )
        }
    }

    @Test
    fun `rejects unknown and oversized strict payload JSON`() {
        val codec = SchedulingPolicyPayloadCodec()
        val validJson =
            """
            {
              "adminBookingMode": "DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE",
              "patientBookingMode": "PROVISIONAL_APPROVAL_REQUIRED",
              "provisionalCapacityMode": "NO_HOLD",
              "provisionalRequestTtlSeconds": 86400,
              "resourceHoldTtlSeconds": null,
              "approvalRoles": ["ADMIN", "STAFF"],
              "adminConsentEvidence": {
                "allowedEvidenceTypes": ["SIGNED_FORM"],
                "maximumAgeSeconds": 86400,
                "termsHashRequired": true
              },
              "confirmedChangeMode": "NEW_PROPOSAL_AND_CUSTOMER_CONSENT"
            }
            """.trimIndent()

        val decoded = codec.decode(
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            PolicyScope.TENANT_DEFAULT,
            1,
            validJson,
        )
        decoded shouldBeEqualTo bookingPolicy(ProvisionalCapacityMode.NO_HOLD, null)

        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                PolicyScope.TENANT_DEFAULT,
                1,
                validJson.dropLast(1) + ", \"unexpected\": true}",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                SchedulingPolicyKind.BOOKING_COMMITMENT,
                PolicyScope.TENANT_DEFAULT,
                1,
                " ".repeat(SchedulingPolicyPayloadCodec.MAX_PAYLOAD_BYTES + 1),
            )
        }
    }

    @Test
    fun `decodes an explicit clinic override without polymorphic type metadata`() {
        val decoded = SchedulingPolicyPayloadCodec().decode(
            kind = SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
            scope = PolicyScope.CLINIC_OVERRIDE,
            schemaVersion = 1,
            json =
                """
                {
                  "nominalCapacity": {"mode": "SET", "value": 8},
                  "overbookingQuota": {"mode": "INHERIT"},
                  "automaticReductionEnabled": {"mode": "DISABLE"}
                }
                """.trimIndent(),
        )

        decoded shouldBeEqualTo CapacityAndOverbookingOverride(
            nominalCapacity = OverrideValue.Set(8),
            overbookingQuota = OverrideValue.Inherit,
            automaticReductionEnabled = OverrideValue.Disable,
        )
    }

    @Test
    fun `decodes the booking override wire contract with explicit tri-state values`() {
        val decoded = SchedulingPolicyPayloadCodec().decode(
            kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
            scope = PolicyScope.CLINIC_OVERRIDE,
            schemaVersion = 1,
            json =
                """
                {
                  "adminBookingMode": {"mode": "INHERIT"},
                  "patientBookingMode": {"mode": "INHERIT"},
                  "provisionalCapacityMode": {"mode": "SET", "value": "HARD_HOLD"},
                  "provisionalRequestTtlSeconds": {"mode": "SET", "value": 86400},
                  "resourceHoldTtlSeconds": {"mode": "SET", "value": 900},
                  "approvalRoles": {"mode": "SET", "value": ["ADMIN", "STAFF"]},
                  "adminConsentEvidence": {
                    "mode": "SET",
                    "value": {
                      "allowedEvidenceTypes": ["SIGNED_FORM"],
                      "maximumAgeSeconds": 86400,
                      "termsHashRequired": true
                    }
                  },
                  "confirmedChangeMode": {"mode": "INHERIT"}
                }
                """.trimIndent(),
        )

        decoded shouldBeEqualTo BookingCommitmentOverride(
            adminBookingMode = OverrideValue.Inherit,
            patientBookingMode = OverrideValue.Inherit,
            provisionalCapacityMode = OverrideValue.Set(ProvisionalCapacityMode.HARD_HOLD),
            provisionalRequestTtlSeconds = OverrideValue.Set(86_400L),
            resourceHoldTtlSeconds = OverrideValue.Set(900L),
            approvalRoles = OverrideValue.Set(setOf(ActorRole.ADMIN, ActorRole.STAFF)),
            adminConsentEvidence = OverrideValue.Set(
                ConsentEvidenceRequirement(
                    allowedEvidenceTypes = setOf("SIGNED_FORM"),
                    maximumAge = Duration.ofHours(24),
                    termsHashRequired = true,
                ),
            ),
            confirmedChangeMode = OverrideValue.Inherit,
        )
    }

    @Test
    fun `rejects disabling a required clinic override value`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                CapacityAndOverbookingOverride(
                    nominalCapacity = OverrideValue.Disable,
                    overbookingQuota = OverrideValue.Inherit,
                    automaticReductionEnabled = OverrideValue.Inherit,
                ),
                PolicyScope.CLINIC_OVERRIDE,
            )
        }
    }

    @Test
    fun `decodes legacy notification policy without profile reevaluation targets`() {
        val decoded = SchedulingPolicyPayloadCodec().decode(
            kind = SchedulingPolicyKind.NOTIFICATION_AND_SLA,
            scope = PolicyScope.TENANT_DEFAULT,
            schemaVersion = 1,
            json =
                """
                {
                  "notificationChannels": ["SMS"],
                  "disruptionNoticeSeconds": 900,
                  "mandatoryResponseSeconds": 3600
                }
                """.trimIndent(),
        )

        decoded shouldBeEqualTo NotificationAndSlaPolicy(
            notificationChannels = setOf("SMS"),
            disruptionNoticeSeconds = 900L,
            mandatoryResponseSeconds = 3_600L,
            profileReevaluationHeldTargetSeconds = null,
            profileReevaluationProposedTargetSeconds = null,
        )
    }

    @Test
    fun `enforces profile reevaluation target ranges before activation`() {
        fun tenant(
            heldSeconds: Long? = 300L,
            proposedSeconds: Long? = 1_800L,
        ) = NotificationAndSlaPolicy(
            notificationChannels = setOf("SMS"),
            disruptionNoticeSeconds = 900L,
            mandatoryResponseSeconds = 3_600L,
            profileReevaluationHeldTargetSeconds = heldSeconds,
            profileReevaluationProposedTargetSeconds = proposedSeconds,
        )

        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                tenant(heldSeconds = 59L),
                PolicyScope.TENANT_DEFAULT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                tenant(heldSeconds = 901L),
                PolicyScope.TENANT_DEFAULT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                tenant(proposedSeconds = 299L),
                PolicyScope.TENANT_DEFAULT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                tenant(proposedSeconds = 7_201L),
                PolicyScope.TENANT_DEFAULT,
            )
        }
    }

    @Test
    fun `decodes missing clinic target fields as inherit and rejects disable`() {
        val decoded = SchedulingPolicyPayloadCodec().decode(
            kind = SchedulingPolicyKind.NOTIFICATION_AND_SLA,
            scope = PolicyScope.CLINIC_OVERRIDE,
            schemaVersion = 1,
            json =
                """
                {
                  "notificationChannels": {"mode": "INHERIT"},
                  "disruptionNoticeSeconds": {"mode": "INHERIT"}
                }
                """.trimIndent(),
        )

        decoded shouldBeEqualTo NotificationAndSlaOverride(
            notificationChannels = OverrideValue.Inherit,
            disruptionNoticeSeconds = OverrideValue.Inherit,
            profileReevaluationHeldTargetSeconds = OverrideValue.Inherit,
            profileReevaluationProposedTargetSeconds = OverrideValue.Inherit,
        )
        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyValidator.validatePayload(
                NotificationAndSlaOverride(
                    notificationChannels = OverrideValue.Inherit,
                    disruptionNoticeSeconds = OverrideValue.Inherit,
                    profileReevaluationHeldTargetSeconds = OverrideValue.Disable,
                ),
                PolicyScope.CLINIC_OVERRIDE,
            )
        }
    }

    private fun definition() = SchedulingPolicyDefinition(
        id = null,
        tenantGroupId = 1L,
        scope = PolicyScope.TENANT_DEFAULT,
        clinicId = null,
        kind = SchedulingPolicyKind.BOOKING_COMMITMENT,
        version = 1L,
        schemaVersion = 1,
        lifecycle = PolicyLifecycle.DRAFT,
        effectiveFrom = Instant.parse("2026-08-01T00:00:00Z"),
        effectiveUntil = Instant.parse("2027-08-01T00:00:00Z"),
        revision = 1L,
        payloadHash = "a".repeat(64),
        payload = bookingPolicy(ProvisionalCapacityMode.HARD_HOLD, Duration.ofMinutes(15)),
        createdBy = ActorAuditRef("admin-1", ActorRole.ADMIN),
        changeReason = "Introduce explicit administrator and patient booking rules",
    )

    private fun bookingPolicy(
        mode: ProvisionalCapacityMode,
        resourceHoldTtl: Duration?,
    ) = BookingCommitmentPolicy(
        adminBookingMode = AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
        patientBookingMode = PatientBookingMode.PROVISIONAL_APPROVAL_REQUIRED,
        provisionalCapacityMode = mode,
        provisionalRequestTtl = Duration.ofHours(24),
        resourceHoldTtl = resourceHoldTtl,
        approvalRoles = setOf(ActorRole.ADMIN, ActorRole.STAFF),
        adminConsentEvidence = ConsentEvidenceRequirement(
            allowedEvidenceTypes = setOf("SIGNED_FORM"),
            maximumAge = Duration.ofHours(24),
            termsHashRequired = true,
        ),
        confirmedChangeMode = ConfirmedChangeMode.NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
    )
}
