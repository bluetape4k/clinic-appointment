package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test

class KeysetPaginationTest {

    @Test
    fun `cursor는 clinic과 row id가 모두 양수여야 한다`() {
        ClinicKeysetCursor(clinicId = 10L, id = 101L)

        assertFailsWith<IllegalArgumentException> {
            ClinicKeysetCursor(clinicId = 0L, id = 101L)
        }
        assertFailsWith<IllegalArgumentException> {
            ClinicKeysetCursor(clinicId = 10L, id = 0L)
        }
    }

    @Test
    fun `bounded page는 content와 nullable next cursor를 보존한다`() {
        val cursor = ClinicKeysetCursor(clinicId = 10L, id = 101L)
        val page = ClinicKeysetPage(content = listOf("row-1"), nextCursor = cursor)

        page.content shouldBeEqualTo listOf("row-1")
        page.nextCursor shouldBeEqualTo cursor
        ClinicKeysetPage<String>(content = emptyList(), nextCursor = null).nextCursor.shouldBeNull()
    }
}
