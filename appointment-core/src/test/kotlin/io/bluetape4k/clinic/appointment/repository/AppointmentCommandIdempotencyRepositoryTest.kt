package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.dto.CommandClaimResult
import org.junit.jupiter.api.Test

class AppointmentCommandIdempotencyRepositoryTest {

    private val repository = AppointmentCommandIdempotencyRepository()

    @Test
    fun `actor scope별 command를 선점하고 같은 hash replay만 허용한다`() {
        withCommitmentTables { seed ->
            repository.claim(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                actorScopeHash = "actor-a",
                idempotencyKey = "request-1",
                commandHash = "a".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
            repository.claim(
                1L,
                seed.clinicId,
                "actor-a",
                "request-1",
                "a".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.REPLAY
            assertFailsWith<IllegalArgumentException> {
                repository.claim(
                    1L,
                    seed.clinicId,
                    "actor-a",
                    "request-1",
                    "b".repeat(64),
                )
            }
            repository.claim(
                1L,
                seed.clinicId,
                "actor-b",
                "request-1",
                "b".repeat(64),
            ) shouldBeEqualTo CommandClaimResult.ACQUIRED
        }
    }
}
