package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.io.Serializable
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 유효 예약 정책 캐시 항목 하나를 식별하는 정확한 키다.
 *
 * 키에 권위 저장소에서 읽은 세대 벡터와 두 평가 시각을 모두 포함한다. 따라서 정책 활성화 후
 * 이전 세대 항목이 메모리에 남아 있더라도 새 세대로 조회할 때 일치할 수 없다. 캐시는 정책의
 * 최신 여부를 스스로 판단하지 않으며, 호출자가 먼저 데이터베이스의 현재 스코프 헤드를 읽은 뒤
 * 이 키를 만들어야 한다.
 *
 * @property tenantGroupId 신뢰할 수 있는 서비스 컨텍스트에서 얻은 양수 테넌트 식별자. 정책
 * JSON이나 클라이언트가 보낸 캐시 토큰에서 가져오면 안 된다.
 * @property clinicId 유효 정책 조회 전에 해당 테넌트 소속임을 검증한 양수 병원 식별자.
 * @property generation 권위 저장소의 스코프 헤드에서 읽은 정확한 테넌트/병원 세대 벡터.
 * 완전한 기본 정책이 존재하면 테넌트 세대는 양수여야 하며, 병원별 재정의가 한 번도 활성화되지
 * 않았다면 병원 세대는 `0`일 수 있다.
 * @property decisionAt 의사결정 시점 기준 정책을 평가하는 정확한 UTC 시각. 나노초까지 키에
 * 포함하며 캐시 내부에서는 로컬 시간대 정규화를 수행하지 않는다.
 * @property serviceAt 시술 시점 기준 정책을 평가하는 정확한 UTC 시각. [decisionAt]보다
 * 빠를 수 없으며 DST 누락·중복 시간 해석은 이 키를 만들기 전에 끝나야 한다.
 */
data class EffectivePolicyCacheKey(
    val tenantGroupId: Long,
    val clinicId: Long,
    val generation: PolicyGenerationVector,
    val decisionAt: Instant,
    val serviceAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(generation.tenantGeneration > 0L) { "tenantGeneration must be positive" }
        require(generation.clinicGeneration >= 0L) { "clinicGeneration must be non-negative" }
        require(!serviceAt.isBefore(decisionAt)) { "serviceAt must not be before decisionAt" }
    }
}

/**
 * [EffectivePolicyCache]가 사용할 수 있는 항목 수와 추정 메모리의 상한이다.
 *
 * 모든 값은 상품 정책이 아니라 프로세스 단위 안전 제한이다. 신뢰된 애플리케이션 설정으로만
 * 구성해야 하며 API 요청값을 그대로 사용하면 안 된다. 바이트 값은 스냅샷 직렬화 결과로 계산한
 * 보수적 추정치로, 무제한 보관을 막기 위한 값이지 정확한 JVM 힙 사용량은 아니다.
 *
 * @property maximumEntries 전체 테넌트에 걸쳐 보관할 수 있는 최대 항목 수. 양수이며 기본값은
 * `10,000`이다.
 * @property maximumEntriesPerTenant 동일한 [EffectivePolicyCacheKey.tenantGroupId]가
 * 보관할 수 있는 최대 항목 수. `1..maximumEntries` 범위이며 기본값은 `1,000`이다.
 * @property maximumEstimatedBytes 전체 항목의 추정 바이트 합계 상한. 양수이며 기본값은
 * `64 MiB`다.
 * @property maximumEstimatedBytesPerTenant 테넌트 하나에 허용하는 추정 바이트 상한.
 * `1..maximumEstimatedBytes` 범위이며 기본값은 `8 MiB`다.
 */
data class EffectivePolicyCacheLimits(
    val maximumEntries: Int = 10_000,
    val maximumEntriesPerTenant: Int = 1_000,
    val maximumEstimatedBytes: Long = 64L * 1024L * 1024L,
    val maximumEstimatedBytesPerTenant: Long = 8L * 1024L * 1024L,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        require(maximumEntriesPerTenant in 1..maximumEntries) {
            "maximumEntriesPerTenant must be between 1 and maximumEntries"
        }
        require(maximumEstimatedBytes > 0L) { "maximumEstimatedBytes must be positive" }
        require(maximumEstimatedBytesPerTenant in 1..maximumEstimatedBytes) {
            "maximumEstimatedBytesPerTenant must be between 1 and maximumEstimatedBytes"
        }
    }
}

/**
 * [EffectivePolicyCache]의 특정 시점 운영 통계 스냅샷이다.
 *
 * @property entryCount 현재 프로세스에 보관된 항목 수.
 * @property estimatedBytes 직렬화 기반으로 계산한 현재 추정 바이트 합계.
 * @property hitCount 캐시 생성 이후 정확한 키로 값을 찾은 누적 횟수.
 * @property missCount 캐시 생성 이후 정확한 키가 없었던 누적 횟수.
 * @property evictionCount 용량 제한을 지키기 위해 제거한 누적 항목 수. 정책 활성화 이벤트에
 * 따른 명시적 스코프 무효화는 포함하지 않는다.
 */
data class EffectivePolicyCacheStatistics(
    val entryCount: Int,
    val estimatedBytes: Long,
    val hitCount: Long,
    val missCount: Long,
    val evictionCount: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 유효 정책 스냅샷을 보관하는 스레드 안전·세대 인식·용량 제한 LRU 캐시다.
 *
 * 캐시 적중도 LRU 순서를 바꾸므로 읽기를 포함한 모든 접근을 하나의 [ReentrantLock]과
 * 접근 순서 [LinkedHashMap]으로 보호한다. 이 캐시는 성능 최적화일 뿐 데이터베이스를 읽거나
 * 최신성을 보증하지 않는다. `EffectiveSchedulingPolicyService`는 [get] 호출 전에 반드시
 * 권위 저장소의 스코프 세대를 읽어야 하며, 데이터베이스를 읽지 못한 경우 캐시로 우회하지 않고
 * 실패해야 한다.
 *
 * 이전 세대 항목은 용량 축출 또는 명시적 무효화 전까지 남을 수 있지만
 * [EffectivePolicyCacheKey.generation]이 정확히 일치해야 하므로 새 세대 조회에 사용되지
 * 않는다. 평가 시각도 의도적으로 정확한 값을 사용한다. 이 캐시는 동일한 평가 요청을 위한
 * 제한된 메모이제이션이며 임의의 요청 시각에 높은 적중률을 보장하지 않는다. 시각을 반올림하면
 * 정책 유효 구간 경계를 넘어 의미가 다른 스냅샷을 반환할 수 있다.
 *
 * @property limits 프로세스 단위 항목 수 및 추정 바이트 안전 상한.
 */
class EffectivePolicyCache(
    val limits: EffectivePolicyCacheLimits,
) {
    companion object : KLogging()

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<EffectivePolicyCacheKey, CacheEntry>(16, 0.75f, true)
    private val tenantUsage = mutableMapOf<Long, TenantUsage>()
    private var estimatedBytes = 0L
    private var hitCount = 0L
    private var missCount = 0L
    private var evictionCount = 0L

    /**
     * 권위 저장소의 세대를 포함한 정확한 키와 일치하는 값을 반환한다.
     *
     * `null`은 현재 프로세스 캐시에 정확한 항목이 없다는 뜻일 뿐이다. 이전 세대 캐시 사용을
     * 허용하지 않으며 데이터베이스에 스냅샷이 없음을 증명하지도 않는다.
     */
    fun get(key: EffectivePolicyCacheKey): EffectiveSchedulingPolicy? =
        lock.withLock {
            entries[key]?.value.also { value ->
                if (value == null) {
                    missCount++
                } else {
                    hitCount++
                }
            }
        }

    /**
     * 정확한 키의 스냅샷을 추가하거나 교체한 뒤 모든 용량 제한을 적용한다.
     *
     * @param key 권위 데이터베이스의 세대와 요청에서 UTC로 정규화한 평가 시각으로 만든 키.
     * @param value 컴파일된 불변 스냅샷. 테넌트, 병원, 세대, 두 평가 시각이 [key]와 정확히
     * 일치해야 한다.
     * @param estimatedBytes 직렬화 크기에 기반한 양수 바이트 추정치. 전역 또는 테넌트별
     * 바이트 상한보다 큰 단일 항목은 보관하지 않는다.
     * @return 용량 축출 이후에도 새 항목이 캐시에 남으면 `true`, 그렇지 않으면 `false`.
     * 캐시 보관을 거절해도 스냅샷 자체의 유효성에는 영향이 없다.
     */
    fun put(
        key: EffectivePolicyCacheKey,
        value: EffectiveSchedulingPolicy,
        estimatedBytes: Long,
    ): Boolean =
        lock.withLock {
            require(estimatedBytes > 0L) { "estimatedBytes must be positive" }
            require(value.tenantGroupId == key.tenantGroupId) { "value tenant does not match key" }
            require(value.clinicId == key.clinicId) { "value clinic does not match key" }
            require(value.generation == key.generation) { "value generation does not match key" }
            require(value.decisionAt == key.decisionAt) { "value decisionAt does not match key" }
            require(value.serviceAt == key.serviceAt) { "value serviceAt does not match key" }

            removeInternal(key, quotaEviction = false)
            if (estimatedBytes > limits.maximumEstimatedBytes ||
                estimatedBytes > limits.maximumEstimatedBytesPerTenant
            ) {
                log.debug { "Effective policy cache entry rejected: reason=entry_byte_quota" }
                return@withLock false
            }

            entries[key] = CacheEntry(value, estimatedBytes)
            tenantUsage.getOrPut(key.tenantGroupId, ::TenantUsage).add(estimatedBytes)
            this.estimatedBytes += estimatedBytes
            evictTenantUntilWithinLimits(key.tenantGroupId)
            evictGlobalUntilWithinLimits()
            key in entries
        }

    /**
     * 신뢰된 테넌트/병원 스코프에 속한 모든 항목을 제거한다.
     *
     * 정책 활성화 이벤트를 소비한 뒤 수행하는 성능 최적화다. 이후의 모든 조회가 데이터베이스
     * 세대를 다시 확인하므로 이벤트 유실이나 무효화 지연이 정확성에 영향을 주지 않는다.
     *
     * @return 제거한 항목 수. 프로세스 로컬 운영 지표일 뿐 권한 확인이나 감사 결과로 사용하면
     * 안 된다.
     */
    fun invalidateClinic(
        tenantGroupId: Long,
        clinicId: Long,
    ): Int =
        lock.withLock {
            require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
            require(clinicId > 0L) { "clinicId must be positive" }
            val keys = entries.keys
                .filter { it.tenantGroupId == tenantGroupId && it.clinicId == clinicId }
            keys.forEach { removeInternal(it, quotaEviction = false) }
            if (keys.isNotEmpty()) {
                log.debug { "Effective policy cache invalidated: reason=clinic_generation_event" }
            }
            keys.size
        }

    /** 하나의 잠금 구간에서 현재 크기와 프로세스 로컬 누적 카운터를 일관된 스냅샷으로 반환한다. */
    fun statistics(): EffectivePolicyCacheStatistics =
        lock.withLock {
            EffectivePolicyCacheStatistics(
                entryCount = entries.size,
                estimatedBytes = estimatedBytes,
                hitCount = hitCount,
                missCount = missCount,
                evictionCount = evictionCount,
            )
        }

    private fun evictTenantUntilWithinLimits(tenantGroupId: Long) {
        while ((tenantUsage[tenantGroupId]?.entryCount ?: 0) > limits.maximumEntriesPerTenant ||
            (tenantUsage[tenantGroupId]?.estimatedBytes ?: 0L) > limits.maximumEstimatedBytesPerTenant
        ) {
            val oldest = entries.keys.firstOrNull { it.tenantGroupId == tenantGroupId } ?: return
            removeInternal(oldest, quotaEviction = true)
        }
    }

    private fun evictGlobalUntilWithinLimits() {
        while (entries.size > limits.maximumEntries || estimatedBytes > limits.maximumEstimatedBytes) {
            val oldest = entries.keys.firstOrNull() ?: return
            removeInternal(oldest, quotaEviction = true)
        }
    }

    private fun removeInternal(
        key: EffectivePolicyCacheKey,
        quotaEviction: Boolean,
    ) {
        val removed = entries.remove(key) ?: return
        tenantUsage[key.tenantGroupId]?.let { usage ->
            usage.remove(removed.estimatedBytes)
            if (usage.entryCount == 0) {
                tenantUsage.remove(key.tenantGroupId)
            }
        }
        estimatedBytes -= removed.estimatedBytes
        if (quotaEviction) {
            evictionCount++
            log.debug { "Effective policy cache entry evicted: reason=quota" }
        }
    }

    private data class CacheEntry(
        val value: EffectiveSchedulingPolicy,
        val estimatedBytes: Long,
    )

    private data class TenantUsage(
        var entryCount: Int = 0,
        var estimatedBytes: Long = 0L,
    ) {
        fun add(bytes: Long) {
            entryCount++
            estimatedBytes += bytes
        }

        fun remove(bytes: Long) {
            entryCount--
            estimatedBytes -= bytes
            check(entryCount >= 0 && estimatedBytes >= 0L) {
                "effective policy cache tenant usage became negative"
            }
        }
    }
}
