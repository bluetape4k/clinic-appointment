package io.bluetape4k.clinic.appointment.event

import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets

/**
 * appointment-event가 저장하는 JSON의 단일 strict/canonical mapper이다.
 *
 * `bluetape4k-jackson3`의 permissive 기본 mapper를 사용하지 않고, outbox 경계에서
 * 중복 key·trailing token·과도한 문서 구조를 먼저 차단한다. mapper는 thread-safe한
 * 불변 설정으로 codec 사이에서 공유한다.
 */
internal object AppointmentEventJson {

    internal const val MAX_DOCUMENT_BYTES = 64 * 1024
    private const val MAX_STRING_CHARS = 4 * 1024

    val mapper: JsonMapper = JsonMapper.builder(
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(32)
                    .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNameLength(MAX_STRING_CHARS)
                    .build(),
            )
            .build(),
    )
        .addModule(
            KotlinModule.Builder()
                .enable(KotlinFeature.StrictNullChecks)
                .build(),
        )
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    /** 직렬화 결과도 동일한 UTF-8 문서 상한을 적용해 read/write 경계를 대칭화한다. */
    fun writeCanonical(value: Any): String {
        val encoded = mapper.writeValueAsString(value)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "JSON document exceeds the bounded event payload size"
        }
        return encoded
    }

    /** Jackson의 source 단위와 무관하게 UTF-8 저장/전송 바이트 상한을 먼저 적용한다. */
    fun requireDocumentSize(json: String) {
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "JSON document exceeds the bounded event payload size"
        }
    }
}
