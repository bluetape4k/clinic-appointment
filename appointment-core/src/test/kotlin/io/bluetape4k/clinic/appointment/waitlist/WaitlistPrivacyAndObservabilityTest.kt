package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistMetricsContract
import org.junit.jupiter.api.Test

class WaitlistPrivacyAndObservabilityTest {

    @Test
    fun `command boundary rejects profile shaped actor and correlation values`() {
        listOf(
            "alice@example.com",
            "010-1234-5678",
            "eyJhbGciOiJIUzI1NiJ9.payload.signature",
            "a".repeat(65),
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> { ActorRef(value) }
        }

        listOf(
            "alice@example.com",
            "010-1234-5678",
            "line\nbreak",
            "a".repeat(129),
            "raw;sql",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> { CorrelationId(value) }
        }

        MemberId("member-opaque-1").value shouldBeEqualTo "member-opaque-1"
    }

    @Test
    fun `actor and correlation values expose only bounded opaque forms`() {
        ActorRef("SYSTEM").value shouldBeEqualTo "SYSTEM"
        ActorRef("staff:operator-1").value shouldBeEqualTo "staff:operator-1"
        ActorRef("recovery:command-1").value shouldBeEqualTo "recovery:command-1"
        ActorRef("hmac:v1:" + "a".repeat(64)).value shouldBeEqualTo
            "hmac:v1:" + "a".repeat(64)
        CorrelationId("waitlist.claim-1").value shouldBeEqualTo "waitlist.claim-1"
    }

    @Test
    fun `metric contract has required names and rejects high cardinality labels`() {
        WaitlistMetricsContract.METER_NAMES shouldBeEqualTo setOf(
            "waitlist_offer_active",
            "waitlist_hold_active",
            "waitlist_claim_conflict_total",
            "waitlist_decision_unavailable_total",
            "waitlist_expiry_backlog",
            "waitlist_hold_reconcile_age_seconds",
        )
        val labels = WaitlistMetricsContract.labels(
            "tenant_partition" to "tenant-01",
            "clinic_partition" to "clinic-01",
            "reason" to WaitlistMetricsContract.reasonLabel(WaitlistReasonCode.slotOccupied),
        )
        labels.values["tenant_partition"] shouldBeEqualTo "tenant-01"

        listOf(
            "member_id" to "member-opaque-1",
            "correlation_id" to "waitlist.claim-1",
            "reason" to "raw-patient-token",
            "clinic_partition" to "clinic/01",
        ).forEach { label ->
            assertFailsWith<IllegalArgumentException> {
                WaitlistMetricsContract.labels(label)
            }
        }
    }
}
