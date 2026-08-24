package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.dto.KeysetPageResponse
import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetCursor
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ClinicKeysetCursorCodecTest {

    @Test
    fun `cursor는 v1 payload를 URL-safe no-padding Base64로 round trip한다`() {
        val cursor = ClinicKeysetCursor(clinicId = 10L, id = 101L)

        ClinicKeysetCursorCodec.encode(cursor) shouldBeEqualTo "djE6MTA6MTAx"
        ClinicKeysetCursorCodec.decode("djE6MTA6MTAx") shouldBeEqualTo cursor
    }

    @Test
    fun `cursor codec은 version segment와 양수 식별자를 엄격히 검증한다`() {
        val malformed = listOf(
            "",
            "not-a-cursor",
            encode("v2:10:101"),
            encode("v1:0:101"),
            encode("v1:10:0"),
            encode("v1:10:-1"),
            encode("v1:10:101:extra"),
            "djE6MTA6MTAx=",
            "A".repeat(129),
        )

        malformed.forEach { token ->
            assertFailsWith<IllegalArgumentException> {
                ClinicKeysetCursorCodec.decode(token)
            }
        }
    }

    @Test
    fun `keyset page response는 items와 nullable nextCursor를 보존한다`() {
        val response = KeysetPageResponse(
            items = listOf(DoctorRecord(id = 101L, clinicId = 10L, name = "의사")),
            nextCursor = "djE6MTA6MTAx",
        )

        response.items.single().id shouldBeEqualTo 101L
        response.nextCursor shouldBeEqualTo "djE6MTA6MTAx"
        KeysetPageResponse<DoctorRecord>(items = emptyList()).nextCursor shouldBeEqualTo null
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
