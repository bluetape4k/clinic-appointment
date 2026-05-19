package io.bluetape4k.clinic.appointment.api.tenant

/**
 * Thread-local holder for the tenant resolved from the current request path.
 */
object TenantContext {

    private val tenantHolder = ThreadLocal<TenantInfo?>()

    /**
     * Returns the current tenant, or `null` when the request is not tenant scoped.
     */
    fun current(): TenantInfo? = tenantHolder.get()

    /**
     * Returns the current tenant or fails when tenant-scoped code is called outside
     * a tenant request.
     */
    fun requireCurrent(): TenantInfo =
        current() ?: error("Tenant context is not available")

    /**
     * Runs [block] with [tenantInfo] installed and restores the previous tenant.
     */
    fun <T> withTenant(tenantInfo: TenantInfo, block: () -> T): T {
        val previous = set(tenantInfo)
        return try {
            block()
        } finally {
            restore(previous)
        }
    }

    /**
     * Creates a coroutine context element that propagates the current tenant.
     */
    fun asContextElement(tenantInfo: TenantInfo? = current()): TenantContextElement =
        TenantContextElement(tenantInfo)

    internal fun set(tenantInfo: TenantInfo?): TenantInfo? {
        val previous = tenantHolder.get()
        if (tenantInfo == null) {
            tenantHolder.remove()
        } else {
            tenantHolder.set(tenantInfo)
        }
        return previous
    }

    internal fun restore(tenantInfo: TenantInfo?) {
        if (tenantInfo == null) {
            tenantHolder.remove()
        } else {
            tenantHolder.set(tenantInfo)
        }
    }

    internal fun clear() {
        tenantHolder.remove()
    }
}
