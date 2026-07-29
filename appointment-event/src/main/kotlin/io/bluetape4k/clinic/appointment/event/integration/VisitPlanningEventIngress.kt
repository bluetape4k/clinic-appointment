package io.bluetape4k.clinic.appointment.event.integration

/**
 * 고정된 `PackageExecutionPlanned` schema의 JSON payload만 역직렬화합니다.
 *
 * 구현체는 class name 기반 subtype, default polymorphic typing, unknown field를
 * 허용하지 않아야 합니다. 역직렬화 실패 상세나 raw payload를 로그에 남기지 않습니다.
 */
fun interface PackageExecutionEventDecoder {
    /**
     * 크기와 중첩 깊이 검사를 통과한 raw JSON을 허용된 DTO로 변환합니다.
     *
     * @param rawPayload 최대 1 MiB, JSON 중첩 깊이 32 이하임이 확인된 원문입니다.
     * @return schema version 1의 [PackageExecutionEvent]입니다.
     * @throws RuntimeException JSON 구조나 필드 계약이 허용된 schema와 다르면 발생합니다.
     */
    fun decode(rawPayload: ByteArray): PackageExecutionEvent
}

/**
 * 실행 BOM의 raw transport 경계와 trusted domain mapping 사이를 보호합니다.
 *
 * byte 크기, JSON 중첩 깊이, 고정 event type/schema를 역직렬화 전에 검사합니다.
 * [PackageExecutionEventDecoder]가 raw payload에서 만든 DTO만 canonical hash와
 * 서명 검증에 사용하므로, 별도로 매핑된 객체를 원문 검증 결과와 바꿔치기할 수 없습니다.
 */
class VisitPlanningEventIngress(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val payloadDecoder: PackageExecutionEventDecoder,
) {
    /**
     * 검증된 envelope만 반환합니다.
     *
     * [rawEnvelope]의 기존 payload 객체는 사용하지 않습니다. [rawPayload]에서 이 ingress의
     * strict decoder가 생성한 객체로 교체한 뒤 hash와 서명을 검증합니다.
     *
     * @param rawEnvelope payload 외의 transport metadata와 서명입니다.
     * @param rawPayload 아직 domain DTO로 역직렬화하지 않은 JSON bytes입니다.
     * @return 원문 bytes에서 생성되고 모든 trust gate를 통과한 실행 BOM envelope입니다.
     * @throws SchedulingTrustException payload가 1 MiB를 넘거나 중첩 깊이가 32를 넘거나,
     * 신뢰·schema·hash 검증이 실패하면 발생합니다.
     */
    fun verify(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
    ): TrustedSchedulingEventEnvelope<PackageExecutionEvent> {
        trust(rawPayload.size <= MAX_PAYLOAD_BYTES, "PAYLOAD_TOO_LARGE")
        trust(maxJsonDepth(rawPayload) <= MAX_JSON_DEPTH, "PAYLOAD_DEPTH_EXCEEDED")
        trust(rawEnvelope.eventType == PACKAGE_EXECUTION_EVENT_TYPE, "EVENT_TYPE_NOT_ALLOWED")
        trust(rawEnvelope.schemaVersion == PACKAGE_EXECUTION_SCHEMA_VERSION, "SCHEMA_VERSION_NOT_ALLOWED")
        validateRawMetadata(rawEnvelope)
        val payload = try {
            payloadDecoder.decode(rawPayload)
        } catch (_: Exception) {
            throw SchedulingTrustException("PAYLOAD_MAPPING_FAILED")
        }
        return trustVerifier.verifyPackageExecution(rawEnvelope.withPayload(payload))
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
        trust(envelope.signature.length <= MAX_SIGNATURE_LENGTH, "ENVELOPE_METADATA_INVALID")
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
                    '}' -> {
                        trust(openings.removeLastOrNull() == '{', "PAYLOAD_STRUCTURE_INVALID")
                    }
                    ']' -> {
                        trust(openings.removeLastOrNull() == '[', "PAYLOAD_STRUCTURE_INVALID")
                    }
                }
            }
        }
        trust(!insideString && openings.isEmpty(), "PAYLOAD_STRUCTURE_INVALID")
        return maximum
    }

    private fun UntrustedSchedulingEventEnvelope<*>.withPayload(
        payload: PackageExecutionEvent,
    ): UntrustedSchedulingEventEnvelope<PackageExecutionEvent> =
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
        const val PACKAGE_EXECUTION_EVENT_TYPE = "PackageExecutionPlanned"
        const val PACKAGE_EXECUTION_SCHEMA_VERSION = 1
        const val MAX_PAYLOAD_BYTES = 1_048_576
        const val MAX_JSON_DEPTH = 32
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_SIGNATURE_LENGTH = 1_024
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
