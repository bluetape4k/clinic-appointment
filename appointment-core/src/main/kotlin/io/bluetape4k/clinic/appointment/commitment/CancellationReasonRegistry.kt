package io.bluetape4k.clinic.appointment.commitment

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 예약 취소 reason code와 멱등성 canonical encoding을 소유하는 공통 계약이다.
 *
 * API와 event가 서로를 의존하지 않고 같은 등록 목록과 hash 입력을 사용하도록
 * appointment-core에 둔다. 목록에 없는 대문자 code도 허용하지 않는다.
 */
object CancellationReasonRegistry {
    const val CANONICAL_VERSION = "cancel-v1"
    const val MAX_DETAIL_LENGTH = 500

    val codes: Set<String> = setOf(
        "CUSTOMER_REQUEST",
        "REFUND",
        "EQUIPMENT_FAILURE",
        "CLINIC_REQUEST",
    )

    /** 등록된 취소 사유 code인지 검증하고 원래 값을 반환합니다. */
    fun requireCode(value: String): String {
        require(value in codes) { "reasonCode must be a registered cancellation reason code" }
        return value
    }

    /** 취소 상세 사유의 공백·길이·제어문자 계약을 검증합니다. */
    fun requireDetail(value: String?): String? {
        if (value == null) return null
        require(value.isNotBlank()) { "reasonDetail must not be blank" }
        require(value.length <= MAX_DETAIL_LENGTH) {
            "reasonDetail must not exceed $MAX_DETAIL_LENGTH characters"
        }
        require(value.none(Char::isISOControl)) {
            "reasonDetail must not contain control characters"
        }
        return value
    }

    /** `cancel-v1\0` + length-prefixed UTF-8 fields의 SHA-256 hex digest를 반환합니다. */
    fun canonicalHashHex(reasonCode: String, reasonDetail: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(reasonCode, reasonDetail))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * delimiter collision을 막기 위해 각 값의 UTF-8 byte length를 unsigned 32-bit
     * big-endian으로 앞에 붙인다. nullable detail은 `0xffffffff` length다.
     */
    fun canonicalBytes(reasonCode: String, reasonDetail: String?): ByteArray {
        val code = requireCode(reasonCode).toByteArray(StandardCharsets.UTF_8)
        val detail = requireDetail(reasonDetail)?.toByteArray(StandardCharsets.UTF_8)
        require(code.size <= Int.MAX_VALUE) { "reasonCode is too large" }
        require(detail == null || detail.size <= Int.MAX_VALUE) { "reasonDetail is too large" }

        val version = "$CANONICAL_VERSION\u0000".toByteArray(StandardCharsets.UTF_8)
        val capacity = version.size + 4 + code.size + 4 + (detail?.size ?: 0)
        return ByteBuffer.allocate(capacity).apply {
            put(version)
            putInt(code.size)
            put(code)
            putInt(detail?.size ?: -1)
            detail?.let(::put)
        }.array()
    }
}

/** 등록된 예약 취소 reason code다. */
@JvmInline
value class CancellationReasonCode(val value: String) : Serializable {
    init {
        CancellationReasonRegistry.requireCode(value)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
