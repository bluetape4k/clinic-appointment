package io.bluetape4k.clinic.appointment.event.integration

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
) {

    fun expireEligiblePayloads(actor: String, reason: String): QuarantineRetentionResult =
        QuarantineRetentionResult(
            expiredCount = repository.expireEligiblePayloads(clock.instant(), actor, reason),
        )
}
