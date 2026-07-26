package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class PurchasePlanMetricsContractTest {

    @Test
    fun `accepts only documented low cardinality label names and values`() {
        val labels = PurchasePlanMetricsContract.labels(
            "result" to "quarantined",
            "reason" to "catalog_retired",
            "mode" to "write",
            "eventType" to "PurchaseCompleted",
            "producerClass" to "commerce",
            "tenantPartition" to "tenant-0001",
            "clinicPartition" to "clinic-0001",
        )

        labels.values["result"] shouldBeEqualTo "quarantined"
        labels.values["reason"] shouldBeEqualTo "catalog_retired"
    }

    @Test
    fun `rejects high cardinality names and suspicious identifiers as metric labels`() {
        listOf(
            "eventId" to "event-1",
            "correlationId" to "correlation-1",
            "sourcePurchaseId" to "purchase-1",
            "planId" to "42",
            "patientReference" to "patient-token",
            "ciphertext" to "abc",
            "kid" to "key-1",
            "keyId" to "key-1",
        ).forEach { label ->
            assertFailsWith<IllegalArgumentException> {
                PurchasePlanMetricsContract.labels(label)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            PurchasePlanMetricsContract.labels("reason" to "raw-patient-token")
        }
        assertFailsWith<IllegalArgumentException> {
            PurchasePlanMetricsContract.labels("producerClass" to "commerce-service-with-a-very-specific-node-42")
        }
    }

    @Test
    fun `cardinality thresholds warn at 80 percent and high at 95 percent`() {
        PurchasePlanMetricsContract.cardinalitySignal(799) shouldBeEqualTo CardinalitySignal.OK
        PurchasePlanMetricsContract.cardinalitySignal(800) shouldBeEqualTo CardinalitySignal.WARNING
        PurchasePlanMetricsContract.cardinalitySignal(949) shouldBeEqualTo CardinalitySignal.WARNING
        PurchasePlanMetricsContract.cardinalitySignal(950) shouldBeEqualTo CardinalitySignal.HIGH

        assertFailsWith<IllegalArgumentException> {
            PurchasePlanMetricsContract.cardinalitySignal(-1)
        }
    }
}
