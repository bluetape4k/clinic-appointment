package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetCursor
import java.nio.charset.StandardCharsets
import java.util.Base64

/** clinic 목록 keyset 경계를 URL-safe no-padding Base64 opaque token으로 변환합니다. */
object ClinicKeysetCursorCodec {

    private const val VERSION = "v1"
    private const val MAX_TOKEN_LENGTH = 128
    private val BASE64URL = Regex("[A-Za-z0-9_-]+")
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()

    /** `v1:<clinicId>:<id>` payload를 opaque cursor로 인코딩합니다. */
    fun encode(cursor: ClinicKeysetCursor): String =
        ENCODER.encodeToString("$VERSION:${cursor.clinicId}:${cursor.id}".toByteArray(StandardCharsets.UTF_8))

    /** opaque cursor를 엄격히 검증하고 clinic keyset 경계로 디코딩합니다. */
    fun decode(token: String): ClinicKeysetCursor {
        require(token.length in 1..MAX_TOKEN_LENGTH && BASE64URL.matches(token)) {
            "cursor is malformed"
        }

        val decoded = try {
            DECODER.decode(token)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("cursor is malformed", failure)
        }
        val canonical = ENCODER.encodeToString(decoded)
        require(canonical == token) { "cursor is malformed" }

        val parts = String(decoded, StandardCharsets.UTF_8).split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "cursor is malformed" }
        val clinicId = parts[1].toLongOrNull() ?: throw IllegalArgumentException("cursor is malformed")
        val id = parts[2].toLongOrNull() ?: throw IllegalArgumentException("cursor is malformed")
        return try {
            ClinicKeysetCursor(clinicId = clinicId, id = id)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("cursor is malformed", failure)
        }
    }
}
