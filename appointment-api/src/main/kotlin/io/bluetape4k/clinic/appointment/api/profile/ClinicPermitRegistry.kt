package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 프로필 재평가 작업의 병원별 동시 실행 permit을 활성 참조가 있는 동안만 보관합니다.
 *
 * 참조 수에는 permit 보유자와 대기자가 모두 포함됩니다. 같은 병원 키의 생성, 참조
 * 변경, 제거는 [ConcurrentHashMap.compute]에서 직렬화하므로 제거와 재확보가 경쟁해도
 * 활성 semaphore가 둘 생기지 않습니다.
 */
internal class ClinicPermitRegistry(
    private val permits: Int,
    private val metrics: ProfileReevaluationMetrics?,
) {
    private val entries = ConcurrentHashMap<ClinicKey, PermitEntry>()

    init {
        require(permits > 0) { "permits must be positive" }
    }

    suspend fun <T> withPermit(
        scope: ProfileReevaluationScope,
        action: suspend () -> T,
    ): T {
        val key = ClinicKey(scope.tenantGroupId, scope.clinicId)
        val entry = retain(key)
        return try {
            entry.semaphore.withPermit {
                action()
            }
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: ClinicKey): PermitEntry {
        var retained: PermitEntry? = null
        entries.compute(key) { _, current ->
            val entry =
                current ?: PermitEntry(Semaphore(permits)).also {
                    metrics?.recordClinicPermitEntryCreated()
                }
            entry.referenceCount++
            retained = entry
            entry
        }
        return checkNotNull(retained)
    }

    private fun release(
        key: ClinicKey,
        retained: PermitEntry,
    ) {
        entries.compute(key) { _, current ->
            check(current === retained) {
                "clinic permit entry changed while it was still referenced"
            }
            check(retained.referenceCount > 0) {
                "clinic permit entry reference count must be positive"
            }
            retained.referenceCount--
            if (retained.referenceCount == 0) {
                metrics?.recordClinicPermitEntryEvicted()
                null
            } else {
                retained
            }
        }
    }

    private class PermitEntry(
        val semaphore: Semaphore,
        var referenceCount: Int = 0,
    )

    private class ClinicKey(
        private val tenantGroupId: Long,
        private val clinicId: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is ClinicKey &&
                        tenantGroupId == other.tenantGroupId &&
                        clinicId == other.clinicId
                )

        override fun hashCode(): Int =
            31 * tenantGroupId.hashCode() + clinicId.hashCode()
    }
}
