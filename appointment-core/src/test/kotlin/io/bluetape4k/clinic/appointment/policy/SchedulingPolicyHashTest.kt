package io.bluetape4k.clinic.appointment.policy

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyValueSource
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.policy.SourceVersion
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyHasher
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class SchedulingPolicyHashTest {

    @Test
    fun `payload hash is independent of set and map insertion order`() {
        val first = TestPolicies.booking(
            approvalRoles = linkedSetOf(TestPolicies.staffRole, TestPolicies.adminRole),
            evidenceTypes = linkedSetOf("VERBAL_RECORDING", "SIGNED_FORM"),
        )
        val reordered = TestPolicies.booking(
            approvalRoles = linkedSetOf(TestPolicies.adminRole, TestPolicies.staffRole),
            evidenceTypes = linkedSetOf("SIGNED_FORM", "VERBAL_RECORDING"),
        )

        SchedulingPolicyHasher.payloadHash(first) shouldBeEqualTo
            SchedulingPolicyHasher.payloadHash(reordered)
    }

    @Test
    fun `reliability payload hash sorts objective weight keys`() {
        val first = PriorityAndReliabilityPolicy(
            priorityWeights = linkedMapOf("NO_SHOW_HISTORY" to 7, "RETURN_VISIT" to 3),
            noShowPenalty = 5,
            sameDayCancellationPenalty = 2,
            minimumPriorityScore = 0,
        )
        val reordered = first.copy(
            priorityWeights = linkedMapOf("RETURN_VISIT" to 3, "NO_SHOW_HISTORY" to 7),
        )

        SchedulingPolicyHasher.payloadHash(first) shouldBeEqualTo
            SchedulingPolicyHasher.payloadHash(reordered)
    }

    @Test
    fun `payload hashing rejects canonical input above the codec safety bound`() {
        val oversized = PriorityAndReliabilityPolicy(
            priorityWeights = (1..20_000).associate { "SIGNAL_$it" to it },
            noShowPenalty = 5,
            sameDayCancellationPenalty = 2,
            minimumPriorityScore = 0,
        )

        assertFailsWith<IllegalArgumentException> {
            SchedulingPolicyHasher.payloadHash(oversized)
        }
    }

    @Test
    fun `snapshot hash is independent of source map insertion order and includes evaluation times`() {
        val payload = TestPolicies.compiled()
        val firstSources = linkedMapOf(
            SchedulingPolicyKind.RECONFIRMATION to SourceVersion(4L, null),
            SchedulingPolicyKind.BOOKING_COMMITMENT to SourceVersion(3L, 2L),
        )
        val reorderedSources = linkedMapOf(
            SchedulingPolicyKind.BOOKING_COMMITMENT to SourceVersion(3L, 2L),
            SchedulingPolicyKind.RECONFIRMATION to SourceVersion(4L, null),
        )
        val firstPaths = linkedMapOf(
            "booking.patientBookingMode" to PolicyValueSource.TENANT,
            "booking.provisionalCapacityMode" to PolicyValueSource.CLINIC,
        )
        val reorderedPaths = linkedMapOf(
            "booking.provisionalCapacityMode" to PolicyValueSource.CLINIC,
            "booking.patientBookingMode" to PolicyValueSource.TENANT,
        )
        val decisionAt = Instant.parse("2026-08-01T00:00:00Z")
        val serviceAt = Instant.parse("2026-08-20T00:00:00Z")

        val first = SchedulingPolicyHasher.snapshotHash(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = PolicyGenerationVector(7L, 3L),
            sourceVersions = firstSources,
            sourceByPath = firstPaths,
            disabledFeatures = linkedSetOf("z", "a"),
            warnings = listOf("warning-b", "warning-a"),
            payload = payload,
        )
        val reordered = SchedulingPolicyHasher.snapshotHash(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = PolicyGenerationVector(7L, 3L),
            sourceVersions = reorderedSources,
            sourceByPath = reorderedPaths,
            disabledFeatures = linkedSetOf("a", "z"),
            warnings = listOf("warning-b", "warning-a"),
            payload = payload,
        )
        val differentDecisionTime = SchedulingPolicyHasher.snapshotHash(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = decisionAt.plusSeconds(1),
            serviceAt = serviceAt,
            generation = PolicyGenerationVector(7L, 3L),
            sourceVersions = firstSources,
            sourceByPath = firstPaths,
            disabledFeatures = linkedSetOf("z", "a"),
            warnings = listOf("warning-b", "warning-a"),
            payload = payload,
        )

        first shouldBeEqualTo reordered
        (first == differentDecisionTime) shouldBeEqualTo false
    }

    @Test
    fun `one thousand shuffled map and set inputs produce one snapshot hash`() {
        val random = Random(182)
        val decisionAt = Instant.parse("2026-08-01T00:00:00Z")
        val serviceAt = Instant.parse("2026-08-20T00:00:00Z")
        val sourceEntries = listOf(
            SchedulingPolicyKind.BOOKING_COMMITMENT to SourceVersion(3L, 2L),
            SchedulingPolicyKind.RECONFIRMATION to SourceVersion(4L, null),
        )
        val pathEntries = listOf(
            "booking.patientBookingMode" to PolicyValueSource.TENANT,
            "booking.provisionalCapacityMode" to PolicyValueSource.CLINIC,
        )

        fun shuffledHash(): String = SchedulingPolicyHasher.snapshotHash(
            tenantGroupId = 1L,
            clinicId = 2L,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = PolicyGenerationVector(7L, 3L),
            sourceVersions = sourceEntries.shuffled(random).toMap(LinkedHashMap()),
            sourceByPath = pathEntries.shuffled(random).toMap(LinkedHashMap()),
            disabledFeatures = linkedSetOf(*listOf("z", "a").shuffled(random).toTypedArray()),
            warnings = listOf("warning-b", "warning-a"),
            payload = TestPolicies.compiled(
                approvalRoles = listOf(TestPolicies.adminRole, TestPolicies.staffRole)
                    .shuffled(random)
                    .toCollection(LinkedHashSet()),
                evidenceTypes = listOf("SIGNED_FORM", "VERBAL_RECORDING")
                    .shuffled(random)
                    .toCollection(LinkedHashSet()),
            ),
        )

        val expected = shuffledHash()
        repeat(1_000) {
            shuffledHash() shouldBeEqualTo expected
        }
    }
}

private object TestPolicies {
    val adminRole = io.bluetape4k.clinic.appointment.model.policy.ActorRole.ADMIN
    val staffRole = io.bluetape4k.clinic.appointment.model.policy.ActorRole.STAFF

    fun booking(
        approvalRoles: Set<io.bluetape4k.clinic.appointment.model.policy.ActorRole>,
        evidenceTypes: Set<String>,
    ): BookingCommitmentPolicy =
        io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy(
            adminBookingMode =
                io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
                    .DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
            patientBookingMode =
                io.bluetape4k.clinic.appointment.model.policy.PatientBookingMode
                    .PROVISIONAL_APPROVAL_REQUIRED,
            provisionalCapacityMode =
                io.bluetape4k.clinic.appointment.model.policy.ProvisionalCapacityMode.NO_HOLD,
            provisionalRequestTtl = java.time.Duration.ofHours(24),
            resourceHoldTtl = null,
            approvalRoles = approvalRoles,
            adminConsentEvidence =
                io.bluetape4k.clinic.appointment.model.policy.ConsentEvidenceRequirement(
                    allowedEvidenceTypes = evidenceTypes,
                    maximumAge = java.time.Duration.ofHours(24),
                    termsHashRequired = true,
                ),
            confirmedChangeMode =
                io.bluetape4k.clinic.appointment.model.policy.ConfirmedChangeMode
                    .NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
        )

    fun compiled(
        approvalRoles: Set<io.bluetape4k.clinic.appointment.model.policy.ActorRole> =
            setOf(adminRole, staffRole),
        evidenceTypes: Set<String> = setOf("SIGNED_FORM"),
    ) =
        io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy(
            bookingCommitment = booking(
                approvalRoles = approvalRoles,
                evidenceTypes = evidenceTypes,
            ),
        )
}
