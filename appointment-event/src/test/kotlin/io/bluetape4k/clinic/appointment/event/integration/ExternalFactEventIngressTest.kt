package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.plan.ComponentVersionRef
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.MigrationMappingType
import io.bluetape4k.clinic.appointment.model.plan.MigrationTarget
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ExternalFactEventIngressTest {

    private val now: Instant = Instant.parse("2026-07-29T12:00:00Z")
    private val signatureVerifier = RecordingSignatureVerifier()
    private val trustVerifier = SchedulingEventTrustVerifier(
        signatureVerifier = signatureVerifier,
        allowedProducers = setOf("product-service", "clinical-service"),
        allowedKeyIds = setOf("external-fact-key"),
        allowedAlgorithms = setOf("EdDSA"),
        expectedIssuer = "clinic-platform",
        expectedAudience = "appointment-service",
        replayWindow = Duration.ofHours(1),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `상품 전환 승인은 raw payload에서 strict decoding한 DTO만 trusted envelope로 승격한다`() {
        val decoded = migrationEvent()
        val ignoredPreMappedPayload = decoded.copy(sourcePurchaseId = "must-not-be-used")
        val raw = envelope(
            eventType = "ProductVersionMigrationApproved",
            producer = "product-service",
            payload = ignoredPreMappedPayload,
            payloadHash = ProductVersionMigrationPayloadHasher.hash(decoded),
        )

        val trusted = ingress(migration = decoded).verifyProductVersionMigration(raw, VALID_RAW_PAYLOAD)

        trusted.payload shouldBeEqualTo decoded
        trusted.payload.sourcePurchaseId shouldBeEqualTo "purchase-1"
    }

    @Test
    fun `외부 fact event type과 schema는 decoder 호출 전에 거부한다`() {
        var decodingCount = 0
        val ingress = ingress(
            migrationDecoder = ProductVersionMigrationApprovedEventDecoder {
                decodingCount += 1
                migrationEvent()
            },
        )

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(eventType = "java.lang.Runtime", payload = migrationEvent()),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "EVENT_TYPE_NOT_ALLOWED"

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(eventType = "ProductVersionMigrationApproved", schemaVersion = 99, payload = migrationEvent()),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "SCHEMA_VERSION_NOT_ALLOWED"

        decodingCount shouldBeEqualTo 0
    }

    @Test
    fun `1 MiB 초과 payload와 depth 32 초과 JSON은 decoder 호출 전에 거부한다`() {
        val ingress = ingress(migration = migrationEvent())

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(eventType = "ProductVersionMigrationApproved", payload = migrationEvent()),
                ByteArray(1_048_577) { 'a'.code.toByte() },
            )
        }.reasonCode shouldBeEqualTo "PAYLOAD_TOO_LARGE"

        val tooDeep = "[".repeat(33) + "0" + "]".repeat(33)
        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(eventType = "ProductVersionMigrationApproved", payload = migrationEvent()),
                tooDeep.encodeToByteArray(),
            )
        }.reasonCode shouldBeEqualTo "PAYLOAD_DEPTH_EXCEEDED"
    }

    @Test
    fun `payload mapping 실패는 원문 없이 fact별 stable reason으로 수렴한다`() {
        val ingress = ingress(
            migrationDecoder = ProductVersionMigrationApprovedEventDecoder {
                throw IllegalArgumentException("unknown field must remain private")
            },
        )

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(eventType = "ProductVersionMigrationApproved", payload = migrationEvent()),
                """{"unknown":true}""".encodeToByteArray(),
            )
        }.reasonCode shouldBeEqualTo "PRODUCT_MIGRATION_MAPPING_FAILED"
    }

    @Test
    fun `canonical hash mismatch와 signature 실패를 handler 전 단계에서 거부한다`() {
        val migration = migrationEvent()
        val ingress = ingress(migration = migration)

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(
                    eventType = "ProductVersionMigrationApproved",
                    payload = migration,
                    payloadHash = "0".repeat(64),
                ),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "PAYLOAD_HASH_MISMATCH"

        assertFailsWith<SchedulingTrustException> {
            ingress.verifyProductVersionMigration(
                envelope(
                    eventType = "ProductVersionMigrationApproved",
                    payload = migration,
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                    signature = "invalid-signature",
                ),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "SIGNATURE_INVALID"
    }

    @Test
    fun `일정 변경 거부와 진료 이행 fact도 각자의 event type과 canonical hash로 검증한다`() {
        val decline = declineEvent()
        val fulfillment = fulfillmentEvent()
        val trustedDecline = ingress(decline = decline).verifyMigrationRescheduleDeclined(
            envelope(
                eventType = "ProductVersionMigrationRescheduleDeclined",
                producer = "product-service",
                payload = decline,
                payloadHash = ProductVersionMigrationRescheduleDeclinedPayloadHasher.hash(decline),
            ),
            VALID_RAW_PAYLOAD,
        )
        val trustedFulfillment = ingress(fulfillment = fulfillment).verifyTreatmentFulfillment(
            envelope(
                eventType = "TreatmentFulfillmentRecorded",
                producer = "clinical-service",
                payload = fulfillment,
                payloadHash = TreatmentFulfillmentPayloadHasher.hash(fulfillment),
            ),
            VALID_RAW_PAYLOAD,
        )

        trustedDecline.payload.reasonCode shouldBeEqualTo "CUSTOMER_DECLINED_RESCHEDULE"
        trustedFulfillment.payload.facts.single().treatmentKey shouldBeEqualTo "future-old"
    }

    @Test
    fun `production strict decoder는 allowlist DTO만 만들고 unknown field와 class 정보를 거부한다`() {
        val decoder = StrictProductVersionMigrationRescheduleDeclinedEventDecoder()
        val validJson = """
            {
              "sourceAggregateId":"migration-decline-1",
              "sourceAggregateVersion":1,
              "tenantGroupId":1,
              "clinicId":10,
              "sourcePurchaseAuthority":"purchase-service",
              "sourcePurchaseId":"purchase-1",
              "migrationId":"migration-approval-1",
              "appointmentId":100,
              "reasonCode":"CUSTOMER_DECLINED_RESCHEDULE"
            }
        """.trimIndent().encodeToByteArray()

        decoder.decode(validJson).reasonCode shouldBeEqualTo "CUSTOMER_DECLINED_RESCHEDULE"

        assertFailsWith<IllegalArgumentException> {
            decoder.decode(
                validJson.decodeToString()
                    .replace("\"reasonCode\"", "\"unexpected\":true,\"reasonCode\"")
                    .encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decoder.decode(
                validJson.decodeToString()
                    .replaceFirst("{", """{"@class":"java.lang.Runtime",""")
                    .encodeToByteArray(),
            )
        }
    }

    @Test
    fun `production strict decoder는 allowlist 밖의 업무 reason을 생성하지 않는다`() {
        val raw = """
            {
              "sourceAggregateId":"migration-decline-1",
              "sourceAggregateVersion":1,
              "tenantGroupId":1,
              "clinicId":10,
              "sourcePurchaseAuthority":"purchase-service",
              "sourcePurchaseId":"purchase-1",
              "migrationId":"migration-approval-1",
              "appointmentId":100,
              "reasonCode":"CHANGE_CONFIRMED_APPOINTMENT"
            }
        """.trimIndent().encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            StrictProductVersionMigrationRescheduleDeclinedEventDecoder().decode(raw)
        }
    }

    @Test
    fun `진료 이행 시각은 replay window 안이며 envelope 발생 시각 이후일 수 없다`() {
        val futureFact = fulfillmentEvent().copy(
            facts = listOf(TreatmentFulfillmentFact.completed("future-old", now)),
        )
        val replayedFact = fulfillmentEvent().copy(
            facts = listOf(TreatmentFulfillmentFact.completed("future-old", now.minus(Duration.ofHours(2)))),
        )

        assertFailsWith<SchedulingTrustException> {
            ingress(fulfillment = futureFact).verifyTreatmentFulfillment(
                envelope(
                    eventType = "TreatmentFulfillmentRecorded",
                    producer = "clinical-service",
                    payload = futureFact,
                    payloadHash = TreatmentFulfillmentPayloadHasher.hash(futureFact),
                ),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "FACT_FROM_FUTURE"

        assertFailsWith<SchedulingTrustException> {
            ingress(fulfillment = replayedFact).verifyTreatmentFulfillment(
                envelope(
                    eventType = "TreatmentFulfillmentRecorded",
                    producer = "clinical-service",
                    payload = replayedFact,
                    payloadHash = TreatmentFulfillmentPayloadHasher.hash(replayedFact),
                ),
                VALID_RAW_PAYLOAD,
            )
        }.reasonCode shouldBeEqualTo "FACT_REPLAY_WINDOW_EXCEEDED"
    }

    private fun ingress(
        migration: ProductVersionMigrationApprovedEvent = migrationEvent(),
        decline: ProductVersionMigrationRescheduleDeclinedEvent = declineEvent(),
        fulfillment: TreatmentFulfillmentEvent = fulfillmentEvent(),
    ): ExternalFactEventIngress =
        ingress(
            migrationDecoder = ProductVersionMigrationApprovedEventDecoder { migration },
            declineDecoder = ProductVersionMigrationRescheduleDeclinedEventDecoder { decline },
            fulfillmentDecoder = TreatmentFulfillmentEventDecoder { fulfillment },
        )

    private fun ingress(
        migrationDecoder: ProductVersionMigrationApprovedEventDecoder = ProductVersionMigrationApprovedEventDecoder {
            migrationEvent()
        },
        declineDecoder: ProductVersionMigrationRescheduleDeclinedEventDecoder =
            ProductVersionMigrationRescheduleDeclinedEventDecoder { declineEvent() },
        fulfillmentDecoder: TreatmentFulfillmentEventDecoder = TreatmentFulfillmentEventDecoder { fulfillmentEvent() },
    ): ExternalFactEventIngress =
        ExternalFactEventIngress(
            trustVerifier = trustVerifier,
            migrationDecoder = migrationDecoder,
            declineDecoder = declineDecoder,
            fulfillmentDecoder = fulfillmentDecoder,
        )

    private fun <T> envelope(
        eventType: String,
        schemaVersion: Int = 1,
        producer: String = "product-service",
        payload: T,
        payloadHash: String = "a".repeat(64),
        signature: String = "valid-signature",
    ) = UntrustedSchedulingEventEnvelope(
        eventId = "external-fact-1",
        eventType = eventType,
        occurredAt = now.minusSeconds(10),
        receivedAt = now,
        producer = producer,
        issuer = "clinic-platform",
        audience = "appointment-service",
        keyId = "external-fact-key",
        algorithm = "EdDSA",
        schemaVersion = schemaVersion,
        correlationId = "correlation-1",
        payloadHash = payloadHash,
        signature = signature,
        payload = payload,
    )

    private fun migrationEvent(): ProductVersionMigrationApprovedEvent {
        val mappings = listOf(
            MigrationMapping(
                type = MigrationMappingType.REPLACE,
                sourceTreatmentKeys = setOf("future-old"),
                targets = listOf(MigrationTarget("future-new")),
            ),
        )
        val mappingHash = ProductVersionMigrationPayloadHasher.mappingHash(mappings)
        return ProductVersionMigrationApprovedEvent(
            sourceAggregateId = "migration-1",
            sourceAggregateVersion = 1,
            tenantGroupId = 1,
            clinicId = 10,
            sourcePurchaseAuthority = "purchase-service",
            sourcePurchaseId = "purchase-1",
            migrationId = "migration-approval-1",
            fromProductVersionId = "product-v1",
            toProductVersionId = "product-v2",
            mappings = mappings,
            mappingHash = mappingHash,
            consent = ProductVersionMigrationConsentEvidence(
                migrationId = "migration-approval-1",
                fromProductVersionId = "product-v1",
                toProductVersionId = "product-v2",
                mappingHash = mappingHash,
                consentedAt = now.minusSeconds(60),
                evidenceType = ProductVersionMigrationConsentEvidenceType.DIGITAL_SIGNATURE,
                evidenceReferenceHash = "b".repeat(64),
            ),
            targetExecutionSnapshot = targetSnapshot(),
        )
    }

    private fun declineEvent() = ProductVersionMigrationRescheduleDeclinedEvent(
        sourceAggregateId = "migration-decline-1",
        sourceAggregateVersion = 1,
        tenantGroupId = 1,
        clinicId = 10,
        sourcePurchaseAuthority = "purchase-service",
        sourcePurchaseId = "purchase-1",
        migrationId = "migration-approval-1",
        appointmentId = 100,
        reasonCode = "CUSTOMER_DECLINED_RESCHEDULE",
    )

    private fun fulfillmentEvent() = TreatmentFulfillmentEvent(
        sourceAggregateId = "fulfillment-1",
        sourceAggregateVersion = 1,
        tenantGroupId = 1,
        clinicId = 10,
        sourcePurchaseAuthority = "purchase-service",
        sourcePurchaseId = "purchase-1",
        facts = listOf(TreatmentFulfillmentFact.completed("future-old", now.minusSeconds(30))),
    )

    private fun targetSnapshot(): PackageExecutionSnapshot =
        PackageExecutionSnapshot(
            packageProductId = "package-product",
            packageProductVersionId = "product-v2",
            selectedComponentVersions = listOf(ComponentVersionRef("component", "component-v2")),
            expandedTreatmentItems = listOf(executionTreatment("future-new")),
            executionDependencies = emptyList(),
            visitGroupingConstraints = emptyList(),
            snapshotHash = "2".repeat(64),
        )

    private fun executionTreatment(treatmentKey: String): ExecutionTreatment =
        ExecutionTreatment(
            treatmentKey = treatmentKey,
            componentProductId = "component",
            componentProductVersionId = "component-v2",
            sourceBomItemId = "bom-$treatmentKey",
            sequence = 1,
            representativeTreatmentName = "대표 진료",
            detailedTreatmentCodes = listOf("CODE-A"),
            preparationMinutes = 5,
            treatmentMinutes = 20,
            recoveryMinutes = 5,
            practitionerQualifications = listOf("DOCTOR"),
            equipmentTypes = listOf("LASER"),
            spaceCapabilities = listOf("ROOM"),
        )

    private class RecordingSignatureVerifier : SchedulingEventSignatureVerifier {
        var calls: Int = 0

        override fun verify(envelope: UntrustedSchedulingEventEnvelope<*>): Boolean {
            calls += 1
            return envelope.signature == "valid-signature"
        }
    }

    private companion object {
        val VALID_RAW_PAYLOAD = """{"sourceAggregateId":"external-fact"}""".encodeToByteArray()
    }
}
