package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheKey
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyCompiler
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.jdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageInvalidation
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageSnapshot
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant

/**
 * Issue #313의 test-only detached snapshot fixture다.
 *
 * 운영 Spring bean이나 [EffectivePolicyCache] wiring을 건드리지 않고, H2 JDBC root transaction과
 * bluetape4k-exposed JDBC Caffeine facade의 commit/rollback/fence 경계를 재현한다.
 */
internal class JdbcCaffeineEffectivePolicyPilotFixture : AutoCloseable {
    private val database = Database.connect(
        url = "jdbc:h2:mem:issue313_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val cache = jdbcCaffeineSnapshotCache<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>(
        CaffeineSnapshotCacheConfig(
            snapshot = SnapshotCacheConfig("clinic-policy-jdbc-caffeine:v1", "effective-policy-v1"),
            maximumSize = 64,
            expireAfterWrite = Duration.ofMinutes(5),
            expireAfterAccess = Duration.ofMinutes(1),
            fenceStripes = 64,
        )
    )
    private val baselineCache = EffectivePolicyCache(EffectivePolicyCacheLimits())

    fun sample(
        tenantGroupId: Long = 1L,
        clinicId: Long = 11L,
        generation: Long = 1L,
    ): Sample {
        val value = SchedulingPolicyCompiler.compileCapacity(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = Instant.parse("2026-10-25T00:30:00Z"),
            serviceAt = Instant.parse("2026-10-25T01:30:00Z"),
            generation = PolicyGenerationVector(generation, 0L),
            tenantVersion = 1L,
            clinicVersion = null,
            tenant = CapacityAndOverbookingPolicy(
                nominalCapacity = 10,
                overbookingQuota = 2,
                absoluteBookingLimit = 12,
                automaticReductionEnabled = true,
            ),
            clinic = null,
        )
        return Sample(
            key = EffectivePolicyCacheKey(
                tenantGroupId = value.tenantGroupId,
                clinicId = value.clinicId,
                generation = value.generation,
                decisionAt = value.decisionAt,
                serviceAt = value.serviceAt,
            ),
            value = value,
        )
    }

    fun capture(sample: Sample): SnapshotCacheMiss<EffectivePolicyCacheKey, EffectiveSchedulingPolicy> =
        cache.lookup(sample.key).miss ?: error("fixture requires a cache miss")

    fun commit(sample: Sample, miss: SnapshotCacheMiss<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>) {
        transaction(database) {
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(sample.value, revision = sample.value.snapshotHash))
        }
    }

    fun rollback(sample: Sample, miss: SnapshotCacheMiss<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>) {
        transaction(database) {
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(sample.value, revision = sample.value.snapshotHash))
            throw RollbackMarker()
        }
    }

    fun publishAfterGenerationCheck(
        sample: Sample,
        miss: SnapshotCacheMiss<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>,
        generationMatches: Boolean,
    ): Boolean {
        if (!generationMatches) return false
        commit(sample, miss)
        return true
    }

    fun invalidate(key: EffectivePolicyCacheKey) {
        transaction(database) {
            stageInvalidation(cache, key)
        }
    }

    fun lookup(key: EffectivePolicyCacheKey): EffectiveSchedulingPolicy? =
        cache.lookup(key).snapshot?.value

    fun lookupBaseline(key: EffectivePolicyCacheKey): EffectiveSchedulingPolicy? = baselineCache.get(key)

    fun publish(sample: Sample, pilotEnabled: Boolean) {
        if (!pilotEnabled) {
            baselineCache.put(sample.key, sample.value, estimatedBytes = 1L)
            return
        }
        commit(sample, capture(sample))
    }

    override fun close() {
        TransactionManager.closeAndUnregister(database)
    }

    data class Sample(
        val key: EffectivePolicyCacheKey,
        val value: EffectiveSchedulingPolicy,
    )

    class RollbackMarker : RuntimeException()
}
