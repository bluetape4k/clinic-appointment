package io.bluetape4k.clinic.appointment.api.tenant

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * dispatcher 전환 사이에서 [TenantContext]를 전파하는 coroutine context element입니다.
 */
class TenantContextElement(
    private val tenantInfo: TenantInfo?,
) : ThreadContextElement<TenantInfo?>,
    AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TenantContextElement>

    override fun updateThreadContext(context: CoroutineContext): TenantInfo? =
        TenantContext.set(tenantInfo)

    override fun restoreThreadContext(context: CoroutineContext, oldState: TenantInfo?) {
        TenantContext.restore(oldState)
    }
}
