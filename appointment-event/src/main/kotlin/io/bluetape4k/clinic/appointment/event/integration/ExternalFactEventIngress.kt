package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.service.StrictJsonPayloadDecoder

/**
 * 상품 version 전환 승인 JSON payload만 역직렬화합니다.
 *
 * 구현체는 class name 기반 subtype, default polymorphic typing, unknown field를
 * 허용하지 않아야 합니다. 실패 상세나 raw payload는 로그·예외 메시지에 포함하지
 * 않습니다.
 */
fun interface ProductVersionMigrationApprovedEventDecoder {
    /**
     * 크기와 중첩 깊이 검사를 통과한 raw JSON을 schema version 1 DTO로 변환합니다.
     *
     * @throws RuntimeException JSON 구조나 필드 계약이 허용된 schema와 다르면 발생합니다.
     */
    fun decode(rawPayload: ByteArray): ProductVersionMigrationApprovedEvent
}

/**
 * 고객 일정 변경 거부 JSON payload만 역직렬화합니다.
 *
 * 이 payload는 확정 예약 변경 command가 아니라 운영 예외·CRM handoff를 만드는 외부
 * 사실입니다. decoder는 `CUSTOMER_DECLINED_RESCHEDULE` 이외 reason을 만들 수 없어야 합니다.
 */
fun interface ProductVersionMigrationRescheduleDeclinedEventDecoder {
    /**
     * 크기와 중첩 깊이 검사를 통과한 raw JSON을 schema version 1 DTO로 변환합니다.
     *
     * @throws RuntimeException JSON 구조나 필드 계약이 허용된 schema와 다르면 발생합니다.
     */
    fun decode(rawPayload: ByteArray): ProductVersionMigrationRescheduleDeclinedEvent
}

/**
 * 진료 이행 fact JSON payload만 역직렬화합니다.
 *
 * 구현체는 완료·부분 이행·자원 장애·환불의 schema를 고정해야 하며, producer가 제공하지
 * 않은 잔여 진료 정의를 예약서비스에서 추론하지 않도록 unknown/default field를 금지합니다.
 */
fun interface TreatmentFulfillmentEventDecoder {
    /**
     * 크기와 중첩 깊이 검사를 통과한 raw JSON을 schema version 1 DTO로 변환합니다.
     *
     * @throws RuntimeException JSON 구조나 필드 계약이 허용된 schema와 다르면 발생합니다.
     */
    fun decode(rawPayload: ByteArray): TreatmentFulfillmentEvent
}

/**
 * 상품 version 전환 승인 schema 1의 production strict decoder입니다.
 */
class StrictProductVersionMigrationApprovedEventDecoder(
    private val decoder: StrictJsonPayloadDecoder = StrictJsonPayloadDecoder(),
) : ProductVersionMigrationApprovedEventDecoder {
    override fun decode(rawPayload: ByteArray): ProductVersionMigrationApprovedEvent =
        decoder.decode(rawPayload, ProductVersionMigrationApprovedEvent::class.java)
}

/**
 * 상품 전환 뒤 고객 일정 거부 schema 1의 production strict decoder입니다.
 */
class StrictProductVersionMigrationRescheduleDeclinedEventDecoder(
    private val decoder: StrictJsonPayloadDecoder = StrictJsonPayloadDecoder(),
) : ProductVersionMigrationRescheduleDeclinedEventDecoder {
    override fun decode(rawPayload: ByteArray): ProductVersionMigrationRescheduleDeclinedEvent =
        decoder.decode(rawPayload, ProductVersionMigrationRescheduleDeclinedEvent::class.java)
}

/**
 * 완료·부분 이행·자원 장애·환불 schema 1의 production strict decoder입니다.
 */
class StrictTreatmentFulfillmentEventDecoder(
    private val decoder: StrictJsonPayloadDecoder = StrictJsonPayloadDecoder(),
) : TreatmentFulfillmentEventDecoder {
    override fun decode(rawPayload: ByteArray): TreatmentFulfillmentEvent =
        decoder.decode(rawPayload, TreatmentFulfillmentEvent::class.java)
}

/**
 * 외부 fact event의 raw transport 경계와 trusted handler 경계 사이를 보호합니다.
 *
 * 상품서비스, 임상 실행 서비스, 환불 서비스가 발행한 사실은 예약서비스의 미래 Plan
 * revision과 재예약 dirty-set을 바꿀 수 있습니다. 따라서 raw JSON을 바로 handler에
 * 넘기지 않고, byte 크기, JSON 중첩 깊이, 고정 event type/schema, metadata 모양,
 * canonical hash, producer/key/issuer/audience/algorithm/replay/signature를 모두 통과한
 * envelope만 [TrustedSchedulingEventEnvelope]로 승격합니다.
 */
class ExternalFactEventIngress(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val migrationDecoder: ProductVersionMigrationApprovedEventDecoder,
    private val declineDecoder: ProductVersionMigrationRescheduleDeclinedEventDecoder,
    private val fulfillmentDecoder: TreatmentFulfillmentEventDecoder,
) {
    /**
     * 상품 version 전환 승인 raw payload를 trusted envelope로 승격합니다.
     *
     * [rawEnvelope]의 기존 payload 객체는 사용하지 않습니다. [rawPayload]에서 strict decoder가
     * 생성한 객체로 교체한 뒤 hash와 서명을 검증합니다.
     */
    fun verifyProductVersionMigration(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
    ): TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent> =
        verify(
            rawEnvelope = rawEnvelope,
            rawPayload = rawPayload,
            expectedEventType = PRODUCT_VERSION_MIGRATION_APPROVED_EVENT_TYPE,
            expectedSchemaVersion = SCHEMA_VERSION,
            reasonOnMappingFailure = "PRODUCT_MIGRATION_MAPPING_FAILED",
            decoder = migrationDecoder::decode,
            trust = trustVerifier::verifyProductVersionMigration,
        )

    /**
     * 고객 일정 변경 거부 raw payload를 trusted envelope로 승격합니다.
     *
     * 신뢰 검증에 실패하면 기존 확정 예약을 바꾸지 않고 stable reason code로 거부됩니다.
     */
    fun verifyMigrationRescheduleDeclined(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
    ): TrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent> =
        verify(
            rawEnvelope = rawEnvelope,
            rawPayload = rawPayload,
            expectedEventType = PRODUCT_VERSION_MIGRATION_DECLINED_EVENT_TYPE,
            expectedSchemaVersion = SCHEMA_VERSION,
            reasonOnMappingFailure = "PRODUCT_MIGRATION_DECLINE_MAPPING_FAILED",
            decoder = declineDecoder::decode,
            trust = trustVerifier::verifyMigrationRescheduleDeclined,
        )

    /**
     * 진료 완료·부분 이행·자원 장애·환불 raw payload를 trusted envelope로 승격합니다.
     *
     * handler는 이 결과만 받아야 completed provenance와 미래 예약 재계산을 분리할 수 있습니다.
     */
    fun verifyTreatmentFulfillment(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
    ): TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent> =
        verify(
            rawEnvelope = rawEnvelope,
            rawPayload = rawPayload,
            expectedEventType = TREATMENT_FULFILLMENT_RECORDED_EVENT_TYPE,
            expectedSchemaVersion = SCHEMA_VERSION,
            reasonOnMappingFailure = "TREATMENT_FULFILLMENT_MAPPING_FAILED",
            decoder = fulfillmentDecoder::decode,
            trust = trustVerifier::verifyTreatmentFulfillment,
        )

    private fun <T> verify(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        expectedEventType: String,
        expectedSchemaVersion: Int,
        reasonOnMappingFailure: String,
        decoder: (ByteArray) -> T,
        trust: (UntrustedSchedulingEventEnvelope<T>) -> TrustedSchedulingEventEnvelope<T>,
    ): TrustedSchedulingEventEnvelope<T> {
        trust(rawPayload.size <= MAX_PAYLOAD_BYTES, "PAYLOAD_TOO_LARGE")
        trust(maxJsonDepth(rawPayload) <= MAX_JSON_DEPTH, "PAYLOAD_DEPTH_EXCEEDED")
        trust(rawEnvelope.eventType == expectedEventType, "EVENT_TYPE_NOT_ALLOWED")
        trust(rawEnvelope.schemaVersion == expectedSchemaVersion, "SCHEMA_VERSION_NOT_ALLOWED")
        validateRawMetadata(rawEnvelope)
        val payload = try {
            decoder(rawPayload)
        } catch (_: Exception) {
            throw SchedulingTrustException(reasonOnMappingFailure)
        }
        return trust(rawEnvelope.withPayload(payload))
    }

    private fun validateRawMetadata(envelope: UntrustedSchedulingEventEnvelope<*>) {
        val identifiers = listOf(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
        )
        trust(
            identifiers.all { it.length in 1..MAX_IDENTIFIER_LENGTH && IDENTIFIER.matches(it) },
            "ENVELOPE_METADATA_INVALID",
        )
        trust(SHA256.matches(envelope.payloadHash), "ENVELOPE_METADATA_INVALID")
        trust(envelope.signature.length in 1..MAX_SIGNATURE_LENGTH, "ENVELOPE_METADATA_INVALID")
    }

    private fun maxJsonDepth(payload: ByteArray): Int {
        val openings = ArrayDeque<Char>()
        var maximum = 0
        var insideString = false
        var escaped = false
        payload.forEach { byte ->
            val character = byte.toInt().toChar()
            if (insideString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                }
            } else {
                when (character) {
                    '"' -> insideString = true
                    '{', '[' -> {
                        openings.addLast(character)
                        maximum = maxOf(maximum, openings.size)
                    }
                    '}' -> trust(openings.removeLastOrNull() == '{', "PAYLOAD_STRUCTURE_INVALID")
                    ']' -> trust(openings.removeLastOrNull() == '[', "PAYLOAD_STRUCTURE_INVALID")
                }
            }
        }
        trust(!insideString && openings.isEmpty(), "PAYLOAD_STRUCTURE_INVALID")
        return maximum
    }

    private fun <T> UntrustedSchedulingEventEnvelope<*>.withPayload(payload: T): UntrustedSchedulingEventEnvelope<T> =
        UntrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = eventType,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            producer = producer,
            issuer = issuer,
            audience = audience,
            keyId = keyId,
            algorithm = algorithm,
            schemaVersion = schemaVersion,
            correlationId = correlationId,
            payloadHash = payloadHash,
            signature = signature,
            payload = payload,
        )

    private fun trust(condition: Boolean, reasonCode: String) {
        if (!condition) throw SchedulingTrustException(reasonCode)
    }

    private companion object {
        const val PRODUCT_VERSION_MIGRATION_APPROVED_EVENT_TYPE = "ProductVersionMigrationApproved"
        const val PRODUCT_VERSION_MIGRATION_DECLINED_EVENT_TYPE = "ProductVersionMigrationRescheduleDeclined"
        const val TREATMENT_FULFILLMENT_RECORDED_EVENT_TYPE = "TreatmentFulfillmentRecorded"
        const val SCHEMA_VERSION = 1
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_JSON_DEPTH = 32
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_SIGNATURE_LENGTH = 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
