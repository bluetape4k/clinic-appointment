package io.bluetape4k.clinic.appointment.api.tenant

/**
 * 현재 요청 경로에서 해석한 tenant를 보관하는 thread-local holder입니다.
 */
object TenantContext {

    private val tenantHolder = ThreadLocal<TenantInfo?>()

    /**
     * 현재 tenant를 반환하며, 요청이 tenant scope가 아니면 `null`을 반환합니다.
     */
    fun current(): TenantInfo? = tenantHolder.get()

    /**
     * 현재 tenant를 반환하며, tenant 요청 밖에서 tenant-scoped code가 호출되면 실패합니다.
     */
    fun requireCurrent(): TenantInfo =
        current() ?: error("Tenant context is not available")

    /**
     * [tenantInfo]를 설치한 상태로 [block]을 실행하고 이전 tenant를 복원합니다.
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
     * 현재 tenant를 전파하는 coroutine context element를 생성합니다.
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
