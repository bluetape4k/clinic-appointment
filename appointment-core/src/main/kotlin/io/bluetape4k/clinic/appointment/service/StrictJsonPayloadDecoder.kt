package io.bluetape4k.clinic.appointment.service

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * 외부 event의 allowlisted concrete DTO만 strict JSON으로 역직렬화합니다.
 *
 * mapper는 Kotlin constructor를 사용하고 unknown field를 거부하며 default polymorphic
 * typing을 활성화하지 않습니다. caller가 trusted envelope의 `(eventType,
 * schemaVersion)`으로 선택한 concrete `targetType`만 허용하므로 JSON의 class name이나
 * 임의 subtype discriminator가 dispatch에 관여할 수 없습니다.
 *
 * 이 codec은 크기·중첩 깊이·서명·payload hash를 검증하지 않습니다. 해당 검증은 raw
 * ingress가 역직렬화 전후에 수행해야 합니다.
 */
class StrictJsonPayloadDecoder(
    private val objectMapper: JsonMapper = JsonMapper.builder()
        .findAndAddModules()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build(),
) {
    /**
     * UTF-8 JSON bytes를 caller가 고정한 concrete type으로 변환합니다.
     *
     * @param rawPayload raw ingress의 크기·깊이 검사를 통과한 JSON bytes입니다.
     * @param targetType event type/schema allowlist가 선택한 concrete DTO class입니다.
     * @throws IllegalArgumentException malformed JSON, unknown/missing field, type mismatch,
     * constructor invariant 실패가 있으면 raw 원문을 노출하지 않고 발생합니다.
     */
    fun <T : Any> decode(
        rawPayload: ByteArray,
        targetType: Class<T>,
    ): T =
        try {
            objectMapper.readValue(rawPayload, targetType)
        } catch (failure: Exception) {
            throw IllegalArgumentException(
                "payload does not match the allowlisted ${targetType.simpleName} schema",
                failure,
            )
        }
}
