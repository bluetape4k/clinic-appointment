package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
}
