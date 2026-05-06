package io.bluetape4k.clinic.appointment.api.config

import org.springframework.cache.Cache
import org.springframework.cache.support.AbstractCacheManager

/**
 * [NearCacheAdapter] 목록을 Spring [org.springframework.cache.CacheManager]로 노출하는 구현체.
 *
 * [AbstractCacheManager.loadCaches]를 구현하여 초기화 시 어댑터 목록을 등록한다.
 */
class NearCacheCacheManager(
    private val adapters: List<Cache>,
) : AbstractCacheManager() {

    override fun loadCaches(): Collection<Cache> = adapters
}
