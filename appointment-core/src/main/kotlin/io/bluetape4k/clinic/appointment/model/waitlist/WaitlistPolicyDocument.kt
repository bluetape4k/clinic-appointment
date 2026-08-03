package io.bluetape4k.clinic.appointment.model.waitlist

import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 자동 대기 offer 후보 선정을 위한 닫힌 waitlist policy 문서입니다.
 *
 * 모든 가중치는 정수로만 저장한다. 부동소수점 합산은 재생 가능한 결정 audit을 약화시키므로
 * codec은 의미상 정수인 JSON 숫자만 canonical 정수로 받아들인다.
 */
data class WaitlistPolicyDocument(
    val urgencyWeight: Int,
    val recoveryWeight: Int,
    val benefitWeight: Int,
    val reliabilityWeight: Int,
    val waitingAgeWeight: Int,
    val slotFitWeight: Int,
) : Serializable {
    init {
        listOf(
            urgencyWeight,
            recoveryWeight,
            benefitWeight,
            reliabilityWeight,
            waitingAgeWeight,
            slotFitWeight,
        ).forEach { weight ->
            require(weight in MIN_WEIGHT..MAX_WEIGHT) {
                "waitlist policy weight must be between $MIN_WEIGHT and $MAX_WEIGHT"
            }
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        const val MIN_WEIGHT: Int = 0
        const val MAX_WEIGHT: Int = 10_000

        fun canonicalJson(document: WaitlistPolicyDocument): String =
            mapper.writeValueAsString(document)

        fun canonicalDigest(document: WaitlistPolicyDocument): String =
            sha256(canonicalJson(document))
    }
}

/** strict codec이 검증한 canonical waitlist policy payload입니다. */
data class DecodedWaitlistPolicyDocument(
    val document: WaitlistPolicyDocument,
    val canonicalJson: String,
    val digest: String,
) : Serializable {
    init {
        require(canonicalJson.toByteArray(StandardCharsets.UTF_8).size <= WaitlistPolicyDocumentCodec.MAX_PAYLOAD_BYTES) {
            "canonical waitlist policy must not exceed ${WaitlistPolicyDocumentCodec.MAX_PAYLOAD_BYTES} UTF-8 bytes"
        }
        require(SHA256_REGEX.matches(digest)) { "digest must be lowercase SHA-256" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * waitlist policy JSON을 신뢰 가능한 concrete schema로만 decode하는 codec입니다.
 *
 * 이 codec은 default typing을 켜지 않고, unknown field, duplicate key, depth 9 이상, 64 KiB
 * 초과 payload, Jackson `@class` metadata, enum-like `mode` injection을 모두 거부한다.
 */
class WaitlistPolicyDocumentCodec(
    private val objectMapper: JsonMapper = mapper,
) {
    companion object {
        const val MAX_PAYLOAD_BYTES: Int = 64 * 1024
        const val MAX_NESTING_DEPTH: Int = 8
    }

    fun decode(json: String): DecodedWaitlistPolicyDocument {
        val rawBytes = json.toByteArray(StandardCharsets.UTF_8)
        if (rawBytes.size > MAX_PAYLOAD_BYTES) {
            throw WaitlistPolicyValidationException("waitlist policy must not exceed $MAX_PAYLOAD_BYTES UTF-8 bytes")
        }
        return try {
            validateDepth(rawBytes)
            val node = objectMapper.readTree(rawBytes)
            val document = readDocument(node)
            val canonicalJson = WaitlistPolicyDocument.canonicalJson(document)
            DecodedWaitlistPolicyDocument(
                document = document,
                canonicalJson = canonicalJson,
                digest = WaitlistPolicyDocument.canonicalDigest(document),
            )
        } catch (failure: WaitlistPolicyValidationException) {
            throw failure
        } catch (failure: Exception) {
            throw WaitlistPolicyValidationException("invalid waitlist policy document", failure)
        }
    }

    private fun validateDepth(rawBytes: ByteArray) {
        var depth = 0
        objectMapper.createParser(rawBytes).use { parser ->
            while (parser.nextToken() != null) {
                when (parser.currentToken()) {
                    JsonToken.START_OBJECT,
                    JsonToken.START_ARRAY,
                        -> {
                        depth += 1
                        if (depth > MAX_NESTING_DEPTH) {
                            throw WaitlistPolicyValidationException(
                                "waitlist policy nesting depth must not exceed $MAX_NESTING_DEPTH",
                            )
                        }
                    }
                    JsonToken.END_OBJECT,
                    JsonToken.END_ARRAY,
                        -> depth -= 1
                    else -> Unit
                }
            }
        }
    }

    private fun readDocument(node: JsonNode): WaitlistPolicyDocument {
        if (!node.isObject) {
            throw WaitlistPolicyValidationException("waitlist policy root must be an object")
        }
        val fieldNames = node.propertyStream().map { it.key }.toList()
        val unknownFields = fieldNames - allowedFields
        if (unknownFields.isNotEmpty()) {
            throw WaitlistPolicyValidationException(
                "unknown waitlist policy fields: ${unknownFields.sorted().joinToString(",")}",
            )
        }
        val missingFields = allowedFields - fieldNames.toSet()
        if (missingFields.isNotEmpty()) {
            throw WaitlistPolicyValidationException(
                "missing waitlist policy fields: ${missingFields.sorted().joinToString(",")}",
            )
        }
        return WaitlistPolicyDocument(
            urgencyWeight = node.requiredWeight("urgencyWeight"),
            recoveryWeight = node.requiredWeight("recoveryWeight"),
            benefitWeight = node.requiredWeight("benefitWeight"),
            reliabilityWeight = node.requiredWeight("reliabilityWeight"),
            waitingAgeWeight = node.requiredWeight("waitingAgeWeight"),
            slotFitWeight = node.requiredWeight("slotFitWeight"),
        )
    }

    private fun JsonNode.requiredWeight(fieldName: String): Int {
        val value = get(fieldName)
        if (value == null || !value.isNumber) {
            throw WaitlistPolicyValidationException("$fieldName must be an integer")
        }
        if (value.canConvertToExactIntegral()) {
            val weight = value.asInt()
            if (weight in WaitlistPolicyDocument.MIN_WEIGHT..WaitlistPolicyDocument.MAX_WEIGHT) {
                return weight
            }
        }
        throw WaitlistPolicyValidationException(
            "$fieldName must be between ${WaitlistPolicyDocument.MIN_WEIGHT} and ${WaitlistPolicyDocument.MAX_WEIGHT}",
        )
    }
}

/** waitlist policy payload가 닫힌 schema와 안전 한계를 통과하지 못했습니다. */
class WaitlistPolicyValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private val allowedFields = setOf(
    "urgencyWeight",
    "recoveryWeight",
    "benefitWeight",
    "reliabilityWeight",
    "waitingAgeWeight",
    "slotFitWeight",
)

private val mapper: JsonMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    .build()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
