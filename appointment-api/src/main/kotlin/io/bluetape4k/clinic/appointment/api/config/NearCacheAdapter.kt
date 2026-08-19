package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.lettuce.core.RedisCommandInterruptedException
import io.lettuce.core.RedisCommandTimeoutException
import org.springframework.cache.Cache
import org.springframework.cache.Cache.ValueWrapper
import org.springframework.cache.support.AbstractValueAdaptingCache
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * [NearCacheOperations]를 Spring [Cache] 인터페이스로 브릿지하는 어댑터.
 *
 * [AbstractValueAdaptingCache]를 상속하여 [get(key, type)] / [get(key, valueLoader)] 기본 구현을 위임한다.
 * - 빈 리스트(emptyList)는 캐시에 저장하지 않는다 (`put`, `putIfAbsent` 모두 적용).
 * - 일반적인 캐시 백엔드 장애는 로그를 남기고 캐시 미스/실패로 처리한다.
 * - 취소·타임아웃·인터럽트는 호출자의 제어 흐름이므로 삼키지 않고 전파한다.
 * - 같은 키의 loader 호출은 하나의 in-flight 작업으로 합치며, 성공·실패·취소 후 반드시 정리한다.
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

    private val inFlightLoads = ConcurrentHashMap<String, BlockingLoad<Any>>()
    private val loadGeneration = AtomicLong(0)

    override fun getName(): String = name
    override fun getNativeCache(): Any = delegate

    /**
     * 캐시에서 값을 조회한다. Redis 장애 시 null을 반환하여 캐시 미스로 처리한다.
     */
    override fun lookup(key: Any): Any? {
        return try {
            delegate.get(key.toString())
        } catch (e: Exception) {
            rethrowControlFlow(e)
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

        val normalizedKey = key.toString()
        val flight = BlockingLoad<Any>()
        val existing = inFlightLoads.putIfAbsent(normalizedKey, flight)
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.await() as T
        }

        val generation = loadGeneration.get()
        try {
            val value = try {
                val loaded = valueLoader.call()
                if (loadGeneration.get() == generation) {
                    put(key, loaded)
                }
                loaded
            } catch (e: Exception) {
                rethrowControlFlow(e)
                throw Cache.ValueRetrievalException(key, valueLoader, e)
            }
            flight.complete(value)
            return value
        } catch (e: Throwable) {
            flight.completeExceptionally(e)
            throw e
        } finally {
            inFlightLoads.remove(normalizedKey, flight)
        }
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) return
        if (value is List<*> && value.isEmpty()) return
        try {
            @Suppress("UNCHECKED_CAST")
            delegate.put(key.toString(), value as V)
        } catch (e: Exception) {
            rethrowControlFlow(e)
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
            rethrowControlFlow(e)
            log.warn(e) { "캐시 putIfAbsent 실패: name=$name, key=$key" }
            // 저장 실패를 실제 기존 값으로 오인하지 않도록 null을 반환한다.
            null
        }
    }

    override fun evict(key: Any) {
        try {
            delegate.remove(key.toString())
        } catch (e: Exception) {
            rethrowControlFlow(e)
            log.warn(e) { "캐시 evict 실패: name=$name, key=$key" }
        }
    }

    override fun evictIfPresent(key: Any): Boolean {
        return try {
            // getAndRemove는 원자적으로 조회+삭제를 수행하여 TOCTOU 경합을 방지한다
            delegate.getAndRemove(key.toString()) != null
        } catch (e: Exception) {
            rethrowControlFlow(e)
            log.warn(e) { "캐시 evictIfPresent 실패: name=$name, key=$key" }
            false
        }
    }

    override fun clear() {
        loadGeneration.incrementAndGet()
        inFlightLoads.clear()
        try {
            delegate.clearAll()
        } catch (e: Exception) {
            rethrowControlFlow(e)
            log.warn(e) { "캐시 전체 삭제 실패: name=$name" }
        }
    }

    private fun rethrowControlFlow(e: Exception) {
        when (e) {
            is CancellationException -> throw e
            is TimeoutException -> throw e
            is RedisCommandTimeoutException -> throw e
            is RedisCommandInterruptedException -> {
                if (e.cause is InterruptedException) Thread.currentThread().interrupt()
                throw e
            }
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }
}

private class BlockingLoad<V: Any> {

    private val completed = CountDownLatch(1)

    @Volatile
    private var value: V? = null

    @Volatile
    private var error: Throwable? = null

    fun complete(value: V) {
        this.value = value
        completed.countDown()
    }

    fun completeExceptionally(error: Throwable) {
        this.error = error
        completed.countDown()
    }

    fun await(): V {
        try {
            completed.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }

        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as V
    }
}
