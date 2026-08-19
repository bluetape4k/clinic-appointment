package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalException
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionStatus
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import java.time.Instant
import org.junit.jupiter.api.Test

class AppointmentOperationalExceptionRepositoryTest {

    private val repository = AppointmentOperationalExceptionRepository()

    @Test
    fun `운영 예외 append와 acknowledge resolve는 caller transaction에서 상태를 전이한다`() {
        withCommitmentTables { seed ->
            val openedAt = Instant.parse("2026-08-19T00:00:00Z")
            val id = repository.append(
                AppointmentOperationalException(
                    appointmentPlanId = seed.planId,
                    appointmentId = seed.appointmentId,
                    type = AppointmentOperationalExceptionType.RESOURCE_DISRUPTION,
                    reasonCode = "ROOM_UNAVAILABLE",
                    status = AppointmentOperationalExceptionStatus.OPEN,
                    openedAt = openedAt,
                    resolvedAt = null,
                ),
            )

            id.shouldBeGreaterThan(0L)
            repository.acknowledge(id).shouldBeTrue()
            repository.resolve(id, openedAt.plusSeconds(60)).shouldBeTrue()
        }
    }
}
