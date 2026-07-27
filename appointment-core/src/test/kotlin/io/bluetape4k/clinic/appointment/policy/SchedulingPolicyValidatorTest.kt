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
