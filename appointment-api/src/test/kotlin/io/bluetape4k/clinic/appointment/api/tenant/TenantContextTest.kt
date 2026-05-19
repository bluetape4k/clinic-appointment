package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class TenantContextTest {

    private val tenant = TenantInfo(
        id = 1L,
        tenantCode = "tenant-a",
        displayName = "Tenant A",
    )

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `withTenant installs and restores tenant`() {
        TenantContext.current().shouldBeNull()

        TenantContext.withTenant(tenant) {
            TenantContext.requireCurrent() shouldBeEqualTo tenant
        }

        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `requireCurrent fails when tenant is missing`() {
        assertFailsWith<IllegalStateException> {
            TenantContext.requireCurrent()
        }
    }

    @Test
    fun `context element propagates tenant across coroutine dispatcher`() = runBlocking(TenantContext.asContextElement(tenant)) {
        TenantContext.requireCurrent() shouldBeEqualTo tenant

        withContext(Dispatchers.Default) {
            TenantContext.requireCurrent() shouldBeEqualTo tenant
        }
    }
}
