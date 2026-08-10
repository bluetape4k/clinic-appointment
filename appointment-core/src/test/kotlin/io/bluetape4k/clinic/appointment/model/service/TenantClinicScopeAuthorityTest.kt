package io.bluetape4k.clinic.appointment.model.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class TenantClinicScopeAuthorityTest {

    @Test
    fun `verified scope resolver rejects a valid but unrelated tenant clinic pair`() {
        val resolver = TenantClinicScopeResolver { tenantGroupId, clinicId ->
            tenantGroupId == 7L && clinicId == 31L
        }

        resolver.resolve(7L, 31L).shouldNotBeNull().toScope().cacheKey() shouldBeEqualTo "7:31"
        resolver.resolve(7L, 32L).shouldBeNull()
    }

    @Test
    fun `verified scope cannot be constructed through a public constructor`() {
        VerifiedTenantClinicScope::class.isSealed.shouldBeTrue()
        VerifiedTenantClinicScope::class.java.declaredConstructors
            .filter { it.parameterCount == 2 }
            .none { Modifier.isPublic(it.modifiers) }
            .shouldBeTrue()
    }
}
