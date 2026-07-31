package io.bluetape4k.clinic.appointment.model.identity

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class MemberIdTest {
    @Test
    fun `공백 member id는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            MemberId(" ")
        }
    }

    @Test
    fun `member id 원문을 그대로 보존한다`() {
        MemberId("member_01JZ8A").value shouldBeEqualTo "member_01JZ8A"
    }
}
