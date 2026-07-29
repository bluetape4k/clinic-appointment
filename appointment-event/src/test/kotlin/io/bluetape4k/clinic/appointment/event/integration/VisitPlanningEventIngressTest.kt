package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.plan.ComponentVersionRef
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class VisitPlanningEventIngressTest {

    private val now = Instant.parse("2026-07-29T00:00:00Z")
    private val trustVerifier = SchedulingEventTrustVerifier(
        signatureVerifier = SchedulingEventSignatureVerifier { true },
        allowedProducers = setOf("commerce-service"),
        allowedKeyIds = setOf("commerce-key"),
        allowedAlgorithms = setOf("EdDSA"),
        expectedIssuer = "commerce-issuer",
        expectedAudience = "appointment-service",
        replayWindow = Duration.ofHours(1),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `raw payload에서 strict decoding한 실행 BOM만 trusted envelope로 승격한다`() {
        val payload = packageEvent()
        val ignoredPreMappedPayload = payload.copy(sourcePurchaseId = "must-not-be-used")
        val raw = envelope(payload = ignoredPreMappedPayload, payloadHash = PackageExecutionPayloadHasher.hash(payload))

        val trusted = ingress(payload).verify(raw, VALID_RAW_PAYLOAD)

        trusted.payload shouldBeEqualTo payload
        trusted.eventType shouldBeEqualTo "PackageExecutionPlanned"
    }

    @Test
    fun `허용되지 않은 event type과 schema를 mapping 전에 거부한다`() {
        val wrongType = envelope(eventType = "java.lang.Runtime")
        val wrongSchema = envelope(schemaVersion = 99)
        var decodingCount = 0
        val ingress = ingress {
            decodingCount += 1
            packageEvent()
        }

        assertFailsWith<SchedulingTrustException> {
            ingress.verify(wrongType, VALID_RAW_PAYLOAD)
        }.reasonCode shouldBeEqualTo "EVENT_TYPE_NOT_ALLOWED"
        assertFailsWith<SchedulingTrustException> {
            ingress.verify(wrongSchema, VALID_RAW_PAYLOAD)
        }.reasonCode shouldBeEqualTo "SCHEMA_VERSION_NOT_ALLOWED"
        decodingCount shouldBeEqualTo 0
    }

    @Test
    fun `1 MiB 초과 payload와 depth 32 초과 JSON을 mapping 전에 거부한다`() {
        val ingress = ingress(packageEvent())

        assertFailsWith<SchedulingTrustException> {
            ingress.verify(envelope(), ByteArray(1_048_577) { 'a'.code.toByte() })
        }.reasonCode shouldBeEqualTo "PAYLOAD_TOO_LARGE"

        val tooDeep = "[".repeat(33) + "0" + "]".repeat(33)
        assertFailsWith<SchedulingTrustException> {
            ingress.verify(envelope(), tooDeep.encodeToByteArray())
        }.reasonCode shouldBeEqualTo "PAYLOAD_DEPTH_EXCEEDED"
    }

    @Test
    fun `raw payload에서 mapping한 실행 BOM hash가 envelope와 다르면 거부한다`() {
        val raw = envelope().copy(payloadHash = "0".repeat(64))

        assertFailsWith<SchedulingTrustException> {
            ingress(packageEvent()).verify(raw, VALID_RAW_PAYLOAD)
        }.reasonCode shouldBeEqualTo "PAYLOAD_HASH_MISMATCH"
    }

    @Test
    fun `실행 BOM collection 상한은 canonical hash 계산 전에 거부한다`() {
        val oversized = packageEvent().let { event ->
            event.copy(
                executionSnapshot = event.executionSnapshot.copy(
                    selectedComponentVersions = List(1_001) {
                        ComponentVersionRef("laser", "laser-v1", quantity = 1)
                    },
                ),
            )
        }

        assertFailsWith<SchedulingTrustException> {
            ingress(oversized).verify(
                envelope(payload = oversized, payloadHash = "0".repeat(64)),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "PAYLOAD_CONTRACT_INVALID"
    }

    @Test
    fun `unknown field를 거부한 decoder 실패는 원문 없이 stable reason으로 수렴한다`() {
        val ingress = ingress {
            throw IllegalArgumentException("unknown field must remain private")
        }

        assertFailsWith<SchedulingTrustException> {
            ingress.verify(envelope(), """{"unknown":true}""".encodeToByteArray())
        }.reasonCode shouldBeEqualTo "PAYLOAD_MAPPING_FAILED"
    }

    @Test
    fun `닫힘 순서가 잘못된 JSON은 decoder 호출 전에 거부한다`() {
        var decodingCount = 0
        val ingress = ingress {
            decodingCount += 1
            packageEvent()
        }

        assertFailsWith<SchedulingTrustException> {
            ingress.verify(envelope(), "{]".encodeToByteArray())
        }.reasonCode shouldBeEqualTo "PAYLOAD_STRUCTURE_INVALID"
        decodingCount shouldBeEqualTo 0
    }

    private fun envelope(
        eventType: String = "PackageExecutionPlanned",
        schemaVersion: Int = 1,
        payload: PackageExecutionEvent = packageEvent(),
        payloadHash: String = PackageExecutionPayloadHasher.hash(payload),
    ) = UntrustedSchedulingEventEnvelope(
        eventId = "package-event-1",
        eventType = eventType,
        occurredAt = now.minusSeconds(10),
        receivedAt = now,
        producer = "commerce-service",
        issuer = "commerce-issuer",
        audience = "appointment-service",
        keyId = "commerce-key",
        algorithm = "EdDSA",
        schemaVersion = schemaVersion,
        correlationId = "correlation-1",
        payloadHash = payloadHash,
        signature = "signature",
        payload = payload,
    )

    private fun ingress(payload: PackageExecutionEvent): VisitPlanningEventIngress =
        ingress { payload }

    private fun ingress(
        decoder: PackageExecutionEventDecoder,
    ): VisitPlanningEventIngress =
        VisitPlanningEventIngress(
            trustVerifier = trustVerifier,
            payloadDecoder = decoder,
        )

    private fun packageEvent() = PackageExecutionEvent(
        sourceAggregateId = "purchase-aggregate",
        sourceAggregateVersion = 1,
        tenantGroupId = 1,
        clinicId = 10,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = "purchase-1",
        executionSnapshot = PackageExecutionSnapshot(
            packageProductId = "laser-package",
            packageProductVersionId = "laser-package-v1",
            selectedComponentVersions = listOf(
                ComponentVersionRef("laser", "laser-v1", quantity = 1),
            ),
            expandedTreatmentItems = listOf(
                ExecutionTreatment(
                    treatmentKey = "laser-1",
                    componentProductId = "laser",
                    componentProductVersionId = "laser-v1",
                    sourceBomItemId = "laser-bom",
                    sequence = 1,
                    representativeTreatmentName = "레이저 진료",
                    detailedTreatmentCodes = listOf("LASER"),
                    preparationMinutes = 10,
                    treatmentMinutes = 20,
                    recoveryMinutes = 10,
                    practitionerQualifications = listOf("DOCTOR"),
                    equipmentTypes = listOf("LASER"),
                    spaceCapabilities = listOf("LASER_ROOM"),
                ),
            ),
            executionDependencies = emptyList(),
            visitGroupingConstraints = emptyList(),
            snapshotHash = "a".repeat(64),
        ),
    )

    private companion object {
        val VALID_RAW_PAYLOAD = """{"sourceAggregateId":"purchase-aggregate"}""".encodeToByteArray()
    }
}
