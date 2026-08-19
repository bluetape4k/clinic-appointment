package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.lettuce.core.RedisCommandTimeoutException
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.cache.Cache
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NearCacheAdapter] 단위 테스트.
 *
 * MockK로 [NearCacheOperations]를 mock하여 어댑터의 동작을 검증한다.
 */
class NearCacheAdapterTest {

    private lateinit var delegate: NearCacheOperations<List<String>>
    private lateinit var adapter: NearCacheAdapter<List<String>>

    @BeforeEach
    fun setUp() {
        delegate = mockk()
        adapter = NearCacheAdapter("test-cache", delegate)
    }

    // 1. get(key) → delegate.get() 값 반환 시 Cache.ValueWrapper로 감쌈
    @Test
    fun `get - delegate가 값을 반환하면 ValueWrapper로 감싸서 반환한다`() {
        val key = "key1"
        val expected = listOf("a", "b")
        every { delegate.get(key) } returns expected

        val result = adapter.get(key)

        result.shouldNotBeNull()
        result.get() shouldBeEqualTo expected
    }

    // 2. get(key) → delegate.get() null 반환 시 null 반환
    @Test
    fun `get - delegate가 null을 반환하면 null을 반환한다`() {
        val key = "missing-key"
        every { delegate.get(key) } returns null

        val result = adapter.get(key)

        result.shouldBeNull()
    }

    // 3. lookup(key) 중 delegate.get() 예외 → null 반환 (adapter.get("key") == null)
    @Test
    fun `get - delegate에서 예외 발생 시 null을 반환한다`() {
        val key = "error-key"
        every { delegate.get(key) } throws RuntimeException("Redis 연결 오류")

        val result = adapter.get(key)

        result.shouldBeNull()
    }

    @Test
    fun `get - 취소 예외는 삼키지 않고 호출자에게 전파한다`() {
        val key = "cancelled-key"
        every { delegate.get(key) } throws CancellationException("조회 취소")

        assertFailsWith<CancellationException> {
            adapter.get(key)
        }
    }

    // 4. put(key, null) → delegate.put 미호출
    @Test
    fun `put - null 값은 delegate에 저장하지 않는다`() {
        adapter.put("key1", null)

        verify(exactly = 0) { delegate.put(any(), any()) }
    }

    // 5. put(key, emptyList()) → delegate.put 미호출
    @Test
    fun `put - 빈 리스트는 delegate에 저장하지 않는다`() {
        adapter.put("key1", emptyList<String>())

        verify(exactly = 0) { delegate.put(any(), any()) }
    }

    // 6. put(key, nonEmptyList) → delegate.put(key, nonEmptyList) 1회 호출
    @Test
    fun `put - 비어있지 않은 리스트는 delegate에 1회 저장한다`() {
        val key = "key1"
        val value = listOf("x", "y")
        justRun { delegate.put(key, value) }

        adapter.put(key, value)

        verify(exactly = 1) { delegate.put(key, value) }
    }

    // 7. put(key, value) Redis 예외 시 → 예외 전파 없음 (정상 완료)
    @Test
    fun `put - delegate에서 예외 발생 시 예외를 전파하지 않는다`() {
        val key = "key1"
        val value = listOf("x", "y")
        every { delegate.put(key, value) } throws RuntimeException("Redis 장애")

        // 예외가 전파되지 않으면 정상 완료
        adapter.put(key, value)
    }

    // 8. put(key, value) 제어 흐름 예외는 호출자에게 전파
    @Test
    fun `put - 취소 예외는 삼키지 않고 호출자에게 전파한다`() {
        val key = "key1"
        val value = listOf("x", "y")
        every { delegate.put(key, value) } throws CancellationException("호출 취소")

        assertFailsWith<CancellationException> {
            adapter.put(key, value)
        }
    }

    @Test
    fun `put - 타임아웃 예외는 삼키지 않고 호출자에게 전파한다`() {
        val key = "key1"
        val value = listOf("x", "y")
        every { delegate.put(key, value) } throws TimeoutException("Redis 타임아웃")

        assertFailsWith<TimeoutException> {
            adapter.put(key, value)
        }
    }

    @Test
    fun `put - Lettuce command timeout도 삼키지 않고 호출자에게 전파한다`() {
        val key = "key1"
        val value = listOf("x", "y")
        every { delegate.put(key, value) } throws RedisCommandTimeoutException("Redis command timeout")

        assertFailsWith<RedisCommandTimeoutException> {
            adapter.put(key, value)
        }
    }

    // 8. evict(key) → delegate.remove(key) 1회 호출
    @Test
    fun `evict - delegate의 remove를 1회 호출한다`() {
        val key = "key1"
        justRun { delegate.remove(key) }

        adapter.evict(key)

        verify(exactly = 1) { delegate.remove(key) }
    }

    // 9. clear() → delegate.clearAll() 1회 호출
    @Test
    fun `clear - delegate의 clearAll을 1회 호출한다`() {
        justRun { delegate.clearAll() }

        adapter.clear()

        verify(exactly = 1) { delegate.clearAll() }
    }

    @Test
    fun `clear - 취소 예외는 삼키지 않고 호출자에게 전파한다`() {
        every { delegate.clearAll() } throws CancellationException("정리 취소")

        assertFailsWith<CancellationException> {
            adapter.clear()
        }
    }

    @Test
    fun `clear - 진행 중 loader는 clear 이후 stale 값을 다시 저장하지 않는다`() {
        val key = "clear-race-key"
        val loaded = listOf("stale")
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        every { delegate.get(key) } returns null
        justRun { delegate.clearAll() }

        try {
            val future = executor.submit<List<String>> {
                adapter.get(key) {
                    loaderStarted.countDown()
                    releaseLoader.await()
                    loaded
                }!!
            }
            loaderStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            adapter.clear()
            releaseLoader.countDown()

            future.get(5, TimeUnit.SECONDS) shouldBeEqualTo loaded
            verify(exactly = 0) { delegate.put(key, loaded) }
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    // 10. putIfAbsent(key, value) → 키 없을 때(delegate.putIfAbsent 반환 null): null 반환
    @Test
    fun `putIfAbsent - 키가 없을 때 null을 반환한다`() {
        val key = "new-key"
        val value = listOf("a")
        every { delegate.putIfAbsent(key, value) } returns null

        val result = adapter.putIfAbsent(key, value)

        result.shouldBeNull()
    }

    // 11. putIfAbsent(key, value) → 키 있을 때(delegate.putIfAbsent 반환 기존값): SimpleValueWrapper(prev) 반환
    @Test
    fun `putIfAbsent - 키가 이미 있을 때 기존 값을 담은 ValueWrapper를 반환한다`() {
        val key = "existing-key"
        val value = listOf("new")
        val existing = listOf("old")
        every { delegate.putIfAbsent(key, value) } returns existing

        val result = adapter.putIfAbsent(key, value)

        result.shouldNotBeNull()
        result.get() shouldBeEqualTo existing
    }

    // 12. putIfAbsent(key, null) → delegate 미호출, null 반환
    @Test
    fun `putIfAbsent - null 값은 delegate를 호출하지 않고 null을 반환한다`() {
        val result = adapter.putIfAbsent("key1", null)

        result.shouldBeNull()
        verify(exactly = 0) { delegate.putIfAbsent(any(), any()) }
    }

    // 13. putIfAbsent(key, emptyList) → delegate 미호출, null 반환
    @Test
    fun `putIfAbsent - 빈 리스트는 delegate를 호출하지 않고 null을 반환한다`() {
        val result = adapter.putIfAbsent("key1", emptyList<String>())

        result.shouldBeNull()
        verify(exactly = 0) { delegate.putIfAbsent(any(), any()) }
    }

    // 14. putIfAbsent 예외 시 → null 반환 (저장 실패를 기존 값으로 오인하지 않음)
    @Test
    fun `putIfAbsent - 예외 발생 시 null을 반환하여 저장 실패를 나타낸다`() {
        val key = "key1"
        val value = listOf("v")
        every { delegate.putIfAbsent(key, value) } throws RuntimeException("Redis 장애")

        val result = adapter.putIfAbsent(key, value)

        result.shouldBeNull()
    }

    // 15. get(key, valueLoader) → 캐시 미스 시 valueLoader 호출 후 저장
    @Test
    fun `get valueLoader - 캐시 미스 시 valueLoader를 호출하고 결과를 저장한다`() {
        val key = "new-key"
        val loaded = listOf("loaded")
        every { delegate.get(key) } returns null
        justRun { delegate.put(key, loaded) }

        val result = adapter.get(key) { loaded }

        result shouldBeEqualTo loaded
        verify(exactly = 1) { delegate.put(key, loaded) }
    }

    @Test
    fun `get valueLoader - 같은 키의 동시 미스는 loader를 한 번만 호출한다`() {
        val key = "same-key"
        val loaded = listOf("loaded")
        val lookupCount = AtomicInteger()
        val loaderCount = AtomicInteger()
        val loaderStarted = CountDownLatch(1)
        val secondLookupDone = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        every { delegate.get(key) } answers {
            if (lookupCount.incrementAndGet() == 2) secondLookupDone.countDown()
            null
        }
        justRun { delegate.put(key, loaded) }

        try {
            val first = executor.submit<List<String>> {
                adapter.get(key) {
                    loaderCount.incrementAndGet()
                    loaderStarted.countDown()
                    releaseLoader.await()
                    loaded
                }!!
            }

            loaderStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val second = executor.submit<List<String>> {
                val forbiddenLoader = Callable<List<String>> {
                    throw AssertionError("두 번째 loader는 호출되면 안 된다")
                }
                adapter.get(key, forbiddenLoader)!!
            }
            secondLookupDone.await(5, TimeUnit.SECONDS).shouldBeTrue()
            loaderCount.get() shouldBeEqualTo 1

            releaseLoader.countDown()
            first.get(5, TimeUnit.SECONDS) shouldBeEqualTo loaded
            second.get(5, TimeUnit.SECONDS) shouldBeEqualTo loaded
            loaderCount.get() shouldBeEqualTo 1
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `get valueLoader - loader 취소 후에는 동일 키를 다시 로드할 수 있다`() {
        val key = "cancelled-key"
        every { delegate.get(key) } returns null
        val loaderCount = AtomicInteger()

        assertFailsWith<CancellationException> {
            adapter.get(key) {
                loaderCount.incrementAndGet()
                throw CancellationException("loader 취소")
            }
        }

        adapter.get(key) {
            loaderCount.incrementAndGet()
            listOf("retry")
        } shouldBeEqualTo listOf("retry")
        loaderCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `get valueLoader - loader 타임아웃은 ValueRetrievalException으로 감싸지 않는다`() {
        val key = "timeout-key"
        every { delegate.get(key) } returns null

        assertFailsWith<TimeoutException> {
            adapter.get(key, Callable<List<String>> { throw TimeoutException("loader 타임아웃") })
        }
    }

    @Test
    fun `get valueLoader - loader 실패 후에는 동일 키를 다시 로드할 수 있다`() {
        val key = "failed-key"
        val retryValue = listOf("retry")
        every { delegate.get(key) } returns null
        justRun { delegate.put(key, retryValue) }
        val loaderCount = AtomicInteger()

        assertFailsWith<Cache.ValueRetrievalException> {
            adapter.get(key, Callable<List<String>> {
                loaderCount.incrementAndGet()
                throw RuntimeException("loader 실패")
            })
        }

        adapter.get(key, Callable { loaderCount.incrementAndGet(); retryValue }) shouldBeEqualTo retryValue
        loaderCount.get() shouldBeEqualTo 2
    }

    // 16. get(key, valueLoader) → 캐시 히트 시 valueLoader 미호출
    @Test
    fun `get valueLoader - 캐시 히트 시 valueLoader를 호출하지 않는다`() {
        val key = "cached-key"
        val cached = listOf("cached")
        every { delegate.get(key) } returns cached

        var loaderCalled = false
        val result: List<String>? = adapter.get(key) { loaderCalled = true; listOf() }

        result shouldBeEqualTo cached
        loaderCalled.shouldBeFalse()
        verify(exactly = 0) { delegate.put(any(), any()) }
    }

    // 17. get(key, valueLoader) → valueLoader 예외 시 ValueRetrievalException 전파
    @Test
    fun `get valueLoader - valueLoader에서 예외 발생 시 ValueRetrievalException을 던진다`() {
        val key = "error-key"
        every { delegate.get(key) } returns null

        assertFailsWith<Cache.ValueRetrievalException> {
            adapter.get(key) { throw RuntimeException("로드 실패") }
        }
    }

    // 18. evictIfPresent → 키 존재 시 getAndRemove 호출 후 true 반환
    @Test
    fun `evictIfPresent - 키가 존재하면 삭제하고 true를 반환한다`() {
        val key = "key1"
        every { delegate.getAndRemove(key) } returns listOf("v")

        val result = adapter.evictIfPresent(key)

        result.shouldBeTrue()
        verify(exactly = 1) { delegate.getAndRemove(key) }
    }

    // 19. evictIfPresent → 키 없으면 false 반환
    @Test
    fun `evictIfPresent - 키가 없으면 false를 반환한다`() {
        val key = "missing-key"
        every { delegate.getAndRemove(key) } returns null

        val result = adapter.evictIfPresent(key)

        result.shouldBeFalse()
    }

    // 20. evictIfPresent → 예외 시 false 반환
    @Test
    fun `evictIfPresent - 예외 발생 시 false를 반환한다`() {
        val key = "error-key"
        every { delegate.getAndRemove(key) } throws RuntimeException("Redis 장애")

        val result = adapter.evictIfPresent(key)

        result.shouldBeFalse()
    }
}
