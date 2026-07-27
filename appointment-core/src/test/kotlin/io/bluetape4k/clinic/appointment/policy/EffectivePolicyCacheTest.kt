package io.bluetape4k.clinic.appointment.policy

import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheKey
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyCompiler
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 유효 정책 캐시가 전역·테넌트별 개수와 추정 바이트 상한을 지키는지 검증한다.
 *
 * 캐시는 권위 저장소가 아니므로 정확한 세대·두 평가 시각을 모두 key로 사용해야 하며,
 * 무효화와 동시 접근 중에도 사용량 counter가 quota를 넘거나 음수로 무너지면 안 된다.
 */
class EffectivePolicyCacheTest {

    @Test
    fun `global access-order limit evicts the least recently used entry`() {
        val cache = EffectivePolicyCache(
            EffectivePolicyCacheLimits(
                maximumEntries = 2,
                maximumEntriesPerTenant = 2,
                maximumEstimatedBytes = 10_000,
                maximumEstimatedBytesPerTenant = 10_000,
            )
        )
        val first = snapshot(tenantGroupId = 1L, clinicId = 11L, generation = 1L)
        val second = snapshot(tenantGroupId = 1L, clinicId = 12L, generation = 1L)
        val third = snapshot(tenantGroupId = 2L, clinicId = 21L, generation = 1L)

        cache.put(key(first), first, estimatedBytes = 100)
        cache.put(key(second), second, estimatedBytes = 100)
        cache.get(key(first)) shouldBeSameInstanceAs first
        cache.put(key(third), third, estimatedBytes = 100)

        cache.get(key(first)) shouldBeSameInstanceAs first
        cache.get(key(second)).shouldBeNull()
        cache.get(key(third)) shouldBeSameInstanceAs third
        cache.statistics().entryCount shouldBeEqualTo 2
    }

    @Test
    fun `tenant quota evicts only that tenant oldest entry before global entries`() {
        val cache = EffectivePolicyCache(
            EffectivePolicyCacheLimits(
                maximumEntries = 4,
                maximumEntriesPerTenant = 1,
                maximumEstimatedBytes = 10_000,
                maximumEstimatedBytesPerTenant = 10_000,
            )
        )
        val tenantOneOld = snapshot(1L, 11L, 1L)
        val tenantTwo = snapshot(2L, 21L, 1L)
        val tenantOneNew = snapshot(1L, 12L, 1L)

        cache.put(key(tenantOneOld), tenantOneOld, estimatedBytes = 100)
        cache.put(key(tenantTwo), tenantTwo, estimatedBytes = 100)
        cache.put(key(tenantOneNew), tenantOneNew, estimatedBytes = 100)

        cache.get(key(tenantOneOld)).shouldBeNull()
        cache.get(key(tenantTwo)) shouldBeSameInstanceAs tenantTwo
        cache.get(key(tenantOneNew)) shouldBeSameInstanceAs tenantOneNew
    }

    @Test
    fun `byte quotas evict old entries and reject an entry that cannot fit alone`() {
        val cache = EffectivePolicyCache(
            EffectivePolicyCacheLimits(
                maximumEntries = 10,
                maximumEntriesPerTenant = 10,
                maximumEstimatedBytes = 250,
                maximumEstimatedBytesPerTenant = 150,
            )
        )
        val first = snapshot(1L, 11L, 1L)
        val second = snapshot(1L, 12L, 1L)
        val oversized = snapshot(2L, 21L, 1L)

        cache.put(key(first), first, estimatedBytes = 100)
        cache.put(key(second), second, estimatedBytes = 100)
        cache.put(key(oversized), oversized, estimatedBytes = 300)

        cache.get(key(first)).shouldBeNull()
        cache.get(key(second)) shouldBeSameInstanceAs second
        cache.get(key(oversized)).shouldBeNull()
        cache.statistics().estimatedBytes shouldBeEqualTo 100L
    }

    @Test
    fun `generation and both UTC instants are exact cache key boundaries`() {
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val value = snapshot(tenantGroupId = 1L, clinicId = 11L, generation = 7L)
        val key = key(value)
        cache.put(key, value, estimatedBytes = 100)

        cache.get(key) shouldBeSameInstanceAs value
        cache.get(key.copy(generation = PolicyGenerationVector(8L, 0L))).shouldBeNull()
        cache.get(key.copy(decisionAt = key.decisionAt.plusNanos(1))).shouldBeNull()
        cache.get(key.copy(serviceAt = key.serviceAt.plusNanos(1))).shouldBeNull()
    }

    @Test
    fun `scope invalidation removes matching clinic without affecting sibling clinic`() {
        val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val first = snapshot(1L, 11L, 1L)
        val sibling = snapshot(1L, 12L, 1L)
        cache.put(key(first), first, estimatedBytes = 100)
        cache.put(key(sibling), sibling, estimatedBytes = 100)

        cache.invalidateClinic(tenantGroupId = 1L, clinicId = 11L) shouldBeEqualTo 1

        cache.get(key(first)).shouldBeNull()
        cache.get(key(sibling)) shouldBeSameInstanceAs sibling
    }

    @Test
    fun `concurrent reads writes and invalidation preserve bounded usage counters`() {
        val cache = EffectivePolicyCache(
            EffectivePolicyCacheLimits(
                maximumEntries = 64,
                maximumEntriesPerTenant = 32,
                maximumEstimatedBytes = 6_400,
                maximumEstimatedBytesPerTenant = 3_200,
            )
        )
        val snapshots = (0 until 80).map { index ->
            snapshot(
                tenantGroupId = (index % 4 + 1).toLong(),
                clinicId = (index % 20 + 1).toLong(),
                generation = (index / 20 + 1).toLong(),
            )
        }
        val operationCounter = AtomicInteger()
        MultithreadingTester()
            .workers(8)
            .rounds(125)
            .add {
                val operation = operationCounter.getAndIncrement()
                val value = snapshots[operation % snapshots.size]
                cache.put(key(value), value, estimatedBytes = 100L)
                cache.get(key(value))
                if (operation % 17 == 0) {
                    cache.invalidateClinic(value.tenantGroupId, value.clinicId)
                }
            }
            .run()

        val statistics = cache.statistics()
        operationCounter.get() shouldBeEqualTo 1_000
        (statistics.entryCount <= 64) shouldBeEqualTo true
        (statistics.estimatedBytes <= 6_400L) shouldBeEqualTo true
        (statistics.evictionCount > 0L).shouldBeTrue()
    }

    private fun snapshot(
        tenantGroupId: Long,
        clinicId: Long,
        generation: Long,
    ): EffectiveSchedulingPolicy =
        SchedulingPolicyCompiler.compileCapacity(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = Instant.parse("2026-10-25T00:30:00Z"),
            serviceAt = Instant.parse("2026-10-25T01:30:00Z"),
            generation = PolicyGenerationVector(generation, 0L),
            tenantVersion = 1L,
            clinicVersion = null,
            tenant = io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingPolicy(
                nominalCapacity = 10,
                overbookingQuota = 2,
                absoluteBookingLimit = 12,
                automaticReductionEnabled = true,
            ),
            clinic = null,
        )

    private fun key(value: EffectiveSchedulingPolicy): EffectivePolicyCacheKey =
        EffectivePolicyCacheKey(
            tenantGroupId = value.tenantGroupId,
            clinicId = value.clinicId,
            generation = value.generation,
            decisionAt = value.decisionAt,
            serviceAt = value.serviceAt,
        )
}
