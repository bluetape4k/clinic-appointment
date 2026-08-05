package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

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
    fun `context element propagates tenant across coroutine dispatcher`() = runTest(TenantContext.asContextElement(tenant)) {
        TenantContext.requireCurrent() shouldBeEqualTo tenant

        withContext(Dispatchers.Default) {
            TenantContext.requireCurrent() shouldBeEqualTo tenant
        }
    }

    @Test
    fun `nested scopes and throwing blocks restore the previous tenant`() = runTest {
        val other = tenant.copy(id = 2L, tenantCode = "tenant-b")

        TenantContext.withTenant(tenant) {
            TenantContext.withTenant(other) {
                TenantContext.requireCurrent() shouldBeEqualTo other
            }
            TenantContext.requireCurrent() shouldBeEqualTo tenant

            assertFailsWith<IllegalStateException> {
                TenantContext.withTenant(other) {
                    error("scope failure")
                }
            }
            TenantContext.requireCurrent() shouldBeEqualTo tenant
        }
        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `parallel dispatcher children restore their own tenant state`() = runTest {
        val other = tenant.copy(id = 2L, tenantCode = "tenant-b")

        withContext(TenantContext.asContextElement(tenant)) {
            val jobs = listOf(
                launch(Dispatchers.Default) {
                    TenantContext.withTenant(other) {
                        TenantContext.requireCurrent() shouldBeEqualTo other
                    }
                    TenantContext.requireCurrent() shouldBeEqualTo tenant
                },
                launch(Dispatchers.Default) {
                    TenantContext.requireCurrent() shouldBeEqualTo tenant
                },
            )
            jobs.forEach { it.join() }
            TenantContext.requireCurrent() shouldBeEqualTo tenant
        }
        TenantContext.current().shouldBeNull()
    }

    @Test
    fun `cancellation restores thread local state without swallowing cancellation`() = runTest {
        val job = launch(Dispatchers.Default + TenantContext.asContextElement(tenant)) {
            TenantContext.requireCurrent() shouldBeEqualTo tenant
            awaitCancellation()
        }

        job.cancelAndJoin()
        TenantContext.current().shouldBeNull()
    }
}
