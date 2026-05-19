package io.bluetape4k.clinic.appointment.api.tenant

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that propagates [TenantContext] across dispatcher hops.
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
