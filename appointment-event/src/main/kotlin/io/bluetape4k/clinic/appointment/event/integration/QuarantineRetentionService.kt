package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable
import java.time.Clock

data class QuarantineRetentionResult(
    val expiredCount: Int,
) : Serializable

/**
 * Applies quarantine payload retention without deleting immutable metadata or audit history.
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
