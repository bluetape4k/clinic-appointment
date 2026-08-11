package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.time.Clock

data class QuarantineRetentionResult(
    val expiredCount: Int,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 불변 metadata나 audit history를 삭제하지 않고 quarantine payload 보존 정책을 적용합니다.
 */
class QuarantineRetentionService(
    private val repository: SchedulingQuarantineRepository,
    private val clock: Clock,
    private val batchSize: Int = 100,
) {

    fun expireEligiblePayloads(actor: String, reason: String): QuarantineRetentionResult =
        transaction {
            QuarantineRetentionResult(
                expiredCount = repository.expireEligiblePayloads(clock.instant(), actor, reason, batchSize),
            )
        }
}
