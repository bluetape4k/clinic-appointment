package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select

/**
 * caller transaction에서 인증 actor scope별 command를 선점하고 replay를 판정합니다.
 */
class AppointmentCommandIdempotencyRepository {

    /**
     * 정확한 scope와 key를 처음 insert하면 `ACQUIRED`, 같은 command면 `REPLAY`를
     * 반환합니다.
     *
     * @throws IllegalArgumentException 같은 scope/key에 다른 command hash가 이미 있으면
     * 발생합니다.
     */
    fun claim(
        tenantGroupId: Long,
        clinicId: Long,
        actorScopeHash: String,
        idempotencyKey: String,
        commandHash: String,
    ): CommandClaimResult {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        actorScopeHash.requireNotBlank("actorScopeHash")
        idempotencyKey.requireNotBlank("idempotencyKey")
        commandHash.requireNotBlank("commandHash")
        require(commandHash.length == 64) { "commandHash must be a 64-character SHA-256 hex value" }

        val inserted = AppointmentCommandIdempotencies.insertIgnore {
            it[AppointmentCommandIdempotencies.tenantGroupId] = tenantGroupId
            it[AppointmentCommandIdempotencies.clinicId] = clinicId
            it[AppointmentCommandIdempotencies.actorScopeHash] = actorScopeHash
            it[AppointmentCommandIdempotencies.idempotencyKey] = idempotencyKey
            it[AppointmentCommandIdempotencies.commandHash] = commandHash
        }.insertedCount == 1
        if (inserted) {
            return CommandClaimResult.ACQUIRED
        }

        val existingHash = AppointmentCommandIdempotencies
            .select(AppointmentCommandIdempotencies.commandHash)
            .where {
                (AppointmentCommandIdempotencies.tenantGroupId eq tenantGroupId) and
                    (AppointmentCommandIdempotencies.clinicId eq clinicId) and
                    (AppointmentCommandIdempotencies.actorScopeHash eq actorScopeHash) and
                    (AppointmentCommandIdempotencies.idempotencyKey eq idempotencyKey)
            }
            .single()[AppointmentCommandIdempotencies.commandHash]
        require(existingHash == commandHash) {
            "idempotency key is already bound to a different command hash"
        }
        return CommandClaimResult.REPLAY
    }
}
