package io.bluetape4k.clinic.appointment.model.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class TenantClinicScopeTest {

    @Test
    fun `scope requires positive tenant and clinic ids`() {
        assertFailsWith<IllegalArgumentException> { TenantClinicScope(0L, 7L) }
        assertFailsWith<IllegalArgumentException> { TenantClinicScope(3L, 0L) }
        TenantClinicScope(3L, 7L) shouldBeEqualTo TenantClinicScope(3L, 7L)
    }

    @Test
    fun `canonical cache key keeps tuple boundaries`() {
        TenantClinicScope(1L, 23L).cacheKey() shouldBeEqualTo "1:23"
        TenantClinicScope(12L, 3L).cacheKey() shouldBeEqualTo "12:3"
        (TenantClinicScope(1L, 23L).cacheKey() == TenantClinicScope(12L, 3L).cacheKey())
            .shouldBeEqualTo(false)
    }
}
