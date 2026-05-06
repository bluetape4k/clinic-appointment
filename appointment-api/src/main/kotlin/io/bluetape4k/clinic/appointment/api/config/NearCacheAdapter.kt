package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.cache.Cache
import org.springframework.cache.Cache.ValueWrapper
import org.springframework.cache.support.AbstractValueAdaptingCache
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.Callable

/**
 * [NearCacheOperations]를 Spring [Cache] 인터페이스로 브릿지하는 어댑터.
 *
 * [AbstractValueAdaptingCache]를 상속하여 [get(key, type)] / [get(key, valueLoader)] 기본 구현을 위임한다.
 * - 빈 리스트(emptyList)는 캐시에 저장하지 않는다 (`put`, `putIfAbsent` 모두 적용).
 * - Redis 장애 시 예외를 삼키고 로그를 남겨 서비스 가용성을 유지한다.
 *
 * @param V 캐시 값 타입
 * @param name 캐시 이름 (Spring CacheManager 식별자)
 * @param delegate NearCacheOperations 구현체 (L1: Caffeine, L2: Redis)
 */
class NearCacheAdapter<V : Any>(
    private val name: String,
    private val delegate: NearCacheOperations<V>,
) : AbstractValueAdaptingCache(/* allowNullValues = */ false) {

    companion object : KLogging()

    override fun getName(): String = name
    override fun getNativeCache(): Any = delegate

    /**
     * 캐시에서 값을 조회한다. Redis 장애 시 null을 반환하여 캐시 미스로 처리한다.
     */
    override fun lookup(key: Any): Any? {
        return try {
            delegate.get(key.toString())
        } catch (e: Exception) {
            log.warn(e) { "캐시 조회 실패: name=$name, key=$key" }
            null
        }
    }

    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        val wrapper = get(key)
        if (wrapper != null) {
            @Suppress("UNCHECKED_CAST")
            return wrapper.get() as T?
        }
        return try {
            val value = valueLoader.call()
            put(key, value)
            value
        } catch (e: Exception) {
            throw Cache.ValueRetrievalException(key, valueLoader, e)
        }
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) return
        if (value is List<*> && value.isEmpty()) return
        try {
            @Suppress("UNCHECKED_CAST")
            delegate.put(key.toString(), value as V)
        } catch (e: Exception) {
            log.warn(e) { "캐시 저장 실패: name=$name, key=$key" }
        }
    }

    override fun putIfAbsent(key: Any, value: Any?): ValueWrapper? {
        if (value == null) return null
        if (value is List<*> && value.isEmpty()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val prev = delegate.putIfAbsent(key.toString(), value as V)
            prev?.let { SimpleValueWrapper(it) }
        } catch (e: Exception) {
            log.warn(e) { "캐시 putIfAbsent 실패: name=$name, key=$key" }
            null
        }
    }

    override fun evict(key: Any) {
        try {
            delegate.remove(key.toString())
        } catch (e: Exception) {
            log.warn(e) { "캐시 evict 실패: name=$name, key=$key" }
        }
    }

    override fun evictIfPresent(key: Any): Boolean {
        return try {
            val existed = delegate.get(key.toString()) != null
            if (existed) delegate.remove(key.toString())
            existed
        } catch (e: Exception) {
            log.warn(e) { "캐시 evictIfPresent 실패: name=$name, key=$key" }
            false
        }
    }

    override fun clear() {
        try {
            delegate.clearAll()
        } catch (e: Exception) {
            log.warn(e) { "캐시 전체 삭제 실패: name=$name" }
        }
    }
}
