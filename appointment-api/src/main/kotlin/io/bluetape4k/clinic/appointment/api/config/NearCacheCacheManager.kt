package io.bluetape4k.clinic.appointment.api.config

import org.springframework.cache.Cache
import org.springframework.cache.support.AbstractCacheManager

/**
 * [NearCacheAdapter] 목록을 Spring [org.springframework.cache.CacheManager]로 노출하는 구현체.
 *
 * [AbstractCacheManager.loadCaches]를 구현하여 초기화 시 어댑터 목록을 등록한다.
 * 생성 시 중복된 캐시 이름이 존재하면 [IllegalArgumentException]을 던진다.
 */
class NearCacheCacheManager(
    private val adapters: List<NearCacheAdapter<*>>,
) : AbstractCacheManager() {

    override fun loadCaches(): Collection<Cache> {
        val names = adapters.map { it.name }
        val duplicates = names.groupBy { it }.filter { it.value.size > 1 }.keys
        require(duplicates.isEmpty()) { "중복된 캐시 이름: $duplicates" }
        return adapters
    }
}
