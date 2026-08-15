package io.bluetape4k.clinic.appointment.api.service

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** cursor의 최초 발급 시각과 token bytes만 보관하는 공유 registry 경계입니다. */
interface PatientHistoryTokenRegistry {
    /** 만료 전 entry를 읽습니다. 저장소 장애는 [PatientHistoryRegistryException]으로 표현합니다. */
    fun get(key: String): PatientHistoryTokenEntry?

    /** 기존 entry가 있으면 그것을, 없으면 원자적으로 새 entry를 반환합니다. */
    fun putIfAbsent(
        key: String,
        entry: PatientHistoryTokenEntry,
    ): PatientHistoryTokenEntry

    /** registry가 cursor 발급·검증을 수행할 수 있는지 반환합니다. */
    fun isReady(): Boolean = true

    /** 호출자가 전달한 monotonic deadline 안에서 registry read를 수행합니다. */
    fun getWithin(key: String, deadlineNanos: Long?): PatientHistoryTokenEntry? {
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        val result = get(key)
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        return result
    }

    /** 호출자가 전달한 monotonic deadline 안에서 registry write를 수행합니다. */
    fun putIfAbsentWithin(
        key: String,
        entry: PatientHistoryTokenEntry,
        deadlineNanos: Long?,
    ): PatientHistoryTokenEntry {
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        val result = putIfAbsent(key, entry)
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        return result
    }

    /** readiness probe도 동일한 request budget을 넘기지 않도록 합니다. */
    fun isReadyWithin(deadlineNanos: Long?): Boolean {
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        val result = isReady()
        ensurePatientHistoryRegistryDeadline(deadlineNanos)
        return result
    }
}

internal fun ensurePatientHistoryRegistryDeadline(deadlineNanos: Long?) {
    if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
        throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.TIMEOUT)
    }
}

/** registry가 저장하는 민감정보가 제한된 cursor entry입니다. */
data class PatientHistoryTokenEntry(
    val token: String,
    val issuedAt: Instant,
)

/** registry 장애를 원인 분류와 함께 API 계층으로 전달합니다. */
class PatientHistoryRegistryException(
    val reason: PatientHistoryRegistryFailureReason,
    cause: Throwable? = null,
) : RuntimeException(reason.name, cause)

/** metric label로 사용할 수 있는 고정 registry 장애 분류입니다. */
enum class PatientHistoryRegistryFailureReason {
    TIMEOUT,
    UNAVAILABLE,
    CAPACITY_FULL,
    NON_LINEARIZABLE,
    MISSING_ENTRY,
    COLLISION,
}

/** 테스트와 local profile에서만 사용하는 bounded registry 구현입니다. */
class InMemoryPatientHistoryTokenRegistry(
    private val clock: () -> Instant = { Instant.now() },
    private val ttl: Duration = Duration.ofMinutes(30),
    private val capacity: Int = 10_000,
) : PatientHistoryTokenRegistry {
    private val entries = ConcurrentHashMap<String, PatientHistoryTokenEntry>()

    init {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive" }
        require(capacity > 0) { "capacity must be positive" }
    }

    override fun get(key: String): PatientHistoryTokenEntry? {
        val entry = entries[key] ?: return null
        if (entry.issuedAt.plus(ttl).isBefore(clock())) {
            entries.remove(key, entry)
            return null
        }
        return entry
    }

    override fun putIfAbsent(
        key: String,
        entry: PatientHistoryTokenEntry,
    ): PatientHistoryTokenEntry {
        get(key)?.let { return it }
        if (entries.size >= capacity && !entries.containsKey(key)) {
            throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.CAPACITY_FULL)
        }
        val previous = entries.putIfAbsent(key, entry)
        return previous ?: entry
    }
}
